package com.reviewpilot.service.ai;

import com.reviewpilot.model.ReviewResult;

/**
 * 单次 Agent 评审调用的结果与诊断统计；随取随用，绝不存储在单例 Agent 的实例字段上。
 * token 数为本轮循环中所有模型调用返回的真实用量之和。
 */
public record AgentReview(ReviewResult result, int reactRounds, int toolCallCount,
                          int promptTokens, int completionTokens) {

    public AgentReview(ReviewResult result, int reactRounds, int toolCallCount) {
        this(result, reactRounds, toolCallCount, 0, 0);
    }
}
