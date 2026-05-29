package com.reviewpilot.service.diff;

/**
 * One line within a {@link DiffHunk}.
 * <p>
 * {@code oldLine}/{@code newLine} are 1-based line numbers in the old/new file
 * respectively. {@code oldLine} is non-zero for {@link DiffLineType#CONTEXT}
 * and {@link DiffLineType#REMOVED}; {@code newLine} is non-zero for
 * {@link DiffLineType#CONTEXT} and {@link DiffLineType#ADDED}.
 */
public record DiffLine(DiffLineType type, int oldLine, int newLine, String content) {
}
