package com.reviewpilot.service.critic;

import java.util.List;

/**
 * Result of the Critic Agent's quality inspection. Contains a list of issues
 * found in the review output — each tagged by category ([HALLUCINATION],
 * [MISSING], [SEVERITY]) for downstream traceability.
 *
 * <p>{@link #needsRevision()} returns true when issues is non-empty, making
 * the decision deterministic rather than relying on an LLM-generated numeric
 * score that would vary across calls.
 */
public record CriticResult(List<String> issues) {

    public CriticResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean needsRevision() {
        return !issues.isEmpty();
    }
}
