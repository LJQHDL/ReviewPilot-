package com.reviewpilot.service.github;

/** 请求的仓库不在 {@link RepoAllowlist} 白名单内时抛出；映射为 HTTP 403。 */
public class RepoNotAllowedException extends RuntimeException {
    public RepoNotAllowedException(String repo) {
        super("Repository " + repo + " is not allowed by this service");
    }
}
