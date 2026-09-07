package com.reviewpilot.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.core.JsonParser;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.model.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Decodes review replies consistently across initial review and revision.
 * The caller supplies transport-specific repair; this class owns format recovery. */
@Component
public class ReviewReplyReader {
    private static final Logger log = LoggerFactory.getLogger(ReviewReplyReader.class);
    private final ObjectMapper json = new ObjectMapper();

    public ReviewResult read(String raw, Function<String, String> repair) {
        return readOrNull(raw, repair).orElseGet(() -> {
            String summary = extractField(raw, "summary");
            if (summary.isEmpty()) summary = "(Parse error — see logs)";
            return new ReviewResult("", summary, List.of(), List.of(), null);
        });
    }

    /**
     * Parse and, if needed, repair — but report failure instead of inventing a
     * review. Callers that already hold a good result (the reflection revision)
     * must be able to keep it rather than adopt a parse-error placeholder.
     */
    public java.util.Optional<ReviewResult> readOrNull(String raw, Function<String, String> repair) {
        try {
            return java.util.Optional.of(parse(raw));
        } catch (JsonProcessingException first) {
            log.warn("Review JSON parse failed; requesting one format repair");
            String retry = repair.apply("Your previous response was not valid review JSON. Parser error: "
                    + first.getOriginalMessage() + ". Respond with a valid JSON object matching the review schema.");
            try {
                return java.util.Optional.of(parse(retry));
            } catch (JsonProcessingException second) {
                log.error("Review JSON repair failed ({}); caller decides how to degrade",
                        second.getOriginalMessage());
                return java.util.Optional.empty();
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

    public ReviewResult parse(String raw)
            throws JsonProcessingException {
        JsonNode root = json.readTree(
                JsonReplyCleaner.extractJsonObject(JsonReplyCleaner.stripFences(raw)));
        if (root == null || !root.isObject()) {
            throw JsonMappingException.from((JsonParser) null, "Expected a review JSON object");
        }
        String summary = root.has("summary") ? root.get("summary").asText("") : "";
        List<RiskItem> risks = parseRisks(root.get("risks"));
        List<Suggestion> suggestions = parseSuggestions(root.get("suggestions"));
        List<String> keyFindings = parseKeyFindings(root.get("keyFindings"));
        return new ReviewResult("", summary, risks, suggestions, keyFindings, null);
    }

    private static List<String> parseKeyFindings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String s = n.asText(null);
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private List<RiskItem> parseRisks(JsonNode arr) {
        List<RiskItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            RiskLevel level = parseLevel(n.has("level") ? n.get("level").asText("") : "");
            String file = n.has("file") ? n.get("file").asText("") : "";
            int line = n.has("line") ? n.get("line").asInt(0) : 0;
            String message = n.has("message") ? n.get("message").asText("") : "";
            if (!message.isBlank()) out.add(new RiskItem(level, file, line, message));
        }
        return out;
    }

    private List<Suggestion> parseSuggestions(JsonNode arr) {
        List<Suggestion> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String file = n.has("file") ? n.get("file").asText("") : "";
            int line = n.has("line") ? n.get("line").asInt(0) : 0;
            String message = n.has("message") ? n.get("message").asText("") : "";
            if (!message.isBlank()) out.add(new Suggestion(file, line, message));
        }
        return out;
    }

    private static RiskLevel parseLevel(String s) {
        if (s == null) return RiskLevel.LOW;
        try { return RiskLevel.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RiskLevel.LOW; }
    }

}
