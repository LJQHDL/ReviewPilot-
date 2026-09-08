package com.reviewpilot.service.ai;

import java.util.List;

/**
 * 多轮 LLM 会话中的一条消息，兼容 OpenAI 的四种角色：system / user / assistant / tool。
 */
public record Message(String role, String content, String toolCallId,
                      List<ToolCall> toolCalls) {

    // 以下静态工厂按角色构造消息：tool 消息需回填 toolCallId 以关联对应的工具调用
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
