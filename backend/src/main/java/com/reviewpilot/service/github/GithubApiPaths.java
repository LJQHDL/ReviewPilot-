package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;

/** GitHub 路由拼接工具：把 REST 路径映射集中于此，不侵入 PrUrl 值对象。 */
public final class GithubApiPaths {
    private GithubApiPaths() {}
    /** PR 详情端点路径：/repos/{owner}/{repo}/pulls/{number}。 */
    public static String pullRequest(PrUrl pr) {
        return "/repos/" + pr.owner() + "/" + pr.repo() + "/pulls/" + pr.number();
    }
}
