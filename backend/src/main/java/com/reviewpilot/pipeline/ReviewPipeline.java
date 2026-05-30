package com.reviewpilot.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.model.Suggestion;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the Day1 main flow:
 * <pre>
 *   PrUrl → GithubPrFetcher → PromptBuilder → ModelProvider → ReviewResult
 * </pre>
 * PR#4-#6 inject FileClassifier / RiskDetector / ContextLoader between
 * fetch and prompt. The pipeline lives behind {@link com.reviewpilot.controller.ReviewController}.
 */
@Service
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);

    private final GithubPrFetcher fetcher;
    private final PromptBuilder promptBuilder;
    private final ModelProvider modelProvider;
    private final ObjectMapper json = new ObjectMapper();

    public ReviewPipeline(GithubPrFetcher fetcher,
                          PromptBuilder promptBuilder,
                          ModelProvider modelProvider) {
        this.fetcher = fetcher;
        this.promptBuilder = promptBuilder;
        this.modelProvider = modelProvider;
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
                    new ReviewResult.Meta(modelProvider.name(), null, 0,
                            System.currentTimeMillis() - started)
            );
        }

        String userPrompt = promptBuilder.build(files, java.util.Map.of(), List.of(), List.of());
        String raw = modelProvider.complete(promptBuilder.systemPrompt(), userPrompt);
        ReviewResult parsed = parseModelReply(raw, prUrlRaw);

        return new ReviewResult(
                parsed.prUrl(),
                parsed.summary(),
                parsed.risks(),
                parsed.suggestions(),
                new ReviewResult.Meta(modelProvider.name(), null, files.size(),
                        System.currentTimeMillis() - started)
        );
    }

    /**
     * Parses the model reply into a {@link ReviewResult}. Models occasionally
     * wrap JSON in markdown fences or add a leading sentence — strip those
     * before parsing so a slightly disobedient response doesn't fail the request.
     */
    ReviewResult parseModelReply(String raw, String prUrl) {
        String cleaned = stripFences(raw).trim();

        try {
            JsonNode root = json.readTree(cleaned);
            String summary = textOrEmpty(root, "summary");
            List<RiskItem> risks = parseRisks(root.get("risks"));
            List<Suggestion> suggestions = parseSuggestions(root.get("suggestions"));
            return new ReviewResult(prUrl, summary, risks, suggestions, null);
        } catch (JsonProcessingException e) {
            log.warn("Model reply was not valid JSON ({} chars); returning fallback summary.",
                    cleaned.length());
            // Fall back to surfacing whatever the model said as the summary so the
            // user gets something useful; downstream UI shows raw text in that field.
            return new ReviewResult(prUrl, cleaned, List.of(), List.of(), null);
        }
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

    /** Strip a leading/trailing ```json ... ``` fence the model sometimes adds. */
    static String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t;
    }
}
