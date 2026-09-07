package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Available tool definitions, dispatch, and tool-facing error normalization. */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final RepositorySearchService searchService;
    private final FileContentFetcher fileContentFetcher;

    public ToolRegistry(RepositorySearchService searchService, FileContentFetcher fileContentFetcher) {
        this.searchService = searchService;
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
                case "search_repo" -> searchService.search(pr, (String) call.arguments().get("query"));
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
        // The path is model-authored, and the model is steered by PR content an
        // attacker authors. The transport encodes segments, but refusing anything
        // that is not a plain repo-relative path keeps the intent explicit.
        if (path.startsWith("/") || path.contains("..") || path.indexOf('?') >= 0) {
            return "Error: path must be a repository-relative path without '..' or '?'";
        }
        String content = fileContentFetcher.fetchContent(pr, path);
        if (content == null) return "File not found or not accessible: " + path;
        return "File: " + path + "\n```\n" + content + "\n```";
    }

}
