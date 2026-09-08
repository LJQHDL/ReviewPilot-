package com.reviewpilot.service.context;

import java.util.List;

/**
 * 围绕一条或多条风险发现的连续文件行切片，从 {@link com.reviewpilot.service.diff.FileChange} 的 diff hunks 中提取。
 *
 * <p>{@code lines} 是渲染后的展示行，每行前缀其新文件行号，如 {@code "  42:     foo.lock();"}。
 * PromptBuilder 会把切片嵌入 "Context" 标题下，让模型直接看到周边代码而无需自己解析 diff。
 */
public record ContextSlice(String file, int startLine, int endLine, List<String> lines) {
}
