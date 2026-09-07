package com.reviewpilot.controller;

import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.service.ai.AiProviderException;
import com.reviewpilot.service.github.GithubApiException;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import com.reviewpilot.service.github.RepoNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.reviewpilot.service.ai.AiAuthenticationException;

/** Shared HTTP error mapping for API controllers. */
@RestControllerAdvice(basePackages = "com.reviewpilot.controller")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
        // The upstream body is logged, never returned: it names the token owner and
        // their rate-limit state, and echoing 403-vs-404 detail turns this endpoint
        // into a private-repository existence probe.
        log.warn("GitHub auth rejected: status={} upstream body={}", e.status(), e.upstreamBody());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<ErrorResponse> handleGithubApi(GithubApiException e) {
        // Unmapped until now: any GitHub status other than 401/403/404 fell through
        // to Spring's default {timestamp,status,error,path} body, which the axios
        // interceptor cannot read, so every upstream failure looked like "请求失败".
        log.warn("GitHub upstream failure: {}", e.toString());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("GitHub API request failed"));
    }

    @ExceptionHandler(RepoNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleRepoNotAllowed(RepoNotAllowedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ErrorResponse> handleAi(AiProviderException e) {
        HttpStatus status = e instanceof AiAuthenticationException
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ErrorResponse.of(e.getMessage()));
    }
}
