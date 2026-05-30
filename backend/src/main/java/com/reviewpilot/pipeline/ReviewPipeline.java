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
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextLoader;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.prompt.PromptBuilder;
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
    private final PromptBuilder promptBuilder;
    private final ModelProvider modelProvider;
    private final ObjectMapper json = new ObjectMapper();

    public ReviewPipeline(GithubPrFetcher fetcher,
                          FileClassifier classifier,
                          RiskDetector riskDetector,
                          ContextLoader contextLoader,
                          PromptBuilder promptBuilder,
                          ModelProvider modelProvider) {
        this.fetcher = fetcher;
        this.classifier = classifier;
        this.riskDetector = riskDetector;
        this.contextLoader = contextLoader;
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

        Map<String, FileType> classifications = new HashMap<>();
        for (FileChange f : files) {
            classifications.put(f.filename(), classifier.classify(f));
        }
        List<RiskItem> ruleRisks = riskDetector.scan(files);
        List<ContextSlice> contexts = contextLoader.load(files, ruleRisks);
        log.debug("Pipeline: {} files, {} rule risks, {} context slices",
                files.size(), ruleRisks.size(), contexts.size());

        String userPrompt = promptBuilder.build(files, classifications, ruleRisks, contexts);
        String raw = modelProvider.complete(promptBuilder.systemPrompt(), userPrompt);
        ReviewResult parsed = parseModelReply(raw, prUrlRaw);

        List<RiskItem> mergedRisks = mergeRisks(ruleRisks, parsed.risks());

        return new ReviewResult(
                parsed.prUrl(),
                parsed.summary(),
                mergedRisks,
                parsed.suggestions(),
                new ReviewResult.Meta(modelProvider.name(), null, files.size(),
                        System.currentTimeMillis() - started)
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
