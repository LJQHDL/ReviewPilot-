package com.reviewpilot.service.ai;

import java.util.List;

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

}
