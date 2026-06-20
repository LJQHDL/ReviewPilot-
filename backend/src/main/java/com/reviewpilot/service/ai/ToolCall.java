package com.reviewpilot.service.ai;

import java.util.Map;

/**
 * A tool call requested by the LLM — part of an assistant message's response.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    /** Convert to the OpenAI-compatible tool_call wire format. */
    public Map<String, Object> toApiMap() {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", mapToJson(arguments))
        );
    }

    private static String mapToJson(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : args.entrySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append("\"").append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof String s) sb.append("\"").append(s).append("\"");
            else sb.append(v);
        }
        sb.append("}");
        return sb.toString();
    }
}
