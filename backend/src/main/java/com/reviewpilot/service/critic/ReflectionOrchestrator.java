package com.reviewpilot.service.critic;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.ReviewReplyReader;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Coordinates quality inspection and optional revision; returns structured data to the pipeline. */
@Component
public class ReflectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReflectionOrchestrator.class);

    private final ModelProvider modelProvider;
    private final CriticAgent criticAgent;
    private final CriticPromptBuilder prompts;
    private final ReviewReplyReader replyReader;
    private final boolean enabled;

    public ReflectionOrchestrator(ModelProvider modelProvider,
                                   CriticAgent criticAgent,
                                   CriticPromptBuilder prompts,
                                   ReviewReplyReader replyReader,
                                   @Value("${reviewpilot.agent.reflection.enabled:true}") boolean enabled) {
        this.modelProvider = modelProvider;
        this.enabled = enabled;
        this.criticAgent = criticAgent;
        this.prompts = prompts;
        this.replyReader = replyReader;
        log.info("Reflection orchestrator ready, enabled={}", enabled);
    }

    public record RefinementResult(ReviewResult revision, List<String> criticIssues) {
        public boolean needsRevision() {
            return revision != null;
        }
    }

    /**
     * Run the reflection loop: Critic → optionally Revision.
     * When disabled via config, returns an empty result immediately so the
     * pipeline serves the original review without extra LLM calls.
     *
     * <p>{@code userPrompt} doubles as the code the critic judges against, and the
     * revision is only adopted if it parses — a reply that never became JSON must
     * not replace a review that did.
     */
    public RefinementResult refine(ReviewResult v0, List<RiskItem> ruleRisks,
                                    String systemPrompt, String userPrompt) {
        if (!enabled) {
            return new RefinementResult(null, List.of());
        }

        // Step 1: Run the Critic (with retry on parse failure)
        CriticResult critic = criticAgent.inspect(v0, ruleRisks, userPrompt);

        if (!critic.needsRevision()) {
            log.debug("Critic found no issues — review passes on first attempt");
            return new RefinementResult(null, List.of());
        }

        log.info("Critic found {} issue(s), triggering revision. Issues: {}",
                critic.issues().size(), critic.issues());

        // Step 2: Build revision system prompt with critic feedback
        String revisionSystem = prompts.revisionSystemPrompt(systemPrompt, critic.issues());

        String revisionRaw = modelProvider.complete(revisionSystem, userPrompt);
        if (revisionRaw == null || revisionRaw.isBlank()) {
            return new RefinementResult(null, critic.issues());
        }
        return replyReader.readOrNull(revisionRaw, feedback ->
                        modelProvider.complete(revisionSystem + "\n\n" + feedback, userPrompt))
                .map(revision -> new RefinementResult(revision, critic.issues()))
                .orElseGet(() -> {
                    log.warn("Revision reply never parsed; serving the unrevised review");
                    return new RefinementResult(null, critic.issues());
                });
    }

}
