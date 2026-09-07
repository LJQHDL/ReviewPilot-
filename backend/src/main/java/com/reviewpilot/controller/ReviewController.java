package com.reviewpilot.controller;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.pipeline.ReviewPipeline;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Primary entrypoint of ReviewPilot. Frontend (PR#7) hits this endpoint.
 *
 * <pre>
 *   POST /api/review
 *   { "prUrl": "https://github.com/owner/repo/pull/12" }
 *   ->
 *   200 { ReviewResult }
 *   400 { error: "<reason>" }     (bad URL)
 *   404 { error: "..." }          (private or non-existent PR)
 *   401 { error: "..." }          (GitHub auth / rate-limit, or DeepSeek key not set)
 *   502 { error: "..." }          (DeepSeek API failure)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class ReviewController {
    private final ReviewPipeline pipeline;

    public ReviewController(ReviewPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** HTTP body; URL semantics are validated by the application use case. */
    public record ReviewRequest(String prUrl) {}

    @PostMapping("/review")
    public ReviewResult review(@RequestBody ReviewRequest request) {
        if (request == null || request.prUrl() == null || request.prUrl().isBlank()) {
            throw new IllegalArgumentException("prUrl is required");
        }
        return pipeline.review(request.prUrl());
    }

}
