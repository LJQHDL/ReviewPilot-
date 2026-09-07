package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.config.GithubProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** GitHub code-search HTTP adapter. */
@Component
public class GithubCodeSearcher {
    private final WebClient github;
    private final GithubProperties props;

    public GithubCodeSearcher(@Qualifier("githubWebClient") WebClient github,
                              GithubProperties props) {
        this.github = github;
        this.props = props;
    }

    /**
     * Search the PR's repository for a keyword. Returns up to 5 matching
     * file paths. Transport failures propagate so they cannot become cached misses.
     *
     * <p>The query is model-authored — and the model is steered by PR content an
     * attacker writes — so it is carried as one encoded parameter value rather than
     * concatenated into the URI: a raw {@code #} used to start a fragment, which
     * silently dropped the {@code repo:} scope and searched all of GitHub with this
     * service's privileged token.
     */
    public String searchCode(PrUrl pr, String query) {
        try {
            String scoped = query + " repo:" + pr.owner() + "/" + pr.repo();
            SearchResult result = github.get()
                    .uri(b -> b.path("/search/code")
                            .queryParam("q", scoped)
                            .queryParam("per_page", 5)
                            .build())
                    .retrieve()
                    .bodyToMono(SearchResult.class)
                    .block(props.timeout());
            if (result == null || result.items() == null || result.items().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (SearchItem item : result.items()) {
                sb.append(item.path()).append("\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            throw new GithubApiException("GitHub code search failed", e);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResult(
            @com.fasterxml.jackson.annotation.JsonProperty("total_count") int totalCount,
            java.util.List<SearchItem> items) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchItem(String path, String name) {}
}
