package com.reviewpilot.service.github;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.ai.RepositorySearchService;
import com.reviewpilot.service.ai.ToolCall;
import com.reviewpilot.service.ai.ToolRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GithubCodeSearcherTest {
    @Test
    void failed_search_is_not_cached_as_no_results() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"total_count\":1,\"items\":[{\"path\":\"src/Foo.java\",\"name\":\"Foo.java\"}]}"));
            var client = WebClient.builder().baseUrl(server.url("/").toString()).build();
            var search = new RepositorySearchService(new GithubCodeSearcher(client, new com.reviewpilot.config.GithubProperties("https://api.github.com", "", java.util.List.of(), null, 0)), 25, 256);
            var registry = new ToolRegistry(search, null);
            var call = new ToolCall("c1", "search_repo", Map.of("query", "Foo"));
            var pr = new PrUrl("owner", "repo", 1);
            String failed = registry.execute(call, pr);
            String recovered = registry.execute(call, pr);
            assertTrue(failed.contains("Tool execution failed"));
            assertEquals("src/Foo.java\n", recovered);
            assertEquals(2, server.getRequestCount());
            assertTrue(server.takeRequest().getPath().contains("repo:owner/repo"));
        }
    }

    /**
     * A raw '#' starts a URI fragment, which is never sent to the server: it used
     * to truncate the q value mid-parameter, dropping the repo: scope and searching
     * all of GitHub with this service's privileged token.
     */
    @Test
    void query_cannot_escape_its_parameter_or_drop_the_repo_scope() throws Exception {
        try (var server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"total_count\":0,\"items\":[]}"));
            var client = WebClient.builder().baseUrl(server.url("/").toString()).build();
            var props = new com.reviewpilot.config.GithubProperties(
                    server.url("/").toString(), "t", java.util.List.of(), null, 0);

            new GithubCodeSearcher(client, props)
                    .searchCode(new PrUrl("owner", "repo", 1), "evil#");

            String path = server.takeRequest().getPath();
            assertTrue(path.contains("evil%23"), "'#' must be encoded, got " + path);
            assertTrue(path.contains("repo:owner/repo"), "repo scope must survive, got " + path);
        }
    }
}
