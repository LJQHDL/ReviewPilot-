package com.reviewpilot.service.ai;

import java.util.List;

/**
 * {@link ModelProvider#chat(List, List)} 的单次返回：文本内容、工具调用列表和真实 token 用量。
 */
public record AgentResponse(String content, List<ToolCall> toolCalls,
                             int promptTokens, int completionTokens) {

    /** LLM 本轮是否请求了工具调用（决定 ReAct 循环继续还是收敛）。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
