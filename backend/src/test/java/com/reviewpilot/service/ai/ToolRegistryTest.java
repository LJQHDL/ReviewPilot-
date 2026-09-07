package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import com.reviewpilot.service.github.GithubCodeSearcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private GithubCodeSearcher prFetcher;
    private FileContentFetcher fileContentFetcher;
    private ToolRegistry registry;
    private final PrUrl pr = PrUrl.parse("https://github.com/owner/repo/pull/1");

    @BeforeEach
    void setup() {
        prFetcher = mock(GithubCodeSearcher.class);
        fileContentFetcher = mock(FileContentFetcher.class);
        registry = new ToolRegistry(new RepositorySearchService(prFetcher), fileContentFetcher);
    }

    @Test
    void execute_fetchFileContent_success() {
        when(fileContentFetcher.fetchContent(pr, "src/Foo.java"))
                .thenReturn("public class Foo {}");

        ToolCall call = new ToolCall("c1", "fetch_file_content",
                Map.of("path", "src/Foo.java"));
        String result = registry.execute(call, pr);

        assertTrue(result.contains("public class Foo {}"));
        assertTrue(result.contains("src/Foo.java"));
    }

    @Test
    void execute_fetchFileContent_fileNotFound() {
        when(fileContentFetcher.fetchContent(pr, "missing.txt"))
                .thenReturn(null);

        ToolCall call = new ToolCall("c1", "fetch_file_content",
                Map.of("path", "missing.txt"));
        String result = registry.execute(call, pr);

        assertTrue(result.contains("not found"));
        assertTrue(result.contains("missing.txt"));
    }

    @Test
    void execute_fetchFileContent_missingPathArg() {
        ToolCall call = new ToolCall("c1", "fetch_file_content", Map.of());
        String result = registry.execute(call, pr);

        assertTrue(result.contains("path is required"));
    }

    @Test
    void execute_fetchFileContent_blankPathArg() {
        ToolCall call = new ToolCall("c1", "fetch_file_content",
                Map.of("path", "  "));
        String result = registry.execute(call, pr);

        assertTrue(result.contains("path is required"));
    }

    @Test
    void execute_searchRepo_success() {
        when(prFetcher.searchCode(any(), eq("lock")))
                .thenReturn("Foo.java:10 lock.lock();");

        ToolCall call = new ToolCall("c1", "search_repo",
                Map.of("query", "lock"));
        String result = registry.execute(call, pr);

        assertEquals("Foo.java:10 lock.lock();", result);
        verify(prFetcher).searchCode(pr, "lock");
    }

    @Test
    void execute_searchRepo_noResults() {
        when(prFetcher.searchCode(any(), anyString()))
                .thenReturn("");

        ToolCall call = new ToolCall("c1", "search_repo",
                Map.of("query", "nonexistent"));
        String result = registry.execute(call, pr);

        assertTrue(result.contains("No results found"));
    }

    @Test
    void execute_searchRepo_cacheHit() {
        when(prFetcher.searchCode(any(), eq("lock")))
                .thenReturn("Foo.java:10 lock.lock();");

        ToolCall call = new ToolCall("c1", "search_repo",
                Map.of("query", "lock"));

        String first = registry.execute(call, pr);
        String second = registry.execute(call, pr);

        assertEquals("Foo.java:10 lock.lock();", first);
        assertTrue(second.startsWith("(cached) "));
        verify(prFetcher, times(1)).searchCode(pr, "lock");
    }

    @Test
    void execute_unknownTool_returnsError() {
        ToolCall call = new ToolCall("c1", "no_such_tool", Map.of());
        String result = registry.execute(call, pr);

        assertTrue(result.contains("Unknown tool"));
        assertTrue(result.contains("no_such_tool"));
    }

    @Test
    void execute_toolThrows_returnsError() {
        when(fileContentFetcher.fetchContent(pr, "boom.java"))
                .thenThrow(new RuntimeException("network error"));

        ToolCall call = new ToolCall("c1", "fetch_file_content",
                Map.of("path", "boom.java"));
        String result = registry.execute(call, pr);

        assertTrue(result.contains("Tool execution failed"));
        assertTrue(result.contains("network error"));
    }

    @Test
    void searchRepo_rateLimitExceeded() {
        when(prFetcher.searchCode(any(), anyString()))
                .thenReturn("result");

        // 26 calls with unique queries (cache-bypassed)
        String last = "";
        for (int i = 0; i < 26; i++) {
            ToolCall call = new ToolCall("c" + i, "search_repo",
                    Map.of("query", "q" + i));
            last = registry.execute(call, pr);
        }

        assertTrue(last.contains("rate limited"));
    }

    @Test
    void getDefinitions_returnsBothTools() {
        var defs = registry.getDefinitions();

        assertEquals(2, defs.size());
        assertEquals("fetch_file_content", defs.get(0).name());
        assertEquals("search_repo", defs.get(1).name());
    }
}
