package com.reviewpilot.service.prompt;

import com.reviewpilot.service.classifier.FileType;

/**
 * 按 {@link FileType} 划分的评审关注点文案，由 {@code PromptBuilder} 拼接在每组文件之前。
 * 目的不是换 JSON Schema（Schema 保持统一），而是把 AI 推向该类文件最要紧的评审维度——
 * Controller 的评审和 SQL 的评审不应该长得一样。
 *
 * <p>每项只有几条简短要点。要点刻意写得具体（"校验入参""警惕 N+1 查询"）
 * 而非空泛（"写好代码"），因为模型会把具体项当作可执行约束，而忽略正确的废话。
 */
public enum PromptTemplate {

    CONTROLLER(FileType.CONTROLLER, """
            These files are HTTP-facing controllers. Pay attention to:
            - Input validation: are request fields validated before use?
            - Status codes & error responses: 4xx vs 5xx, leaking exception text
            - Authentication / authorization on sensitive routes
            - Idempotency for non-GET endpoints
            """),

    SERVICE(FileType.SERVICE, """
            These files are business-logic services. Pay attention to:
            - Transaction boundaries and self-invocation pitfalls
            - Concurrency: shared state, locks, atomic operations
            - Error handling: exceptions swallowed vs propagated meaningfully
            - N+1 queries or chatty external calls
            """),

    CONFIG(FileType.CONFIG, """
            These files are configuration. Pay attention to:
            - Hard-coded secrets or environment-specific values that should be externalized
            - Defaults that are unsafe in production (debug=true, permissive CORS, etc.)
            - Cross-environment drift between application*.yml variants
            """),

    SQL(FileType.SQL, """
            These files are SQL / migrations. Pay attention to:
            - Index impact on large tables; missing or redundant indexes
            - Migration safety: locks held, online vs offline DDL
            - Backward compatibility with the previous deployed schema
            - Injection-prone string construction in surrounding code
            """),

    TEST(FileType.TEST, """
            These files are tests. Focus on test quality, not production correctness:
            - Coverage of edge cases and failure paths
            - Brittleness: hard-coded times, network dependencies, ordering assumptions
            - Mocks that diverge from real-system behavior in load-bearing ways
            """),

    OTHER(FileType.OTHER, """
            These files don't match a specific role. Provide general code-review feedback:
            - Correctness, readability, naming, error handling
            - Anything that would make the change harder to maintain
            """);

    private final FileType type;
    private final String guidance;

    PromptTemplate(FileType type, String guidance) {
        this.type = type;
        this.guidance = guidance;
    }

    public FileType type() {
        return type;
    }

    public String guidance() {
        return guidance;
    }

    /** 按 FileType 查找对应模板，找不到时回退 OTHER。 */
    public static PromptTemplate forType(FileType type) {
        for (PromptTemplate t : values()) {
            if (t.type == type) return t;
        }
        return OTHER;
    }
}
