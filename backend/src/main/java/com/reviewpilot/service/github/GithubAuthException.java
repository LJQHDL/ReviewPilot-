package com.reviewpilot.service.github;

public class GithubAuthException extends RuntimeException {
    private final int status;
    private final String upstreamBody;

    public GithubAuthException(int status, String body, Throwable cause) {
        // Deliberately does not include the body: the message reaches the client,
        // and the body names the token owner and their rate-limit state.
        super("GitHub auth failed (" + status + ")", cause);
        this.status = status;
        this.upstreamBody = body;
    }

    public int status() {
        return status;
    }

    public String upstreamBody() {
        return upstreamBody;
    }
}
