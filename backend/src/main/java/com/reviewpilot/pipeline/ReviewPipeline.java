package com.reviewpilot.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.model.Suggestion;
import com.reviewpilot.service.ai.JsonReplyCleaner;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.ReviewAgent;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextLoader;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.FileContentFetcher;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.prompt.PromptBuilder;
import com.reviewpilot.service.critic.ReflectionOrchestrator;
import com.reviewpilot.service.risk.RiskDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates the full review pipeline:
 * <pre>
 *   PrUrl
 *     → GithubPrFetcher    (fetch changed files + parse hunks)
 *     → FileClassifier     (route each file to a FileType)
 *     → RiskDetector       (run rule scans, collect RiskItems)
 *     → ContextLoader      (extract code windows around risks)
 *     → PromptBuilder      (group by type, embed risks + context)
 *     → ModelProvider      (call the LLM)
 *     → ReviewResult       (parse JSON + merge rule risks)
 * </pre>
 * The pipeline lives behind {@link com.reviewpilot.controller.ReviewController}.
 * Rule-detected risks are surfaced both in the prompt (so the AI builds on them)
 * and merged into the final {@code ReviewResult.risks()} (so the UI shows them
 * even when the model omits one).
 */
@Service
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final GithubPrFetcher fetcher;
    private final FileClassifier classifier;
    private final RiskDetector riskDetector;
    private final ContextLoader contextLoader;
    private final FileContentFetcher contentFetcher;
    private final PromptBuilder promptBuilder;
    private final ModelProvider modelProvider;
    private final ReviewAgent reviewAgent;
    private final ReflectionOrchestrator orchestrator;
    private final ObjectMapper json = new ObjectMapper();

    public ReviewPipeline(GithubPrFetcher fetcher,
                          FileClassifier classifier,
                          RiskDetector riskDetector,
                          ContextLoader contextLoader,
                          FileContentFetcher contentFetcher,
                          PromptBuilder promptBuilder,
                          ModelProvider modelProvider,
                          ReviewAgent reviewAgent,
                          ReflectionOrchestrator orchestrator) {
        this.fetcher = fetcher;
        this.classifier = classifier;
        this.riskDetector = riskDetector;
        this.contextLoader = contextLoader;
        this.contentFetcher = contentFetcher;
        this.promptBuilder = promptBuilder;
        this.modelProvider = modelProvider;
        this.reviewAgent = reviewAgent;
        this.orchestrator = orchestrator;
    }

    public ReviewResult review(String prUrlRaw) {
        long started = System.currentTimeMillis();
        PrUrl pr = PrUrl.parse(prUrlRaw);

        List<FileChange> files = fetcher.fetchFiles(pr);
        log.debug("PR {}/{}#{} → {} files", pr.owner(), pr.repo(), pr.number(), files.size());

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

        Map<String, FileType> classifications = new HashMap<>();
        for (FileChange f : files) {
            classifications.put(f.filename(), classifier.classify(f));
        }
        List<RiskItem> ruleRisks = riskDetector.scan(files);
        List<ContextSlice> contexts = contextLoader.load(files, ruleRisks);

        // Fetch full file content for files that have rule-detected risks so the
        // AI gets wider context than just the diff hunk ±3 lines.
        List<String> riskyPaths = ruleRisks.stream()
                .map(RiskItem::file)
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .toList();
        Map<String, String> fullFileContents = riskyPaths.isEmpty()
                ? Map.of()
                : contentFetcher.fetchForRiskyFiles(pr, riskyPaths);
        log.debug("Pipeline: {} files, {} rule risks, {} context slices, {} full-file fetches",
                files.size(), ruleRisks.size(), contexts.size(), fullFileContents.size());

        // ── ReAct Agent review ──
        long llmStart = System.currentTimeMillis();
        String prTitle = fetcher.fetchPrTitle(pr);
        ReviewResult parsed = reviewAgent.review(files, classifications, ruleRisks,
                contexts, prTitle, pr);
        long llmMs = System.currentTimeMillis() - llmStart;
        String systemPrompt = promptBuilder.systemPrompt();
        String userPrompt = promptBuilder.build(files, classifications, ruleRisks, contexts,
                fullFileContents, prTitle);

        int agentRounds = 1;
        ReflectionOrchestrator.RefinementResult refinement =
                orchestrator.refine(parsed, ruleRisks, systemPrompt, userPrompt);
        if (refinement.needsRevision()) {
            parsed = parseWithRetry(refinement.revisionRaw(), prUrlRaw, systemPrompt, userPrompt);
            agentRounds = 2;
        }

        List<RiskItem> mergedRisks = mergeRisks(ruleRisks, parsed.risks());

        long totalMs = System.currentTimeMillis() - started;
        log.info("review_done pr={}/{} files={} rule_risks={} ai_risks={} merged_risks={} "
                        + "full_files={} agent_rounds={} llm_ms={} total_ms={}",
                pr.owner(), pr.repo(), files.size(), ruleRisks.size(), parsed.risks().size(),
                mergedRisks.size(), fullFileContents.size(), agentRounds, llmMs, totalMs);

        return new ReviewResult(
                prUrlRaw,
                parsed.summary(),
                mergedRisks,
                parsed.suggestions(),
                parsed.keyFindings(),
                new ReviewResult.Meta(modelProvider.name(), modelProvider.modelName(), files.size(),
                        System.currentTimeMillis() - started, agentRounds,
                        reviewAgent.getLastReactRounds(), reviewAgent.getLastToolCallCount())
        );
    }

    /**
     * Combines rule-detected and AI-detected risks into one list, deduplicated
     * by (file, line, message) so a model that faithfully echoes a rule finding
     * doesn't produce a duplicate row in the UI. Rule findings come first so
     * they remain visible even if the model omits them entirely.
     */
    private static List<RiskItem> mergeRisks(List<RiskItem> ruleRisks, List<RiskItem> aiRisks) {
        Set<String> seen = new LinkedHashSet<>();
        List<RiskItem> out = new ArrayList<>(ruleRisks.size() + aiRisks.size());
        for (RiskItem r : ruleRisks) addIfNew(r, seen, out);
        for (RiskItem r : aiRisks) addIfNew(r, seen, out);
        return out;
    }

    private static void addIfNew(RiskItem r, Set<String> seen, List<RiskItem> out) {
        if (r == null) return;
        String key = (r.file() == null ? "" : r.file()) + "|" + r.line() + "|" + (r.message() == null ? "" : r.message());
        if (seen.add(key)) out.add(r);
    }

    /**
     * Parses the model reply into a {@link ReviewResult}. Models occasionally
     * wrap JSON in markdown fences, add a leading sentence ("Here is the JSON:"),
     * or trail off after the closing brace. Strip fences first, then carve out
     * the substring from the first '{' to the last '}' so a slightly disobedient
     * response still parses cleanly.
     */
    ReviewResult parseModelReply(String raw, String prUrl) {
        String cleaned = JsonReplyCleaner.extractJsonObject(JsonReplyCleaner.stripFences(raw).trim());
        try {
            return parseCleaned(cleaned, prUrl);
        } catch (JsonProcessingException e) {
            log.warn("Model reply was not valid JSON ({} chars); returning fallback summary.",
                    cleaned.length());
            return new ReviewResult(prUrl, cleaned, List.of(), List.of(), null);
        }
    }

    /**
     * Parse the already-cleaned JSON string. Extracted so retry logic can
     * reuse it without duplicating the fences/prose stripping.
     */
    private ReviewResult parseCleaned(String cleaned, String prUrl) throws JsonProcessingException {
        JsonNode root = json.readTree(cleaned);
        String summary = textOrEmpty(root, "summary");
        List<RiskItem> risks = parseRisks(root.get("risks"));
        List<Suggestion> suggestions = parseSuggestions(root.get("suggestions"));
        return new ReviewResult(prUrl, summary, risks, suggestions, null);
    }

    /**
     * Parse the raw model reply with up to one retry when the reply is not valid
     * JSON. The retry feeds the parse error back into the system prompt so the
     * model can fix its output format. If the retry also fails, falls back to
     * surfacing the raw text as the summary — same degradation as before.
     */
    private ReviewResult parseWithRetry(String raw, String prUrl,
                                        String systemPrompt, String userPrompt) {
        String cleaned = JsonReplyCleaner.extractJsonObject(JsonReplyCleaner.stripFences(raw).trim());
        try {
            return parseCleaned(cleaned, prUrl);
        } catch (JsonProcessingException e) {
            log.warn("First parse attempt failed ({}), retrying with error feedback",
                    e.getOriginalMessage());
            String retrySystem = systemPrompt
                    + "\n\nCRITICAL: Your previous response was not valid JSON. "
                    + "Parser error: " + e.getOriginalMessage() + ". "
                    + "Respond with strictly valid JSON matching the required schema.";
            String raw2 = modelProvider.complete(retrySystem, userPrompt);
            String cleaned2 = JsonReplyCleaner.extractJsonObject(JsonReplyCleaner.stripFences(raw2).trim());
            try {
                return parseCleaned(cleaned2, prUrl);
            } catch (JsonProcessingException e2) {
                log.error("Retry also failed ({}), raw ({} chars)", e2.getOriginalMessage(), raw.length());
                String summary = extractField(raw, "summary");
                if (summary.isEmpty()) summary = extractField(cleaned2, "summary");
                if (summary.isEmpty()) summary = "(Parse error — see server logs)";
                return new ReviewResult(prUrl, summary, List.of(), List.of(), null);
            }
        }
    }

    /** Best-effort extraction of a single JSON string field via regex. */
    private static String extractField(String raw, String field) {
        if (raw == null) return "";
        var m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(raw);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", "\n") : "";
    }

    private List<RiskItem> parseRisks(JsonNode arr) {
        List<RiskItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            RiskLevel level = parseLevel(textOrEmpty(n, "level"));
            String file = textOrEmpty(n, "file");
            int line = n.path("line").asInt(0);
            String message = textOrEmpty(n, "message");
            if (!message.isBlank()) {
                out.add(new RiskItem(level, file, line, message));
            }
        }
        return out;
    }

    private List<Suggestion> parseSuggestions(JsonNode arr) {
        List<Suggestion> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String file = textOrEmpty(n, "file");
            int line = n.path("line").asInt(0);
            String message = textOrEmpty(n, "message");
            if (!message.isBlank()) {
                out.add(new Suggestion(file, line, message));
            }
        }
        return out;
    }

    private static RiskLevel parseLevel(String s) {
        if (s == null) return RiskLevel.LOW;
        try {
            return RiskLevel.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.LOW;
        }
    }

    private static String textOrEmpty(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

}
