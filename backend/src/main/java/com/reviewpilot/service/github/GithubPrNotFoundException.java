package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;

/** PR 不存在或仓库为私有（GitHub 对两者都返回 404）；映射为 HTTP 404。 */
public class GithubPrNotFoundException extends RuntimeException {
    public GithubPrNotFoundException(PrUrl pr, Throwable cause) {
        super("PR not found or repository is private: " + pr.owner() + "/" + pr.repo() + "#" + pr.number(), cause);
    }
}
