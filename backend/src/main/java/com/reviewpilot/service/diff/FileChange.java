package com.reviewpilot.service.diff;

import java.util.List;

/**
 * A single file's worth of changes in a PR. Mirrors the shape of one entry in
 * GitHub's {@code GET /repos/.../pulls/{n}/files} response, augmented with the
 * parsed hunks of {@link #patch()} when {@link #binary()} is false.
 */
public record FileChange(
        String filename,
        String status,         // "added" | "removed" | "modified" | "renamed"
        int additions,
        int deletions,
        boolean binary,
        String patch,          // raw unified diff (null for binary or no-change)
        List<DiffHunk> hunks   // empty if binary or patch is null
) {
}
