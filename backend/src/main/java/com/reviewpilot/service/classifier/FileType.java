package com.reviewpilot.service.classifier;

/**
 * 变更文件的粗粒度功能分类，下游用 {@code RiskDetector} 挑选适用规则、用 {@code PromptBuilder} 挑选 Prompt 模板。
 * <p>
 * 桶数刻意保持精简——六个分类足以让 Prompt 产生有意义的差异，同时不逼迫分类器
 * 在边界情形上精确；无法自信归类的文件落入 {@link #OTHER}，使用通用 Prompt。
 *
 * <ul>
 *   <li>{@link #CONTROLLER} — HTTP 入口（Spring MVC 控制器、REST 端点），评审关注入参校验、状态码、鉴权。</li>
 *   <li>{@link #SERVICE} — 业务逻辑 / Spring service bean，评审关注事务、并发、错误处理。</li>
 *   <li>{@link #CONFIG} — 应用配置（yml/properties、Spring {@code @Configuration} 类），评审关注密钥泄漏、环境相关默认值。</li>
 *   <li>{@link #SQL} — 建表或查询文件，评审关注索引影响、迁移安全、注入。</li>
 *   <li>{@link #TEST} — 测试源码，评审关注覆盖率与脆弱性，而非生产正确性。</li>
 *   <li>{@link #OTHER} — 其余（前端、文档、构建文件、未分类），使用通用评审 Prompt。</li>
 * </ul>
 */
public enum FileType {
    CONTROLLER,
    SERVICE,
    CONFIG,
    SQL,
    TEST,
    OTHER
}
