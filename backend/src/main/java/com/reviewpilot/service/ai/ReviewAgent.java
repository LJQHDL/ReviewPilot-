package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.model.Suggestion;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent: replaces the single-shot LLM call with a multi-turn
 * Reasoning + Acting loop. The LLM autonomously decides what tools to
 * call and when to stop and produce the final review.
 */
@Service
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final int maxRounds;
    private final int maxTotalChars;

    public ReviewAgent(ModelProvider modelProvider,
                       ToolRegistry toolRegistry,
                       PromptBuilder promptBuilder,
                       @Value("${reviewpilot.agent.react.max-rounds:8}") int maxRounds,
                       @Value("${reviewpilot.agent.react.max-total-chars:64000}") int maxTotalChars) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.maxRounds = maxRounds;
        this.maxTotalChars = maxTotalChars;
    }

    private int lastReactRounds;
    private int lastToolCallCount;

    public int getLastReactRounds() { return lastReactRounds; }
    public int getLastToolCallCount() { return lastToolCallCount; }

    public ReviewResult review(List<FileChange> files,
                                Map<String, FileType> classifications,
                                List<RiskItem> ruleRisks,
                                List<ContextSlice> contexts,
                                String prTitle,
                                PrUrl pr) {
        // 1. Build initial messages
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(promptBuilder.reactSystemPrompt()));
        messages.add(Message.user(promptBuilder.reactUserPrompt(
                files, classifications, ruleRisks, contexts, prTitle,
                toolRegistry.getDefinitions())));

        // 2. ReAct loop
        int round = 0;
        int toolCallCount = 0;

        while (round < maxRounds) {
            round++;

            // Token budget: compress if over 80%
            if (estimateChars(messages) > maxTotalChars * 0.8) {
                messages = compressToolResults(messages);
            }
            // Still over limit after compression → force finish
            if (estimateChars(messages) > maxTotalChars) {
                messages.add(Message.user("上下文已满，请立即输出审查结果 JSON，不要调工具。"));
                return forceComplete(messages);
            }

            // Convergence: 4 tool calls then no more tools
            boolean giveTools = toolCallCount < 4;
            List<Tool> tools = giveTools ? toolRegistry.getDefinitions() : List.of();

            AgentResponse resp = modelProvider.chat(messages, tools);
            log.debug("ReAct round {}: toolCalls={} contentLen={}",
                    round, resp.hasToolCalls(), resp.content().length());

            if (resp.hasToolCalls()) {
                // Add the assistant message with tool_calls first (required by API spec)
                messages.add(Message.assistant(resp.content(), resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    String result = toolRegistry.execute(call, pr);
                    messages.add(Message.tool(call.id(), result));
                    toolCallCount++;
                }
                if (toolCallCount >= 4 && giveTools) {
                    messages.add(Message.user(
                            "已进行 " + toolCallCount + " 次工具调用。请立即输出最终审" +
                            "查结果 JSON，不要继续调用工具。"));
                }
                continue;
            }

            // No tool calls → LLM is done, parse result
            lastReactRounds = round;
            lastToolCallCount = toolCallCount;
            return parseWithRetry(resp.content(), messages, tools);
        }

        // 3. Exceeded maxRounds — force completion
        lastReactRounds = round;
        lastToolCallCount = toolCallCount;
        messages.add(Message.user("已达到最大轮次 " + maxRounds + "。请立即输出审查结果 JSON。"));
        return forceComplete(messages);
    }

    /** Force the LLM to output a final result (no tools). */
    private ReviewResult forceComplete(List<Message> messages) {
        AgentResponse resp = modelProvider.chat(messages, List.of());
        return parseWithRetry(resp.content(), messages, List.of());
    }

    /** Parse the LLM's JSON output, retrying once if format is invalid. */
    private ReviewResult parseWithRetry(String raw, List<Message> messages,
                                         List<Tool> tools) {
        String cleaned = JsonReplyCleaner.extractJsonObject(
                JsonReplyCleaner.stripFences(raw).trim());
        try {
            return parseCleaned(cleaned);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Parse failed ({}), retrying with error feedback",
                    e.getOriginalMessage());
            messages.add(Message.user(
                    "你上一次的响应不是合法的 JSON。解析错误：" + e.getOriginalMessage() +
                    "。请严格按 JSON schema 重新输出。"));
            AgentResponse resp = modelProvider.chat(messages, tools);
            String cleaned2 = JsonReplyCleaner.extractJsonObject(
                    JsonReplyCleaner.stripFences(resp.content()).trim());
            try {
                return parseCleaned(cleaned2);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e2) {
                log.error("Retry also failed ({}), raw response ({} chars)",
                        e2.getOriginalMessage(), raw.length());
                // Try to extract just the summary field via regex as a last resort
                String summary = extractField(raw, "summary");
                if (summary.isEmpty()) summary = extractField(cleaned2, "summary");
                if (summary.isEmpty()) summary = "(Parse error — see logs)";
                return new ReviewResult("", summary, List.of(), List.of(), null);
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

    private ReviewResult parseCleaned(String cleaned)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        com.fasterxml.jackson.databind.JsonNode root = json.readTree(cleaned);
        String summary = root.has("summary") ? root.get("summary").asText("") : "";
        List<RiskItem> risks = parseRisks(root.get("risks"));
        List<Suggestion> suggestions = parseSuggestions(root.get("suggestions"));
        List<String> keyFindings = parseKeyFindings(root.get("keyFindings"));
        return new ReviewResult("", summary, risks, suggestions, keyFindings, null);
    }

    private static List<String> parseKeyFindings(com.fasterxml.jackson.databind.JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            String s = n.asText(null);
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private List<RiskItem> parseRisks(com.fasterxml.jackson.databind.JsonNode arr) {
        List<RiskItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
            RiskLevel level = parseLevel(n.has("level") ? n.get("level").asText("") : "");
            String file = n.has("file") ? n.get("file").asText("") : "";
            int line = n.has("line") ? n.get("line").asInt(0) : 0;
            String message = n.has("message") ? n.get("message").asText("") : "";
            if (!message.isBlank()) out.add(new RiskItem(level, file, line, message));
        }
        return out;
    }

    private List<Suggestion> parseSuggestions(com.fasterxml.jackson.databind.JsonNode arr) {
        List<Suggestion> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
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

    /** Rough char-count estimate for token budget management. */
    private static int estimateChars(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            n += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) n += m.toolCalls().toString().length();
        }
        return n;
    }

    /**
     * Compress early tool results to save context. For tool messages over
     * 2000 chars, truncate and mark the compression.
     */
    private static List<Message> compressToolResults(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if ("tool".equals(m.role()) && m.content() != null
                    && m.content().length() > 2000) {
                String truncated = m.content().substring(0, 1000)
                        + "\n[...compressed " + (m.content().length() - 2000)
                        + " chars]";
                out.add(Message.tool(m.toolCallId(), truncated));
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
