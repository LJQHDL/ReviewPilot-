package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;

/** GitHub code-search HTTP adapter. */
@Component
public class GithubCodeSearcher {
    private final WebClient github;
    public GithubCodeSearcher(@Qualifier("githubWebClient") WebClient github) {
        this.github = github;
    }
    /**
     * Search the PR's repository for a keyword. Returns up to 5 matching
     * file paths. Transport failures propagate so they cannot become cached misses.
     */
    public String searchCode(PrUrl pr, String query) {
        try {
            String uri = "/search/code?q=" + query + "+repo:" + pr.owner() + "/" + pr.repo()
                    + "&per_page=5";
            SearchResult result = github.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(SearchResult.class)
                    .block();
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
