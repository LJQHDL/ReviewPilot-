package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条可执行的改进建议：文件 + 行号 + 描述；无法定位到行时 {@code line}=0。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Suggestion(String file, int line, String message) {
}
