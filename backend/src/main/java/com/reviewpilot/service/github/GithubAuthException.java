package com.reviewpilot.service.github;

public class GithubAuthException extends RuntimeException {
    private final int status;

    public GithubAuthException(int status, String body, Throwable cause) {
        super("GitHub auth failed (" + status + "): " + body, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
