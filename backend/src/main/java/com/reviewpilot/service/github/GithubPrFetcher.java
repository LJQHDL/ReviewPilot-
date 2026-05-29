package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.DiffParser;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;

/**
 * Fetches the changed files of a GitHub Pull Request and turns each one into a
 * {@link FileChange} ready for downstream pipeline stages.
 * <p>
 * Per-file {@code patch} is parsed into hunks via {@link DiffParser}. When the
 * file is binary or otherwise lacks a patch, hunks come back empty.
 */
@Service
public class GithubPrFetcher {

    private static final ParameterizedTypeReference<List<GithubPrFile>> PR_FILES =
            new ParameterizedTypeReference<>() {};

    private final WebClient github;
    private final DiffParser diffParser;

    public GithubPrFetcher(WebClient githubWebClient, DiffParser diffParser) {
        this.github = githubWebClient;
        this.diffParser = diffParser;
    }

    public List<FileChange> fetchFiles(PrUrl pr) {
        List<GithubPrFile> raw;
        try {
            raw = github.get()
                    .uri(pr.apiPath() + "/files?per_page=100")
                    .retrieve()
                    .bodyToMono(PR_FILES)
                    .block();
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw new GithubPrNotFoundException(pr, e);
            }
            if (status.value() == 401 || status.value() == 403) {
                throw new GithubAuthException(status.value(), e.getResponseBodyAsString(), e);
            }
            throw new GithubApiException("GitHub API call failed: " + status, e);
        }
        if (raw == null) {
            return List.of();
        }
        return raw.stream().map(this::toFileChange).toList();
    }

    private FileChange toFileChange(GithubPrFile f) {
        boolean binary = f.patch() == null && !"removed".equals(f.status());
        return new FileChange(
                f.filename(),
                f.status(),
                f.additions(),
                f.deletions(),
                binary,
                f.patch(),
                binary ? List.of() : diffParser.parse(f.patch())
        );
    }
}
