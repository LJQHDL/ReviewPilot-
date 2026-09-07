package com.reviewpilot.service.github;

import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * Changed files of a PR, plus whether the list is the whole story. GitHub
 * paginates {@code /pulls/{n}/files}, so a large PR can only ever be partially
 * fetched — callers must surface that instead of reporting a truncated review
 * as a complete one.
 */
public record FetchedFiles(List<FileChange> files, boolean truncated) {

    public static FetchedFiles complete(List<FileChange> files) {
        return new FetchedFiles(files, false);
    }
}
