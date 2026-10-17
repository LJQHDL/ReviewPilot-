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

/** 统一解码初始评审与修订两个环节返回的评审 JSON：调用方提供传输层的修复手段，本类负责格式恢复。 */
@Component
public class ReviewReplyReader {
    private static final Logger log = LoggerFactory.getLogger(ReviewReplyReader.class);
    private final ObjectMapper json = new ObjectMapper();

    /** 容错版读取：彻底失败时降级为只含 summary 的占位结果（用于初始评审）。 */
    public ReviewResult read(String raw, Function<String, String> repair) {
        return readOrNull(raw, repair).orElseGet(() -> {
            // 最后手段：用正则从烂文本里抢救 summary 字段
            String summary = extractField(raw, "summary");
            if (summary.isEmpty()) summary = "(Parse error — see logs)";
            return new ReviewResult("", summary, List.of(), List.of(), null);
        });
    }

    /**
     * 解析并在需要时修复一次——但失败时如实上报，绝不伪造评审内容。
     * 已持有好结果的调用方（反思修订环节）才能保留原结果，而不是采纳解析错误占位符。
     */
    public java.util.Optional<ReviewResult> readOrNull(String raw, Function<String, String> repair) {
        try {
            return java.util.Optional.of(parse(raw));
        } catch (JsonProcessingException first) {
            // 第一次失败：把解析错误反馈给 repair（通常是让 LLM 重写一次）再试
            log.warn("Review JSON parse failed; requesting one format repair");
            String retry = repair.apply("Your previous response was not valid review JSON. Parser error: "
                    + first.getOriginalMessage() + ". Respond with a valid JSON object matching the review schema.");
            try {
                return java.util.Optional.of(parse(retry));
            } catch (JsonProcessingException second) {
                // 修复后仍失败：记 ERROR 返回空，由调用方决定如何降级
                log.error("Review JSON repair failed ({}); caller decides how to degrade",
                        second.getOriginalMessage());
                return java.util.Optional.empty();
            }
        }
    }

    /** 用正则尽力抽取单个 JSON 字符串字段（仅供降级路径使用）。 */
    private static String extractField(String raw, String field) {
        if (raw == null) return "";
        var m = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(raw);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", "\n") : "";
    }

    /** 严格解析：清洗围栏/抽取 JSON 体后解析为 ReviewResult，字段缺失按空值容忍，非法 JSON 抛异常。 */
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

    /** 解析 keyFindings 字符串数组，跳过空白项。 */
    private static List<String> parseKeyFindings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            String s = n.asText(null);
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** 解析 risks 数组，丢弃 message 为空的条目。 */
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

    /** 解析 suggestions 数组，丢弃 message 为空的条目。 */
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

    /** 解析风险等级，无法识别时保守降为 LOW。 */
    private static RiskLevel parseLevel(String s) {
        if (s == null) return RiskLevel.LOW;
        try { return RiskLevel.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return RiskLevel.LOW; }
    }

}
