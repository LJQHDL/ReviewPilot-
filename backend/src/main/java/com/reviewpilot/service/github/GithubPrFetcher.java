package com.reviewpilot.service.github;

import com.reviewpilot.config.GithubProperties;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.DiffParser;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the changed files of a GitHub Pull Request and turns each one into a
 * {@link FileChange} ready for downstream pipeline stages.
 * <p>
 * Per-file {@code patch} is parsed into hunks via {@link DiffParser}. When the
 * file is binary or otherwise lacks a patch, hunks come back empty.
 * <p>
 * Every call is bounded by {@link GithubProperties#timeout()} — these block a
 * servlet thread, so an unbounded stall here is a leaked worker.
 */
@Service
public class GithubPrFetcher {

    private static final int FILES_PER_PAGE = 100;

    private static final ParameterizedTypeReference<List<GithubPrFile>> PR_FILES =
            new ParameterizedTypeReference<>() {};

    private final WebClient github;
    private final DiffParser diffParser;
    private final GithubProperties props;

    public GithubPrFetcher(WebClient githubWebClient, DiffParser diffParser, GithubProperties props) {
        this.github = githubWebClient;
        this.diffParser = diffParser;
        this.props = props;
    }

    /**
     * Walks the paginated files endpoint up to {@code max-file-pages} and reports
     * whether the PR was fully covered. A single un-paged request used to cap every
     * PR at 100 files while the result claimed to describe the whole thing.
     */
    public FetchedFiles fetchFiles(PrUrl pr) {
        List<FileChange> files = new ArrayList<>();
        boolean complete = false;
        for (int page = 1; page <= props.maxFilePages(); page++) {
            List<GithubPrFile> raw = fetchFilePage(pr, page);
            if (raw == null || raw.isEmpty()) {
                complete = true;
                break;
            }
            for (GithubPrFile f : raw) {
                files.add(toFileChange(f));
            }
            if (raw.size() < FILES_PER_PAGE) {
                complete = true;
                break;
            }
        }
        return new FetchedFiles(List.copyOf(files), !complete);
    }

    private List<GithubPrFile> fetchFilePage(PrUrl pr, int page) {
        String path = GithubApiPaths.pullRequest(pr) + "/files";
        try {
            return github.get()
                    .uri(b -> b.path(path)
                            .queryParam("per_page", FILES_PER_PAGE)
                            .queryParam("page", page)
                            .build())
                    .retrieve()
                    .bodyToMono(PR_FILES)
                    .block(props.timeout());
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404) {
                throw new GithubPrNotFoundException(pr, e);
            }
            if (status.value() == 401 || status.value() == 403) {
                throw new GithubAuthException(status.value(), e.getResponseBodyAsString(), e);
            }
            throw new GithubApiException("GitHub API call failed: " + status, e);
        } catch (RuntimeException e) {
            // Timeout or codec limit: surface as a typed upstream failure so the
            // API answers 502 instead of an opaque, unmapped 500.
            throw new GithubApiException("GitHub API call failed: " + e, e);
        }
    }

    /**
     * Fetch the PR title from the GitHub API. Returns null on failure so the
     * pipeline can degrade gracefully — the title is a hint, not a requirement.
     */
    public String fetchPrTitle(PrUrl pr) {
        try {
            PrMetadata meta = github.get()
                    .uri(GithubApiPaths.pullRequest(pr))
                    .retrieve()
                    .bodyToMono(PrMetadata.class)
                    .block(props.timeout());
            return meta != null ? meta.title() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record PrMetadata(String title) {}

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
