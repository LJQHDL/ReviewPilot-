package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The structured output of {@code POST /api/review}. Stable schema — frontend
 * (PR#7) and downstream stages (RiskDetector in PR#5) depend on this shape.
 *
 * @param prUrl       Echoed back so the UI can keep context.
 * @param summary     1-3 bullet summary of the PR's intent.
 * @param risks       Risk items in any order; renderer sorts by level.
 * @param suggestions Actionable suggestions, file/line where applicable.
 * @param meta        Provider/model/runtime info — useful in the demo + debugging.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResult(
        String prUrl,
        String summary,
        List<RiskItem> risks,
        List<Suggestion> suggestions,
        Meta meta
) {

    /** Diagnostic metadata. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String provider, String model, int filesAnalyzed, long elapsedMs) {}
}
