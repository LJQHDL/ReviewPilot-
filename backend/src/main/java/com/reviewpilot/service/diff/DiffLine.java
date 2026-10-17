package com.reviewpilot.service.diff;

/**
 * {@link DiffHunk} 内的一行。
 * <p>
 * {@code oldLine}/{@code newLine} 分别是旧/新文件中 1 起的行号。
 * {@link DiffLineType#CONTEXT} 和 {@link DiffLineType#REMOVED} 行的 {@code oldLine} 非零；
 * {@link DiffLineType#CONTEXT} 和 {@link DiffLineType#ADDED} 行的 {@code newLine} 非零。
 */
public record DiffLine(DiffLineType type, int oldLine, int newLine, String content) {
}
