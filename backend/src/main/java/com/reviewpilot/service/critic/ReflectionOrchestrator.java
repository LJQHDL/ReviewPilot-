package com.reviewpilot.service.critic;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.ReviewReplyReader;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** 编排"质量检查 → 可选修订"闭环，向流水线返回结构化结果而非直接改写评审。 */
@Component
public class ReflectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReflectionOrchestrator.class);

    private final ModelProvider modelProvider;
    private final CriticAgent criticAgent;
    private final CriticPromptBuilder prompts;
    private final ReviewReplyReader replyReader;
    private final boolean enabled;

    public ReflectionOrchestrator(ModelProvider modelProvider,
                                   CriticAgent criticAgent,
                                   CriticPromptBuilder prompts,
                                   ReviewReplyReader replyReader,
                                   @Value("${reviewpilot.agent.reflection.enabled:true}") boolean enabled) {
        this.modelProvider = modelProvider;
        this.enabled = enabled;
        this.criticAgent = criticAgent;
        this.prompts = prompts;
        this.replyReader = replyReader;
        log.info("Reflection orchestrator ready, enabled={}", enabled);
    }

    /** 闭环产出：revision 非 null 表示修订成功、流水线应替换原结果；criticIssues 供日志与诊断。 */
    public record RefinementResult(ReviewResult revision, List<String> criticIssues) {
        public boolean needsRevision() {
            return revision != null;
        }
    }

    /**
     * 运行反思闭环：Critic 检查 → 视需要修订。
     * 配置禁用时立即返回空结果，让流水线以零额外 LLM 调用直接输出原评审。
     *
     * <p>{@code userPrompt} 同时充当 Critic 比对用的"被评审代码"；修订结果只有在
     * 成功解析为 JSON 后才会被采纳——从未变成 JSON 的回复绝不能顶替已经合法的评审。
     */
    public RefinementResult refine(ReviewResult v0, List<RiskItem> ruleRisks,
                                    String systemPrompt, String userPrompt) {
        if (!enabled) {
            return new RefinementResult(null, List.of());
        }

        // 第 1 步：运行 Critic（解析失败时内部自动重试一次）
        CriticResult critic = criticAgent.inspect(v0, ruleRisks, userPrompt);

        if (!critic.needsRevision()) {
            log.debug("Critic found no issues — review passes on first attempt");
            return new RefinementResult(null, List.of());
        }

        log.info("Critic found {} issue(s), triggering revision. Issues: {}",
                critic.issues().size(), critic.issues());

        // 第 2 步：把 Critic 反馈注入修订版 system prompt，重出一版评审
        String revisionSystem = prompts.revisionSystemPrompt(systemPrompt, critic.issues());

        String revisionRaw = modelProvider.complete(revisionSystem, userPrompt);
        if (revisionRaw == null || revisionRaw.isBlank()) {
            return new RefinementResult(null, critic.issues());
        }
        // 修订回复解析失败时保留原评审，只回传 critic 问题清单
        return replyReader.readOrNull(revisionRaw, feedback ->
                        modelProvider.complete(revisionSystem + "\n\n" + feedback, userPrompt))
                .map(revision -> new RefinementResult(revision, critic.issues()))
                .orElseGet(() -> {
                    log.warn("Revision reply never parsed; serving the unrevised review");
                    return new RefinementResult(null, critic.issues());
                });
    }

}
