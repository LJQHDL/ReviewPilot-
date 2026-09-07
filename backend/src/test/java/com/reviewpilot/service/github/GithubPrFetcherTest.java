package com.reviewpilot.service.github;

import com.reviewpilot.config.GithubProperties;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.DiffParser;
import com.reviewpilot.service.diff.FileChange;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubPrFetcherTest {

    private MockWebServer server;
    private GithubPrFetcher fetcher;
    private WebClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        base = server.url("/").toString();
        client = WebClient.builder()
                .baseUrl(base)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
        fetcher = new GithubPrFetcher(client, new DiffParser(), props(3));
    }

    private GithubProperties props(int maxFilePages) {
        return new GithubProperties(base, "test-token", java.util.List.of(),
                java.time.Duration.ofSeconds(30), maxFilePages);
    }

    private GithubPrFetcher fetcherWithPageCap(int maxFilePages) {
        return new GithubPrFetcher(client, new DiffParser(), props(maxFilePages));
    }

    private void okJson(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(body));
    }

    /** A /files page carrying exactly count entries. */
    private static String filesJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"filename\":\"F").append(i)
              .append(".java\",\"status\":\"modified\",\"additions\":1,\"deletions\":0}");
        }
        return sb.append(']').toString();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void calls_correct_endpoint_with_required_headers_and_parses_files() throws Exception {
        String body = """
                [
                  {
                    "filename": "src/Foo.java",
                    "status": "modified",
                    "additions": 2,
                    "deletions": 1,
                    "changes": 3,
                    "blob_url": "https://github.com/o/r/blob/sha/src/Foo.java",
                    "raw_url":  "https://github.com/o/r/raw/sha/src/Foo.java",
                    "contents_url": "https://api.github.com/repos/o/r/contents/src/Foo.java?ref=sha",
                    "patch": "@@ -1,2 +1,3 @@\\n a\\n-b\\n+B\\n+C",
                    "sha": "abcd1234"
                  },
                  {
                    "filename": "img/logo.png",
                    "status": "added",
                    "additions": 0,
                    "deletions": 0,
                    "changes": 0,
                    "patch": null,
                    "sha": "deadbeef"
                  }
                ]
                """;

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(body));

        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/42");
        List<FileChange> files = fetcher.fetchFiles(pr).files();

        assertEquals(2, files.size());

        FileChange foo = files.get(0);
        assertEquals("src/Foo.java", foo.filename());
        assertEquals("modified", foo.status());
        assertEquals(2, foo.additions());
        assertEquals(1, foo.deletions());
        assertEquals(false, foo.binary());
        // Patch was routed through DiffParser.
        assertEquals(1, foo.hunks().size());
        assertEquals(DiffLineType.REMOVED, foo.hunks().get(0).lines().get(1).type());

        FileChange img = files.get(1);
        assertEquals("img/logo.png", img.filename());
        assertEquals(true, img.binary());
        assertTrue(img.hunks().isEmpty());

        RecordedRequest req = server.takeRequest();
        assertEquals("/repos/owner/repo/pulls/42/files?per_page=100&page=1", req.getPath());
        assertEquals("Bearer test-token", req.getHeader("Authorization"));
        assertNotNull(req.getHeader("Accept"));
        assertTrue(req.getHeader("Accept").contains("application/vnd.github+json"));
    }

    @Test
    void maps_404_to_pr_not_found() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"message\":\"Not Found\"}"));
        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/999");
        assertThrows(GithubPrNotFoundException.class, () -> fetcher.fetchFiles(pr));
    }

    @Test
    void maps_401_to_auth_exception() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\":\"Bad credentials\"}"));
        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/1");
        assertThrows(GithubAuthException.class, () -> fetcher.fetchFiles(pr));
    }

    @Test
    void maps_403_to_auth_exception() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("{\"message\":\"rate limit\"}"));
        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/1");
        assertThrows(GithubAuthException.class, () -> fetcher.fetchFiles(pr));
    }

    @Test
    void maps_other_5xx_to_generic_api_exception() {
        server.enqueue(new MockResponse().setResponseCode(500));
        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/1");
        assertThrows(GithubApiException.class, () -> fetcher.fetchFiles(pr));
    }

    @Test
    void empty_array_yields_empty_list() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("[]"));
        PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/1");
        assertTrue(fetcher.fetchFiles(pr).files().isEmpty());

        // Confirm we still hit the correct path even when the PR has no files.
        RecordedRequest req = server.takeRequest();
        assertEquals("/repos/owner/repo/pulls/1/files?per_page=100&page=1", req.getPath());
    }

    @Test
    void walks_pages_until_a_short_page_arrives() throws Exception {
        okJson(filesJson(100));
        okJson(filesJson(7));

        var r = fetcher.fetchFiles(PrUrl.parse("https://github.com/owner/repo/pull/42"));

        assertEquals(107, r.files().size());
        assertFalse(r.truncated(), "a short page means the PR was fully covered");
        assertEquals("/repos/owner/repo/pulls/42/files?per_page=100&page=1", server.takeRequest().getPath());
        assertEquals("/repos/owner/repo/pulls/42/files?per_page=100&page=2", server.takeRequest().getPath());
    }

    /**
     * A single un-paged request capped every PR at 100 files while the result
     * claimed to describe the whole PR. Hitting the cap must be visible.
     */
    @Test
    void reports_truncated_instead_of_passing_off_a_partial_fetch_as_complete() throws Exception {
        okJson(filesJson(100));   // a full page, and the cap is one page

        var r = fetcherWithPageCap(1).fetchFiles(PrUrl.parse("https://github.com/owner/repo/pull/42"));

        assertEquals(100, r.files().size());
        assertTrue(r.truncated(), "reaching the page cap must surface to the caller");
        server.takeRequest();
        assertEquals(1, server.getRequestCount(), "must not keep fetching past the cap");
    }
}