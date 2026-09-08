package com.reviewpilot.model;

/** 风险严重度枚举（HIGH &gt; MEDIUM &gt; LOW），规则检测与 AI 输出共用，前端渲染为彩色标签。 */
public enum RiskLevel {
    HIGH,
    MEDIUM,
    LOW
}
