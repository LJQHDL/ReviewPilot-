package com.reviewpilot.service.github;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Subset of GitHub's "List PR files" response we care about.
 * <p>
 * Endpoint: {@code GET /repos/{owner}/{repo}/pulls/{number}/files}
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
