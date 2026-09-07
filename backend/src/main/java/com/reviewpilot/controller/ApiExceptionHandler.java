package com.reviewpilot.controller;

import com.reviewpilot.model.ErrorResponse;
import com.reviewpilot.service.ai.AiProviderException;
import com.reviewpilot.service.github.GithubAuthException;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.reviewpilot.service.ai.AiAuthenticationException;

/** Shared HTTP error mapping for API controllers. */
@RestControllerAdvice(basePackages = "com.reviewpilot.controller")
public class ApiExceptionHandler {
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
        HttpStatus status = e instanceof AiAuthenticationException
                ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(ErrorResponse.of(e.getMessage()));
    }
}
