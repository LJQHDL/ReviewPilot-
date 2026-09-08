package com.reviewpilot.pipeline;

import com.reviewpilot.model.Deadline;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.AgentReview;
import com.reviewpilot.service.risk.RiskMerger;
import com.reviewpilot.service.ai.ReviewAgent;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextLoader;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.context.RiskFileContextLoader;
import com.reviewpilot.service.github.FetchedFiles;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.RepoAllowlist;
import com.reviewpilot.service.prompt.PromptBuilder;
import com.reviewpilot.service.critic.ReflectionOrchestrator;
import com.reviewpilot.service.risk.RiskDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 完整评审用例：准备证据（抓取/分类/规则检测/上下文）→ ReAct Agent 评审 → 反思修订 → 合并风险并组装 API 结果。 */
@Service
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final GithubPrFetcher fetcher;
    private final FileClassifier classifier;
    private final RiskDetector riskDetector;
    private final ContextLoader contextLoader;
    private final RiskFileContextLoader contentFetcher;
    private final PromptBuilder promptBuilder;
    private final ModelProvider modelProvider;
    private final ReviewAgent reviewAgent;
    private final ReflectionOrchestrator orchestrator;
    private final RiskMerger riskMerger;
    private final RepoAllowlist allowlist;
    private final long requestBudgetMillis;
    private final int maxPrefetchFiles;

    public ReviewPipeline(GithubPrFetcher fetcher,
                          FileClassifier classifier,
                          RiskDetector riskDetector,
                          ContextLoader contextLoader,
                          RiskFileContextLoader contentFetcher,
                          PromptBuilder promptBuilder,
                          ModelProvider modelProvider,
                          ReviewAgent reviewAgent,
                          ReflectionOrchestrator orchestrator,
                          RiskMerger riskMerger,
                          RepoAllowlist allowlist,
                          @Value("${reviewpilot.agent.request-budget-ms:45000}") long requestBudgetMillis,
                          @Value("${reviewpilot.agent.content-fetcher.max-prefetch-files:5}") int maxPrefetchFiles) {
        this.fetcher = fetcher;
        this.classifier = classifier;
        this.riskDetector = riskDetector;
        this.contextLoader = contextLoader;
        this.contentFetcher = contentFetcher;
        this.promptBuilder = promptBuilder;
        this.modelProvider = modelProvider;
        this.reviewAgent = reviewAgent;
        this.orchestrator = orchestrator;
        this.riskMerger = riskMerger;
        this.allowlist = allowlist;
        this.requestBudgetMillis = requestBudgetMillis;
        this.maxPrefetchFiles = maxPrefetchFiles;
    }

    /** 评审主流程：预处理各阶段确定性地串起来，LLM 阶段由 ReviewAgent 自主决策。 */
    public ReviewResult review(String prUrlRaw) {
        long started = System.currentTimeMillis();
        // 全请求墙钟预算：超时后跳过预取与反思，不再启动新的模型调用
        Deadline deadline = Deadline.of(requestBudgetMillis);
        PrUrl pr = PrUrl.parse(prUrlRaw);
        allowlist.requireAllowed(pr);

        FetchedFiles fetched = fetcher.fetchFiles(pr);
        List<FileChange> files = fetched.files();
        log.debug("PR {}/{}#{} → {} files{}", pr.owner(), pr.repo(), pr.number(),
                files.size(), fetched.truncated() ? " (TRUNCATED at page cap)" : "");

        // 空 PR 短路：不消耗任何 LLM 调用直接返回空结果
        if (files.isEmpty()) {
            return new ReviewResult(
                    prUrlRaw,
                    "(no changed files in this PR)",
                    List.of(),
                    List.of(),
                    new ReviewResult.Meta(modelProvider.name(), modelProvider.modelName(), 0,
                            System.currentTimeMillis() - started)
            );
        }

        // 逐文件分类 + 规则风险扫描 + 加载 diff 上下文切片
        Map<String, FileType> classifications = new HashMap<>();
        for (FileChange f : files) {
            classifications.put(f.filename(), classifier.classify(f));
        }
        List<RiskItem> ruleRisks = riskDetector.scan(files);
        List<ContextSlice> contexts = contextLoader.load(files, ruleRisks);

        // 为规则命中风险的文件抓取完整文件内容，让 AI 看到比 diff 上下文（±3 行）更宽的视野。
        // 必须设上限：每个文件要多打两次 GitHub API，风险密集的 PR 曾让该成本随发现数无限增长。
        List<String> riskyPaths = ruleRisks.stream()
                .map(RiskItem::file)
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .toList();
        Map<String, String> fullFileContents =
                (riskyPaths.isEmpty() || maxPrefetchFiles <= 0 || deadline.expired())
                        ? Map.of()
                        : contentFetcher.fetchForRiskyFiles(pr,
                                riskyPaths.stream().limit(maxPrefetchFiles).toList());
        log.debug("Pipeline: {} files, {} rule risks, {} context slices, {} full-file fetches",
                files.size(), ruleRisks.size(), contexts.size(), fullFileContents.size());

        // ── ReAct Agent 评审（LLM + 工具循环） ──
        long llmStart = System.currentTimeMillis();
        String prTitle = fetcher.fetchPrTitle(pr);
        AgentReview agentReview = reviewAgent.review(files, classifications, ruleRisks,
                contexts, prTitle, pr, deadline);
        ReviewResult parsed = agentReview.result();
        long llmMs = System.currentTimeMillis() - llmStart;
        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.build(files, classifications, ruleRisks, contexts,
                fullFileContents, prTitle);

        int agentRounds = 1;
        // 反思是可选的质量环节：预算耗尽时直接跳过，
        // 不再启动客户端已经不会等待的模型调用。
        ReflectionOrchestrator.RefinementResult refinement = deadline.expired()
                ? new ReflectionOrchestrator.RefinementResult(null, List.of())
                : orchestrator.refine(parsed, ruleRisks, systemPrompt, userPrompt);
        if (refinement.needsRevision()) {
            parsed = refinement.revision();
            agentRounds = 2;
        }

        // 规则风险与 AI 风险去重合并，形成最终风险列表
        List<RiskItem> mergedRisks = riskMerger.merge(ruleRisks, parsed.risks());

        long totalMs = System.currentTimeMillis() - started;
        log.info("review_done pr={}/{} files={} rule_risks={} ai_risks={} merged_risks={} "
                        + "full_files={} agent_rounds={} react_rounds={} tool_calls={} "
                        + "prompt_tokens={} completion_tokens={} truncated={} llm_ms={} total_ms={}",
                pr.owner(), pr.repo(), files.size(), ruleRisks.size(), parsed.risks().size(),
                mergedRisks.size(), fullFileContents.size(), agentRounds,
                agentReview.reactRounds(), agentReview.toolCallCount(),
                agentReview.promptTokens(), agentReview.completionTokens(),
                fetched.truncated(), llmMs, totalMs);

        return new ReviewResult(
                prUrlRaw,
                parsed.summary(),
                mergedRisks,
                parsed.suggestions(),
                parsed.keyFindings(),
                new ReviewResult.Meta(modelProvider.name(), modelProvider.modelName(), files.size(),
                        totalMs, agentRounds,
                        agentReview.reactRounds(), agentReview.toolCallCount(),
                        agentReview.promptTokens(), agentReview.completionTokens(),
                        fetched.truncated())
        );
    }

}
