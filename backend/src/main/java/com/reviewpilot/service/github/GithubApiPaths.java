package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;

/** GitHub-specific route mapping, kept outside the PR value object. */
public final class GithubApiPaths {
    private GithubApiPaths() {}
    public static String pullRequest(PrUrl pr) {
        return "/repos/" + pr.owner() + "/" + pr.repo() + "/pulls/" + pr.number();
    }
}
