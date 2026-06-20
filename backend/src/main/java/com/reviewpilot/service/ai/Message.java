package com.reviewpilot.service.ai;

import java.util.List;
import java.util.Map;

/**
 * A single message in a multi-turn LLM conversation. Supports all four
 * OpenAI-compatible roles: system, user, assistant, and tool.
 */
public record Message(String role, String content, String toolCallId,
                      List<ToolCall> toolCalls) {

    public static Message system(String content) {
        return new Message("system", content, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, null, toolCalls);
    }

    public static Message tool(String callId, String content) {
        return new Message("tool", content, callId, null);
    }

    /** Convert to the Map form expected by the OpenAI-compatible JSON body. */
    public Map<String, Object> toApiMap() {
        if ("tool".equals(role)) {
            return Map.of("role", role, "tool_call_id", toolCallId != null ? toolCallId : "",
                    "content", content != null ? content : "");
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // Use null content (not empty string) — OpenAI-compatible APIs
            // reject "" when tool_calls is present.
            java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("role", role);
            if (content != null && !content.isBlank()) {
                map.put("content", content);
            }
            map.put("tool_calls", toolCalls.stream().map(ToolCall::toApiMap).toList());
            return map;
        }
        return Map.of("role", role, "content", content);
    }
}
