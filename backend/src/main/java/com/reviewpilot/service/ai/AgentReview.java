package com.reviewpilot.service.ai;

import com.reviewpilot.model.ReviewResult;

/** One invocation's review and diagnostics; never stored on the singleton agent. */
public record AgentReview(ReviewResult result, int reactRounds, int toolCallCount) {}
