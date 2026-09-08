package com.reviewpilot.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** OpenAI 兼容协议的线格式适配器：把 Agent 的消息/工具记录转成请求体 Map，隔离协议细节。 */
public final class OpenAiMessageMapper {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OpenAiMessageMapper() {}

    /** 将内部 Message 转为 OpenAI 兼容 JSON 请求体期望的 Map 形式。 */
    public static Map<String, Object> message(Message message) {
        String role = message.role();
        String content = message.content();
        String toolCallId = message.toolCallId();
        List<ToolCall> toolCalls = message.toolCalls();
        if ("tool".equals(role)) {
            // tool 角色必须回填 tool_call_id，让模型把结果与调用对应起来
            return Map.of("role", role, "tool_call_id", toolCallId != null ? toolCallId : "",
                    "content", content != null ? content : "");
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // content 用 null 而非空串——OpenAI 兼容 API 在携带 tool_calls 时拒绝 ""
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

    /** 将内部 Tool 定义转为 {type:function, function:{...}} 结构。 */
    public static Map<String, Object> tool(Tool tool) {
        return Map.of("type", "function", "function", Map.of(
                "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()));
    }

    /** 将工具调用序列化为协议格式，arguments 需 JSON 字符串化。 */
    private static Map<String, Object> toolCall(ToolCall call) {
        try {
            return Map.of("id", call.id(), "type", "function", "function", Map.of(
                    "name", call.name(), "arguments", JSON.writeValueAsString(call.arguments())));
        } catch (JsonProcessingException e) {
            throw new AiProviderException("Cannot encode tool call arguments", e);
        }
    }
}
