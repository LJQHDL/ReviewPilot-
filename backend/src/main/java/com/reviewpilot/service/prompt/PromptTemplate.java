package com.reviewpilot.service.prompt;

import com.reviewpilot.service.classifier.FileType;

/**
 * Per-{@link FileType} guidance text that {@code PromptBuilder} stitches into
 * the user prompt above each group of files. The point isn't a different JSON
 * schema — that stays uniform — but to nudge the AI toward the review
 * concerns that matter most for that kind of file. A controller review and a
 * SQL review should not read the same.
 *
 * <p>Each entry is a few short bullets. Bullets are deliberately concrete
 * ("validate input parameters", "watch for N+1 queries") rather than vague
 * ("write good code"), since the model treats specifics as actionable
 * constraints and ignores platitudes.
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

    public static PromptTemplate forType(FileType type) {
        for (PromptTemplate t : values()) {
            if (t.type == type) return t;
        }
        return OTHER;
    }
}
