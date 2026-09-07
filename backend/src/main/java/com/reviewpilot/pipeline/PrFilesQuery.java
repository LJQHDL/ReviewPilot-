package com.reviewpilot.pipeline;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FetchedFiles;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.RepoAllowlist;
import org.springframework.stereotype.Service;

/** Application query for the diff-only use case. */
@Service
public class PrFilesQuery {
    private final GithubPrFetcher fetcher;
    private final RepoAllowlist allowlist;

    public PrFilesQuery(GithubPrFetcher fetcher, RepoAllowlist allowlist) {
        this.fetcher = fetcher;
        this.allowlist = allowlist;
    }

    public FetchedFiles files(String rawUrl) {
        PrUrl pr = PrUrl.parse(rawUrl);
        allowlist.requireAllowed(pr);
        return fetcher.fetchFiles(pr);
    }
}
