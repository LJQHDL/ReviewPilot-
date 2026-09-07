package com.reviewpilot.service.ai;

import com.reviewpilot.model.ReviewResult;

/**
 * One invocation's review and diagnostics; never stored on the singleton agent.
 * Token counts are the provider's real usage summed over every call this loop made.
 */
public record AgentReview(ReviewResult result, int reactRounds, int toolCallCount,
                          int promptTokens, int completionTokens) {

    public AgentReview(ReviewResult result, int reactRounds, int toolCallCount) {
        this(result, reactRounds, toolCallCount, 0, 0);
    }
}
