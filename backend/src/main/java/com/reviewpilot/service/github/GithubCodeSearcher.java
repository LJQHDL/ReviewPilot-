package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.config.GithubProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** GitHub 代码搜索的 HTTP 适配器（限流与缓存策略由 RepositorySearchService 负责）。 */
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
     * 在 PR 所属仓库内搜索关键字，返回至多 5 个命中的文件路径。
     * 传输层故障会向上抛出，防止它们被上层缓存成"查无结果"。
     *
     * <p>查询词由模型生成——而模型可能被攻击者撰写的 PR 内容诱导——因此它必须作为
     * 单个已编码的查询参数传递而非拼进 URI：裸 {@code #} 曾开启 fragment，
     * 使 {@code repo:} 限定被静默丢弃，用本服务的高权限令牌搜索了整个 GitHub。
     */
    public String searchCode(PrUrl pr, String query) {
        try {
            // repo: 限定把搜索钉死在 PR 所属仓库内
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
