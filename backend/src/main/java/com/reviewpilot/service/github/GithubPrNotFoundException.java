package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;

public class GithubPrNotFoundException extends RuntimeException {
    public GithubPrNotFoundException(PrUrl pr, Throwable cause) {
        super("PR not found or repository is private: " + pr.owner() + "/" + pr.repo() + "#" + pr.number(), cause);
    }
}
