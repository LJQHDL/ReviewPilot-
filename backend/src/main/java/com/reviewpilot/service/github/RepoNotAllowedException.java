package com.reviewpilot.service.github;

/** Thrown when the requested repository is outside {@link RepoAllowlist}. */
public class RepoNotAllowedException extends RuntimeException {
    public RepoNotAllowedException(String repo) {
        super("Repository " + repo + " is not allowed by this service");
    }
}
