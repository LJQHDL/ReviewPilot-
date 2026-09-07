package com.reviewpilot.pipeline;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubPrFetcher;
import org.springframework.stereotype.Service;
import java.util.List;

/** Application query for the diff-only use case. */
@Service
public class PrFilesQuery {
    private final GithubPrFetcher fetcher;
    public PrFilesQuery(GithubPrFetcher fetcher) { this.fetcher = fetcher; }
    public List<FileChange> files(String rawUrl) {
        return fetcher.fetchFiles(PrUrl.parse(rawUrl));
    }
}
