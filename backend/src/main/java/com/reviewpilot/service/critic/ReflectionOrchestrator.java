package com.reviewpilot.service.critic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.JsonReplyCleaner;
import com.reviewpilot.service.ai.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the reflection loop: runs the Critic Agent against the
 * ReviewAgent's output and, when quality issues are found, triggers a
 * revision call.
 *
 * <p>This is the core of the V2 Agent upgrade — the first time in the
 * pipeline where an LLM output feeds back into the control flow.
 *
 * <p>Critic prompt building is kept private here rather than extracted into
 * a separate {@code @Component} because the orchestrator is the single
 * consumer and the prompt logic has no independent lifecycle or variance.
 */
@Component
public class ReflectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReflectionOrchestrator.class);

    private final ModelProvider modelProvider;
    private final ObjectMapper json;
    private final boolean enabled;

    public ReflectionOrchestrator(ModelProvider modelProvider,
                                   @Value("${reviewpilot.agent.reflection.enabled:true}") boolean enabled) {
        this.modelProvider = modelProvider;
        this.enabled = enabled;
        this.json = new ObjectMapper();
        log.info("Reflection orchestrator ready, enabled={}", enabled);
    }

    public record RefinementResult(String revisionRaw, List<String> criticIssues) {
        public boolean needsRevision() {
            return revisionRaw != null && !revisionRaw.isBlank();
        }
    }

    /**
     * Run the reflection loop: Critic → optionally Revision.
     * When disabled via config, returns an empty result immediately so the
     * pipeline serves the original review without extra LLM calls.
     */
    public RefinementResult refine(ReviewResult v0, List<RiskItem> ruleRisks,
                                    String systemPrompt, String userPrompt) {
        if (!enabled) {
            return new RefinementResult(null, List.of());
        }

        // Step 1: Run the Critic (with retry on parse failure)
        String criticSystem = criticSystemPrompt();
        String criticUser = buildCriticPrompt(ruleRisks, v0);
        CriticResult critic = runCritic(criticSystem, criticUser);

        if (!critic.needsRevision()) {
            log.debug("Critic found no issues — review passes on first attempt");
            return new RefinementResult(null, List.of());
        }

        log.info("Critic found {} issue(s), triggering revision. Issues: {}",
                critic.issues().size(), critic.issues());

        // Step 2: Build revision system prompt with critic feedback
        StringBuilder sb = new StringBuilder(systemPrompt.length() + 512);
        sb.append(systemPrompt)
          .append("\n\nYOUR PREVIOUS REVIEW HAD THESE ISSUES (fix them in this response):\n");
        for (String issue : critic.issues()) {
            sb.append("- ").append(issue).append('\n');
        }
        sb.append("\nRespond with a corrected review as strictly valid JSON.");

        String revisionRaw = modelProvider.complete(sb.toString(), userPrompt);
        return new RefinementResult(revisionRaw, critic.issues());
    }

    // ── Critic prompt (private — single consumer, no template variance) ──

    private String criticSystemPrompt() {
        return """
                You are a strict code review quality inspector. Your job is to check an
                AI-generated code review for quality issues.

                You will receive:
                1. Pre-detected rule risks (from deterministic static analysis)
                2. The AI-generated review result (summary + risks + suggestions)

                Check for:
                - HALLUCINATION: An AI risk makes a claim not supported by the rule
                  risks. The AI's "Context" for a risk may mention a guard (null check,
                  Optional) that disproves the risk. Flag it.
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

    private String buildCriticPrompt(List<RiskItem> ruleRisks, ReviewResult review) {
        StringBuilder sb = new StringBuilder(4096);
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
