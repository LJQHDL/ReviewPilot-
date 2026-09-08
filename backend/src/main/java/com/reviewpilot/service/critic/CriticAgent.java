package com.reviewpilot.service.critic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.JsonReplyCleaner;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** 执行一次评审质量检查（Critic），回复 JSON 非法时带错误反馈重试一次。 */
@Component
public class CriticAgent {
    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);
    private final ModelProvider modelProvider;
    private final CriticPromptBuilder prompts;
    private final ObjectMapper json;

    public CriticAgent(ModelProvider modelProvider, CriticPromptBuilder prompts, ObjectMapper json) {
        this.modelProvider = modelProvider;
        this.prompts = prompts;
        this.json = json;
    }

    /** 检查一份评审结果：codeUnderReview 是被评审的证据文本，供 Critic 比对防幻觉。 */
    public CriticResult inspect(ReviewResult review, List<RiskItem> ruleRisks, String codeUnderReview) {
        return runCritic(prompts.systemPrompt(), prompts.build(ruleRisks, review, codeUnderReview));
    }

    /** 运行 Critic，JSON 回复格式错误时最多重试一次。 */
    private CriticResult runCritic(String systemPrompt, String userPrompt) {
        String raw = modelProvider.complete(systemPrompt, userPrompt);
        try {
            return parseCriticReply(raw);
        } catch (JsonProcessingException e) {
            // 第一次解析失败：把解析器错误附加进 Prompt 让模型重写
            log.warn("Critic parse failed ({}), retrying with error feedback",
                    e.getOriginalMessage());
            String retryUser = userPrompt
                    + "\n\nYour previous response was not valid JSON. Parser error: "
                    + e.getOriginalMessage() + ". Respond with strictly valid JSON.";
            String raw2 = modelProvider.complete(systemPrompt, retryUser);
            try {
                return parseCriticReply(raw2);
            } catch (JsonProcessingException e2) {
                // 重试仍失败：记 ERROR、返回无问题结果——评审原样放行，
                // 本次请求的质量闭环已失效（宁可漏检不可伪造检查结果）
                log.error("Critic retry also failed ({}); review passes through "
                        + "UNCORRECTED — quality loop is broken for this request",
                        e2.getOriginalMessage());
                return new CriticResult(List.of());
            }
        }
    }

    /** 清洗围栏并抽取 {issues:[...]} 数组，非对象回复视为解析失败。 */
    private CriticResult parseCriticReply(String raw) throws JsonProcessingException {
        String cleaned = JsonReplyCleaner.extractJsonObject(
                JsonReplyCleaner.stripFences(raw).trim());
        JsonNode root = json.readTree(cleaned);
        if (root == null || !root.isObject()) {
            throw com.fasterxml.jackson.databind.JsonMappingException.from(
                    (com.fasterxml.jackson.core.JsonParser) null, "Expected a critic JSON object");
        }
        JsonNode arr = root.get("issues");
        List<String> issues = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText(null);
                if (s != null && !s.isBlank()) {
                    issues.add(s.trim());
                }
            }
        }
        return new CriticResult(issues);
    }
}
