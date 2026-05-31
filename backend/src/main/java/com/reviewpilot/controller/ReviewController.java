package com.reviewpilot.controller;

import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.pipeline.ReviewPipeline;
import com.reviewpilot.service.ai.AiProviderException;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    public record ReviewRequest(String prUrl) {}

    @PostMapping("/review")
    public ReviewResult review(@RequestBody ReviewRequest request) {
        if (request == null || request.prUrl() == null || request.prUrl().isBlank()) {
            throw new IllegalArgumentException("prUrl is required");
        }
        return pipeline.review(request.prUrl());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubPrNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(GithubPrNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubAuthException.class)
    public ResponseEntity<ErrorResponse> handleGithubAuth(GithubAuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ErrorResponse> handleAi(AiProviderException e) {
        // Missing-key case starts with this prefix in DeepSeekProvider.
        boolean isMissingKey = e.getMessage() != null && e.getMessage().startsWith("DeepSeek API key is not set");
        HttpStatus status = isMissingKey ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ErrorResponse.of(e.getMessage()));
    }
}
