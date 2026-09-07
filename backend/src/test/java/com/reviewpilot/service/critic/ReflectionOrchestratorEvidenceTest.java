package com.reviewpilot.service.critic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.ReviewReplyReader;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two properties of the quality loop that the shape of the code does not make
 * obvious:
 *
 * <p>1) The critic is asked to call findings hallucinations, so it must be shown
 * the code. Given only the rule list and the AI's own text, the sole available
 * "evidence" is that nine heuristics stayed silent, which proves nothing.
 *
 * <p>2) A revision that never becomes JSON must not replace a review that did.
 */
class ReflectionOrchestratorEvidenceTest {

    private ModelProvider provider;
    private ReflectionOrchestrator orchestrator;

    private void setUpReturning(String... replies) {
        provider = mock(ModelProvider.class);
        if (replies.length == 1) {
            when(provider.complete(anyString(), anyString())).thenReturn(replies[0]);
        } else {
            when(provider.complete(anyString(), anyString()))
                    .thenReturn(replies[0], java.util.Arrays.copyOfRange(replies, 1, replies.length));
        }
        CriticPromptBuilder prompts = new CriticPromptBuilder();
        ReviewReplyReader reader = new ReviewReplyReader();
        orchestrator = new ReflectionOrchestrator(provider,
                new CriticAgent(provider, prompts, new ObjectMapper()), prompts, reader, true);
    }

    @Test
    void critic_is_shown_the_code_it_is_judging() throws Exception {
        setUpReturning("{\"issues\":[]}");
        ReviewResult v0 = new ReviewResult("u", "original", List.of(), List.of(),
                new ReviewResult.Meta("p", "m", 1, 1));

        orchestrator.refine(v0, List.of(), "system", "DIFF-BODY if (user == null) return;");

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(provider, atLeastOnce()).complete(anyString(), user.capture());
        assertTrue(user.getAllValues().get(0).contains("DIFF-BODY"),
                "the first call is the critic, and it must carry the code under review");
    }

    @Test
    void a_revision_that_never_parses_leaves_the_original_in_place() throws Exception {
        setUpReturning("{\"issues\":[\"[MISSING] rule #1 absent\"]}",
                "not json at all", "still not json");
        ReviewResult v0 = new ReviewResult("u", "original", List.of(), List.of(),
                new ReviewResult.Meta("p", "m", 1, 1));

        ReflectionOrchestrator.RefinementResult result =
                orchestrator.refine(v0, List.of(), "system", "DIFF");

        assertFalse(result.needsRevision(), "a reply that never became JSON is not a revision");
        assertEquals(1, result.criticIssues().size(), "the findings are still reported");
    }
}
