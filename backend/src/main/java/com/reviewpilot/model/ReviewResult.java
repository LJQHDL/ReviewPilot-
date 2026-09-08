package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * POST /api/review 的结构化输出，是前端与下游阶段依赖的稳定契约 Schema。
 *
 * @param prUrl       回显入参 URL，便于前端保持上下文
 * @param summary     对 PR 意图的 1-3 条要点总结
 * @param risks       风险条目（顺序不限，渲染端按等级排序）
 * @param suggestions 可执行的改进建议，尽量带文件/行号
 * @param meta        提供方/模型/耗时等诊断信息，便于演示与调试
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewResult(
        String prUrl,
        String summary,
        List<RiskItem> risks,
        List<Suggestion> suggestions,
        List<String> keyFindings,
        Meta meta
) {

    /**
     * 诊断元数据。{@code agentRounds} 记录 LLM 评审尝试次数（1=单次通过，
     * 2=修订后成功解析）；{@code reactRounds}/{@code reactToolCalls} 是 ReAct
     * 循环的内部统计，可用于进度展示和调试；{@code promptTokens}/
     * {@code completionTokens} 是 Provider 返回的真实用量（费用应基于它而非
     * 循环中的字符估算）；{@code filesTruncated} 表示变更文件数超出抓取上限、
     * 本次评审只覆盖了子集。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String provider, String model, int filesAnalyzed, long elapsedMs,
                       int agentRounds, int reactRounds, int reactToolCalls,
                       int promptTokens, int completionTokens, boolean filesTruncated) {
        public Meta(String provider, String model, int filesAnalyzed, long elapsedMs,
                    int agentRounds) {
            this(provider, model, filesAnalyzed, elapsedMs, agentRounds, 0, 0, 0, 0, false);
        }
        public Meta(String provider, String model, int filesAnalyzed, long elapsedMs) {
            this(provider, model, filesAnalyzed, elapsedMs, 1, 0, 0, 0, 0, false);
        }
    }

    // ── 向后兼容构造器（keyFindings 默认为空列表） ──

    public ReviewResult(String prUrl, String summary, List<RiskItem> risks,
                        List<Suggestion> suggestions, Meta meta) {
        this(prUrl, summary, risks, suggestions, List.of(), meta);
    }

    /** 紧凑构造器：将 null 集合归一化为空列表，保证前端可安全遍历。 */
    public ReviewResult {
        if (keyFindings == null) keyFindings = List.of();
        if (risks == null) risks = List.of();
        if (suggestions == null) suggestions = List.of();
    }
}
