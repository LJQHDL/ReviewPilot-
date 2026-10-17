package com.reviewpilot.service.prompt;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import org.springframework.stereotype.Component;
import java.util.List;

/** 质量检查员（Critic）与修订环节的 Prompt 渲染器；本身不调用模型。 */
@Component
public class CriticPromptBuilder {
    /** Critic 的 system prompt：定义 HALLUCINATION / MISSING / SEVERITY / DUPLICATE / CONSISTENCY 五类检查项与 JSON 输出契约。 */
    public String systemPrompt() {
        return """
                You are a strict code review quality inspector. Your job is to check an
                AI-generated code review for quality issues.

                You will receive:
                1. The code under review (diff plus context) — the only evidence
                2. Pre-detected rule risks (from deterministic static analysis)
                3. The AI-generated review result (summary + risks + suggestions)

                Check for:
                - HALLUCINATION: An AI risk makes a claim the code under review does
                  not support. Read the cited lines: a guard there (null check,
                  Optional, try/finally) that disproves the risk means you flag it.
                  Do NOT judge this category against the rule-risk list — those
                  heuristics are not the evidence and their silence proves nothing.
                - MISSING: A rule-detected risk was not mentioned in the AI output.
                  The rule layer is authoritative — every rule hit should appear.
                - SEVERITY: An AI risk has the wrong severity level. A risk that
                  describes a behavior change (exception swallowed, contract broken,
                  concurrency invariant weakened) must be HIGH, not MEDIUM or LOW.
                - DUPLICATE: Multiple AI risks describe the same underlying issue at the
                  same file:line location with only wording differences. Flag these for
                  consolidation. Format: "[DUPLICATE] risks #1,#3,#4 all describe
                  the same catch (Throwable) pattern at Foo.java:125 — consolidate
                  into one risk with multi-faceted message."
                - CONSISTENCY: Two or more risks at the same code location make claims
                  that conflict. Look for: (a) one says "no log" but another says "is
                  logged" — cannot both be true; (b) one says "silently swallowed" but
                  another describes a log statement; (c) one says severity is HIGH and
                  another describes the same pattern as MEDIUM with contradicting
                  reasoning. Even subtle conflicts count: "no log or rethrow nearby"
                  vs "only a warn log" — if one says NO log and the other says ONLY a
                  log, flag it. Format: "[CONSISTENCY] risk #2 says no log, risk #4
                  says warn log — which is correct?"

                Output a SINGLE JSON object:
                {
                  "issues": [
                    "[HALLUCINATION] risk #1 claims NPE risk at line 42 but context shows null guard at line 40",
                    "[MISSING] rule detected bare catch at line 15, not mentioned in AI output",
                    "[SEVERITY] risk #3 describes exception swallowing that changes semantics — should be HIGH",
                    "[DUPLICATE] risks #1,#3,#4 all describe the same catch at X.java:125 — consolidate"
                  ]
                }

                If the review is good and you find zero issues, return: {"issues":[]}

                Rules:
                - Do NOT output a numeric score. Only output the issues array.
                - Be specific — reference exact risk indices (risk #N) or line numbers.
                - Only flag real problems. Do not invent issues.
                - For MISSING findings, the rule risk must have a non-zero line number
                  and its message must contain a substantive finding.
                """;
    }

    /**
     * 拼装 Critic 的 user prompt：证据代码 + 规则风险 + AI 评审三块对照材料。
     * @param codeUnderReview 评审者当初看到的材料。缺了它，HALLUCINATION 判定就没有
     *        事实基准：剩下唯一的证据只有"九条规则启发式没报这个"，而这什么都证明不了。
     */
    public String build(List<RiskItem> ruleRisks, ReviewResult review, String codeUnderReview) {
        int evidenceLen = codeUnderReview == null ? 0 : codeUnderReview.length();
        StringBuilder sb = new StringBuilder(4096 + evidenceLen);

        sb.append("Code under review (your only evidence):\n");
        sb.append(evidenceLen == 0 ? "  (not available)\n" : codeUnderReview).append('\n');

        sb.append("Rule-detected risks (authoritative):\n");
        if (ruleRisks == null || ruleRisks.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (int i = 0; i < ruleRisks.size(); i++) {
                RiskItem r = ruleRisks.get(i);
                sb.append("  [rule #").append(i + 1).append("] ")
                        .append(r.level()).append(" | ")
                        .append(r.file()).append(':').append(r.line())
                        .append(" | ").append(r.message()).append('\n');
            }
        }

        sb.append("\nAI-generated risks:\n");
        if (review.risks() == null || review.risks().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (int i = 0; i < review.risks().size(); i++) {
                RiskItem r = review.risks().get(i);
                sb.append("  [risk #").append(i + 1).append("] ")
                        .append(r.level()).append(" | ")
                        .append(r.file()).append(':').append(r.line())
                        .append(" | ").append(r.message()).append('\n');
            }
        }

        sb.append("\nAI-generated suggestions:\n");
        if (review.suggestions() == null || review.suggestions().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (int i = 0; i < review.suggestions().size(); i++) {
                var s = review.suggestions().get(i);
                sb.append("  [suggestion #").append(i + 1).append("] ")
                        .append(s.file()).append(':').append(s.line())
                        .append(" | ").append(s.message()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 生成修订版 system prompt：原 prompt + Critic 发现的问题清单 + "输出修正后的合法 JSON"指令。 */
    public String revisionSystemPrompt(String systemPrompt, List<String> issues) {
        StringBuilder sb = new StringBuilder(systemPrompt.length() + 512);
        sb.append(systemPrompt)
          .append("\n\nYOUR PREVIOUS REVIEW HAD THESE ISSUES (fix them in this response):\n");
        for (String issue : issues) {
            sb.append("- ").append(issue).append('\n');
        }
        sb.append("\nRespond with a corrected review as strictly valid JSON.");

        return sb.toString();
    }
}
