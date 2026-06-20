package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import com.reviewpilot.service.github.GithubPrFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry of tools available to the ReAct agent. Each tool is a function
 * that takes arguments from the LLM's tool_call and returns a string.
 *
 * <p>Thread-safe: the single @Component instance accepts {@link PrUrl} as a
 * method parameter (not constructor state), avoiding per-request races.
 * Search results are cached per (owner, repo, query) and rate-limited at
 * 25 calls/minute.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final GithubPrFetcher prFetcher;
    private final FileContentFetcher fileContentFetcher;
    private final Map<String, String> searchCache = new ConcurrentHashMap<>();
    private final AtomicInteger searchCount = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();

    public ToolRegistry(GithubPrFetcher prFetcher, FileContentFetcher fileContentFetcher) {
        this.prFetcher = prFetcher;
        this.fileContentFetcher = fileContentFetcher;
    }

    /** Available tool definitions sent to the LLM. */
    public List<Tool> getDefinitions() {
        return List.of(
                new Tool("fetch_file_content",
                        "Fetch the full source code of a file in the PR's repository. "
                                + "Use when the diff context (±3 lines) is not enough to "
                                + "understand variable assignments, outer try-catch logic, "
                                + "or method signatures.",
                        Map.of("type", "object",
                                "properties", Map.of("path", Map.of("type", "string",
                                        "description", "File path relative to repo root, e.g. src/main/java/Foo.java")),
                                "required", List.of("path"))),
                new Tool("search_repo",
                        "Search the PR's repository for a keyword or code pattern. "
                                + "Use to find all callers of a method, all references to "
                                + "a variable, or related code across files.",
                        Map.of("type", "object",
                                "properties", Map.of("query", Map.of("type", "string",
                                        "description", "Search keyword, e.g. method name, class name")),
                                "required", List.of("query")))
        );
    }

    /**
     * Execute a tool call requested by the LLM. Returns formatted text for
     * the LLM to consume — never throws.
     */
    public String execute(ToolCall call, PrUrl pr) {
        try {
            return switch (call.name()) {
                case "fetch_file_content" -> fetchFile(pr, call.arguments());
                case "search_repo" -> searchRepo(pr, call.arguments());
                default -> "Unknown tool: " + call.name();
            };
        } catch (RuntimeException e) {
            log.warn("Tool {} failed: {}", call.name(), e.toString());
            return "Tool execution failed: " + e.getMessage();
        }
    }

    private String fetchFile(PrUrl pr, Map<String, Object> args) {
        String path = (String) args.get("path");
        if (path == null || path.isBlank()) return "Error: path is required";
        String content = fileContentFetcher.fetchContent(pr, path);
        if (content == null) return "File not found or not accessible: " + path;
        return "File: " + path + "\n```\n" + content + "\n```";
    }

    private String searchRepo(PrUrl pr, Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) return "Error: query is required";

        String cacheKey = pr.owner() + "/" + pr.repo() + "/" + query;
        String cached = searchCache.get(cacheKey);
        if (cached != null) return "(cached) " + cached;

        // Rate limit: 25 calls per 60s sliding window
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000) {
            windowStart = now;
            searchCount.set(0);
        }
        if (searchCount.incrementAndGet() > 25) {
            return "Search rate limited. Please use the information you already have to continue the review.";
        }

        String result = prFetcher.searchCode(pr, query);
        if (result == null || result.isBlank()) {
            result = "No results found for: " + query;
        }
        searchCache.put(cacheKey, result);
        return result;
    }
}
