package com.reviewpilot.service.github;

public class GithubApiException extends RuntimeException {
    public GithubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
