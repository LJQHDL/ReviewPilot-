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
        List<String> keyFindings,
        Meta meta
) {

    /** Diagnostic metadata. {@code agentRounds} tracks how many LLM review
     *  attempts were made (1 = single pass, 2 = a revision that actually parsed).
     *  {@code reactRounds} and {@code reactToolCalls} are the ReAct agent's
     *  internal stats — useful for progress UX and debugging.
     *  {@code promptTokens}/{@code completionTokens} are the provider's real usage
     *  for this request, which is what a cost figure should be built from rather
     *  than the character estimate used mid-loop.
     *  {@code filesTruncated} means the PR had more changed files than the fetcher
     *  walks, so this review covers a subset only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String provider, String model, int filesAnalyzed, long elapsedMs,
                       int agentRounds, int reactRounds, int reactToolCalls,
                       int promptTokens, int completionTokens, boolean filesTruncated) {
        public Meta(String provider, String model, int filesAnalyzed, long elapsedMs,
                    int agentRounds) {
            this(provider, model, filesAnalyzed, elapsedMs, agentRounds, 0, 0, 0, 0, false);
        }
        public Meta(String provider, String model, int filesAnalyzed, long elapsedMs) {
            this(provider, model, filesAnalyzed, elapsedMs, 1, 0, 0, 0, 0, false);
        }
    }

    // ── Backward-compatible constructors (keyFindings defaults to empty) ──

    public ReviewResult(String prUrl, String summary, List<RiskItem> risks,
                        List<Suggestion> suggestions, Meta meta) {
        this(prUrl, summary, risks, suggestions, List.of(), meta);
    }

    public ReviewResult {
        if (keyFindings == null) keyFindings = List.of();
        if (risks == null) risks = List.of();
        if (suggestions == null) suggestions = List.of();
    }
}
