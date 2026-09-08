package com.reviewpilot.service.github;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub "List PR files" 响应中本服务关心的字段子集。
 * <p>
 * 端点：{@code GET /repos/{owner}/{repo}/pulls/{number}/files}
 */
public record GithubPrFile(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        @JsonProperty("blob_url") String blobUrl,
        @JsonProperty("raw_url") String rawUrl,
        @JsonProperty("contents_url") String contentsUrl,
        String patch,
        String sha
) {
}
