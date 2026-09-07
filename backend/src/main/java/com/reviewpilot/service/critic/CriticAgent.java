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

/** Executes one quality inspection, with one malformed-JSON retry. */
@Component
public class CriticAgent {
    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);
    private final ModelProvider modelProvider;
    private final CriticPromptBuilder prompts;
    private final ObjectMapper json = new ObjectMapper();

    public CriticAgent(ModelProvider modelProvider, CriticPromptBuilder prompts) {
        this.modelProvider = modelProvider;
        this.prompts = prompts;
    }

    public CriticResult inspect(ReviewResult review, List<RiskItem> ruleRisks) {
        return runCritic(prompts.systemPrompt(), prompts.build(ruleRisks, review));
    }

    /** Run the Critic with up to one retry if the JSON reply is malformed. */
    private CriticResult runCritic(String systemPrompt, String userPrompt) {
        String raw = modelProvider.complete(systemPrompt, userPrompt);
        try {
            return parseCriticReply(raw);
        } catch (JsonProcessingException e) {
            log.warn("Critic parse failed ({}), retrying with error feedback",
                    e.getOriginalMessage());
            String retryUser = userPrompt
                    + "\n\nYour previous response was not valid JSON. Parser error: "
                    + e.getOriginalMessage() + ". Respond with strictly valid JSON.";
            String raw2 = modelProvider.complete(systemPrompt, retryUser);
            try {
                return parseCriticReply(raw2);
            } catch (JsonProcessingException e2) {
                log.error("Critic retry also failed ({}); review passes through "
                        + "UNCORRECTED — quality loop is broken for this request",
                        e2.getOriginalMessage());
                return new CriticResult(List.of());
            }
        }
    }

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
