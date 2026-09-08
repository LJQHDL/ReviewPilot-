package com.reviewpilot.service.critic;

import java.util.List;

/**
 * Critic 质量检查的结果：评审输出中被发现的问题列表，每条带类别标签
 * （[HALLUCINATION]、[MISSING]、[SEVERITY]）以便下游追溯。
 *
 * <p>{@link #needsRevision()} 在 issues 非空时返回 true，使修订决策是确定性的，
 * 而非依赖 LLM 生成、每次调用都会波动的数值评分。
 */
public record CriticResult(List<String> issues) {

    /** 紧凑构造器：null 归一化为空列表并做防御性拷贝。 */
    public CriticResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 是否需要根据发现的问题触发修订。 */
    public boolean needsRevision() {
        return !issues.isEmpty();
    }
}
