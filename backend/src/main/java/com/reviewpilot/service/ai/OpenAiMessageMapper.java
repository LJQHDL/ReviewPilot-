package com.reviewpilot.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Wire-format adapter. Agent message records do not know the provider protocol. */
public final class OpenAiMessageMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OpenAiMessageMapper() {}

    /** Convert to the Map form expected by the OpenAI-compatible JSON body. */
    public static Map<String, Object> message(Message message) {
        String role = message.role();
        String content = message.content();
        String toolCallId = message.toolCallId();
        List<ToolCall> toolCalls = message.toolCalls();
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
            map.put("tool_calls", toolCalls.stream().map(OpenAiMessageMapper::toolCall).toList());
            return map;
        }
        return Map.of("role", role, "content", content);
    }

    public static Map<String, Object> tool(Tool tool) {
        return Map.of("type", "function", "function", Map.of(
                "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()));
    }

    private static Map<String, Object> toolCall(ToolCall call) {
        try {
            return Map.of("id", call.id(), "type", "function", "function", Map.of(
                    "name", call.name(), "arguments", JSON.writeValueAsString(call.arguments())));
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Cannot encode tool call arguments", e);
        }
    }
}
