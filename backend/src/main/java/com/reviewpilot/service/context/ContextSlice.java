package com.reviewpilot.service.context;

import java.util.List;

/**
 * 围绕一条或多条风险发现的连续文件行切片，从 {@link com.reviewpilot.service.diff.FileChange} 的 diff hunks 中提取。
 *
 * <p>{@code lines} 是渲染后的展示行，每行前缀其新文件行号，如 {@code "  42:     foo.lock();"}。
 * PromptBuilder 会把切片嵌入 "Context" 标题下，让模型直接看到周边代码而无需自己解析 diff。
 */
/** 代码上下文片段数据类，表示文件中需要提供给审查器的一段代码。 */
public record ContextSlice(String file, int startLine, int endLine, List<String> lines) {
    /** 固定上下文内容，避免提示词随调用方的列表修改而变化。 */
    public ContextSlice {
        lines = List.copyOf(lines);
    }
}
