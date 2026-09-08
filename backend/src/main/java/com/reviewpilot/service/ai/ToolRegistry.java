package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.service.github.FileContentFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 面向 LLM 的工具注册表：提供工具定义、按名分发调用，并把一切失败归一化为文本结果。 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final RepositorySearchService searchService;
    private final FileContentFetcher fileContentFetcher;

    public ToolRegistry(RepositorySearchService searchService, FileContentFetcher fileContentFetcher) {
        this.searchService = searchService;
        this.fileContentFetcher = fileContentFetcher;
    }

    /** 随每次 chat() 发给 LLM 的工具定义列表（fetch_file_content + search_repo）。 */
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
     * 执行 LLM 请求的工具调用，返回供 LLM 消费的格式化文本——绝不抛异常，
     * 失败也以文本形式回传给模型让它自行调整。
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

    /** 抓取仓库内单个文件的完整内容。 */
    private String fetchFile(PrUrl pr, Map<String, Object> args) {
        String path = (String) args.get("path");
        if (path == null || path.isBlank()) return "Error: path is required";
        // 该路径由模型生成，而模型可能被攻击者编写的 PR 内容诱导。传输层虽会做
        // 分段编码，但仍显式拒绝一切非"仓库相对纯路径"的输入，让意图保持明确。
        if (path.startsWith("/") || path.contains("..") || path.indexOf('?') >= 0) {
            return "Error: path must be a repository-relative path without '..' or '?'";
        }
        String content = fileContentFetcher.fetchContent(pr, path);
        if (content == null) return "File not found or not accessible: " + path;
        return "File: " + path + "\n```\n" + content + "\n```";
    }

}
