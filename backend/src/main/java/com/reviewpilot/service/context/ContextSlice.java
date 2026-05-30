package com.reviewpilot.service.context;

import java.util.List;

/**
 * A contiguous slice of file lines surrounding one or more risk findings,
 * extracted from the diff hunks of a {@link com.reviewpilot.service.diff.FileChange}.
 *
 * <p>{@code lines} are the rendered display lines, each prefixed with its
 * new-file line number, e.g. {@code "  42:     foo.lock();"}. The slice is
 * what {@code PromptBuilder} embeds under a "Context" heading so the model
 * sees the surrounding code without having to re-parse the diff itself.
 */
public record ContextSlice(String file, int startLine, int endLine, List<String> lines) {
}
