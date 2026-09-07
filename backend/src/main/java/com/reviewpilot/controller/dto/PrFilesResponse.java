package com.reviewpilot.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * View of {@code GET /api/pr/files}.
 *
 * <p>The endpoint used to serialize {@link FileChange} — the internal spine object
 * every pipeline stage consumes, including its parsed hunks and whole patches.
 * That made diff-layer field names part of the public contract, so any rename
 * there was a breaking API change, and a large PR shipped megabytes with no way
 * to ask for less.
 *
 * @param truncated whether the PR had more changed files than the fetcher walks;
 *                  when true, {@code files} is a subset and the review is partial
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrFilesResponse(List<FileEntry> files, boolean truncated) {

    /** @param patch the raw unified diff for this file; only present with {@code ?include=patch} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileEntry(String filename, String status, int additions, int deletions,
                            int hunkCount, String patch) {

        public static FileEntry of(FileChange f, boolean withPatch) {
            return new FileEntry(
                    f.filename(),
                    f.status(),
                    f.additions(),
                    f.deletions(),
                    f.hunks() == null ? 0 : f.hunks().size(),
                    withPatch ? f.patch() : null);
        }
    }
}
