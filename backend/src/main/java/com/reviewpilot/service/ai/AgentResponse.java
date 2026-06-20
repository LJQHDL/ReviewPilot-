package com.reviewpilot.service.ai;

import java.util.List;

/**
 * The result of {@link ModelProvider#chat(List, List)}.
 */
public record AgentResponse(String content, List<ToolCall> toolCalls,
                             int promptTokens, int completionTokens) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
