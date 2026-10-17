package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 评审发现的一条风险：等级 + 文件 + 行号 + 描述；{@code line} 为新文件行号，不定位到行时为 0。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskItem(RiskLevel level, String file, int line, String message) {
}
