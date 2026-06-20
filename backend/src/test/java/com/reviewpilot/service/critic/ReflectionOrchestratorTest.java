package com.reviewpilot.service.critic;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.ai.ModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReflectionOrchestratorTest {

    private ModelProvider modelProvider;
    private ReflectionOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        modelProvider = mock(ModelProvider.class);
        orchestrator = new ReflectionOrchestrator(modelProvider, true);
    }

    @Test
    void disabled_returnsEmpty() {
        ReflectionOrchestrator disabled = new ReflectionOrchestrator(modelProvider, false);

        var result = disabled.refine(
                sampleReview(),
                List.of(),
                "system prompt",
                "user prompt");

        assertFalse(result.needsRevision());
        assertEquals(List.of(), result.criticIssues());
        verify(modelProvider, times(0)).complete(anyString(), anyString());
    }

    @Test
    void criticFindsNoIssues_noRevision() {
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("{\"issues\":[]}");

        var result = orchestrator.refine(
                sampleReview(),
                List.of(),
                "system prompt",
                "user prompt");

        assertFalse(result.needsRevision());
        assertEquals(List.of(), result.criticIssues());
        verify(modelProvider, times(1)).complete(anyString(), anyString());
    }

    @Test
    void criticFindsIssues_triggersRevision() {
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("{\"issues\":[\"[MISSING] rule #1 not in AI output\"]}")
                .thenReturn("{\"summary\":\"fixed\",\"risks\":[],\"suggestions\":[]}");

        ReviewResult v0 = sampleReview();
        List<RiskItem> rules = List.of(
                new RiskItem(RiskLevel.HIGH, "Foo.java", 5, "unreleased lock"));

        var result = orchestrator.refine(v0, rules, "system", "user");

        assertTrue(result.needsRevision());
        assertEquals(1, result.criticIssues().size());
        assertTrue(result.criticIssues().get(0).contains("MISSING"));
        assertNotNull(result.revisionRaw());
        assertTrue(result.revisionRaw().contains("fixed"));
        verify(modelProvider, times(2)).complete(anyString(), anyString());
    }

    @Test
    void criticFindsMultipleIssues_allListed() {
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("{\"issues\":[" +
                        "\"[MISSING] rule #1 not in AI output\"," +
                        "\"[SEVERITY] risk #2 should be HIGH\"]}")
                .thenReturn("{\"summary\":\"fixed\",\"risks\":[],\"suggestions\":[]}");

        var result = orchestrator.refine(
                sampleReview(),
                List.of(new RiskItem(RiskLevel.HIGH, "Bar.java", 10, "bare catch")),
                "system", "user");

        assertEquals(2, result.criticIssues().size());
        assertTrue(result.needsRevision());
    }

    @Test
    void criticParseFails_retrySucceeds() {
        // First call returns invalid JSON → parse fails → retry with error feedback.
        // Retry returns valid JSON with empty issues → no revision triggered.
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("not valid json {{")
                .thenReturn("{\"issues\":[]}");

        List<RiskItem> rules = List.of(
                new RiskItem(RiskLevel.HIGH, "Foo.java", 1, "bare catch"));
        var result = orchestrator.refine(sampleReview(), rules, "system", "user");

        assertFalse(result.needsRevision());
        assertEquals(List.of(), result.criticIssues());
        verify(modelProvider, times(2)).complete(anyString(), anyString());
    }

    @Test
    void criticParseRetryAlsoFails_returnsEmpty() {
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("garbage {{")
                .thenReturn("still garbage");

        var result = orchestrator.refine(
                sampleReview(),
                List.of(),
                "system", "user");

        assertFalse(result.needsRevision());
        assertEquals(List.of(), result.criticIssues());
        verify(modelProvider, times(2)).complete(anyString(), anyString());
    }

    @Test
    void emptyIssuesArray_noRevision() {
        // Critic returns issues array with empty strings — filtered out
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("{\"issues\":[\"\", \"  \"]}");

        var result = orchestrator.refine(
                sampleReview(),
                List.of(),
                "system", "user");

        assertFalse(result.needsRevision());
    }

    @Test
    void nullRuleRisks_handled() {
        when(modelProvider.complete(anyString(), anyString()))
                .thenReturn("{\"issues\":[]}");

        var result = orchestrator.refine(
                sampleReview(),
                null,
                "system", "user");

        assertFalse(result.needsRevision());
    }

    private static ReviewResult sampleReview() {
        return new ReviewResult("https://github.com/a/b/pull/1",
                "sample summary",
                List.of(new RiskItem(RiskLevel.MEDIUM, "Foo.java", 42, "NPE risk")),
                List.of(),
                new ReviewResult.Meta("test", "test-model", 1, 1000, 1));
    }
}
