package com.reviewpilot.pipeline;

import com.reviewpilot.service.ai.ReviewReplyReader;
import com.reviewpilot.service.ai.ReviewAgent;
import com.reviewpilot.service.prompt.CriticPromptBuilder;
import com.reviewpilot.service.critic.CriticAgent;
import com.reviewpilot.service.critic.ReflectionOrchestrator;
import com.reviewpilot.service.context.RiskFileContextLoader;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.ai.AgentReview;
import com.reviewpilot.service.risk.RiskMerger;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextLoader;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import com.reviewpilot.service.prompt.PromptBuilder;
import com.reviewpilot.service.risk.RiskDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives {@link ReviewPipeline#review(String)} end-to-end with mocked
 * collaborators. Complementary to {@code ReviewPipelineParseTest} which only
 * exercises {@code parseModelReply}/{@code stripFences} in isolation.
 */
class ReviewPipelineFlowTest {

    @Test
    void revision_preserves_key_findings() {
        when(fetcher.fetchFiles(any())).thenReturn(List.of(
                new FileChange("Foo.java", "modified", 1, 0, false, "@@", List.of())));
        when(promptBuilder.systemPrompt()).thenReturn("system");
        when(promptBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn("user");
        ModelProvider revisionModel = mock(ModelProvider.class);
        when(revisionModel.complete(any(), any())).thenReturn(
                "{\"issues\":[\"fix\"]}",
                "{\"summary\":\"fixed\",\"keyFindings\":[\"Keep this finding\"],\"risks\":[],\"suggestions\":[]}");
        var reflection = new ReflectionOrchestrator(revisionModel,
                new CriticAgent(revisionModel,
                        new CriticPromptBuilder()),
                new CriticPromptBuilder(), new ReviewReplyReader(), true);
        pipeline = new ReviewPipeline(fetcher, classifier, riskDetector, contextLoader,
                contentFetcher, promptBuilder, modelProvider, reviewAgent, reflection, new RiskMerger());
        assertEquals(List.of("Keep this finding"),
                pipeline.review("https://github.com/o/r/pull/1").keyFindings());
    }

    private GithubPrFetcher fetcher;
    private FileClassifier classifier;
    private RiskDetector riskDetector;
    private ContextLoader contextLoader;
    private RiskFileContextLoader contentFetcher;
    private PromptBuilder promptBuilder;
    private ModelProvider modelProvider;
    private ReviewAgent reviewAgent;
    private ReflectionOrchestrator orchestrator;
    private ReviewPipeline pipeline;

    @BeforeEach
    void setup() {
        fetcher = mock(GithubPrFetcher.class);
        classifier = mock(FileClassifier.class);
        riskDetector = mock(RiskDetector.class);
        contextLoader = mock(ContextLoader.class);
        contentFetcher = mock(RiskFileContextLoader.class);
        promptBuilder = mock(PromptBuilder.class);
        modelProvider = mock(ModelProvider.class);
        reviewAgent = mock(ReviewAgent.class);
        orchestrator = mock(ReflectionOrchestrator.class);
        when(modelProvider.name()).thenReturn("deepseek");
        when(classifier.classify(any())).thenReturn(FileType.OTHER);
        when(riskDetector.scan(any())).thenReturn(List.of());
        when(contextLoader.load(any(), any())).thenReturn(List.of());
        when(contentFetcher.fetchForRiskyFiles(any(), any())).thenReturn(Map.of());
        when(reviewAgent.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentReview(new ReviewResult("", "stub", List.of(), List.of(),
                        new ReviewResult.Meta("deepseek", null, 0, 0)), 1, 0));
        when(orchestrator.refine(any(), any(), any(), any()))
                .thenReturn(new ReflectionOrchestrator.RefinementResult(null, List.of()));
        pipeline = new ReviewPipeline(fetcher, classifier, riskDetector,
                contextLoader, contentFetcher, promptBuilder, modelProvider,
                reviewAgent, orchestrator, new RiskMerger());
    }

    @Test
    void happy_path_invokes_collaborators_in_order_and_fills_meta() {
        FileChange fc = new FileChange("src/Foo.java", "modified",
                3, 1, false, "@@ -1 +1 @@", List.of());
        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(promptBuilder.systemPrompt()).thenReturn("SYSTEM");
        when(promptBuilder.build(eq(List.of(fc)), any(), any(), any(), any(), any())).thenReturn("USER-PROMPT");

        String url = "https://github.com/owner/repo/pull/12";
        when(reviewAgent.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentReview(new ReviewResult(url, "refactor Foo", List.of(), List.of(),
                        new ReviewResult.Meta("deepseek", null, 1, 0)), 1, 0));

        ReviewResult r = pipeline.review(url);

        assertEquals(url, r.prUrl());
        assertEquals("refactor Foo", r.summary());
        assertTrue(r.risks().isEmpty());
        assertTrue(r.suggestions().isEmpty());

        // Meta is owned by the pipeline (not the parser).
        assertNotNull(r.meta(), "meta must be filled by the pipeline");
        assertEquals("deepseek", r.meta().provider());
        assertEquals(1, r.meta().filesAnalyzed());
        assertTrue(r.meta().elapsedMs() >= 0, "elapsedMs should be measured");

        // Verify ordering: fetch → classify → scan → load → reviewAgent
        InOrder order = inOrder(fetcher, classifier, riskDetector, contextLoader,
                reviewAgent);
        order.verify(fetcher).fetchFiles(any());
        order.verify(classifier).classify(fc);
        order.verify(riskDetector).scan(eq(List.of(fc)));
        order.verify(contextLoader).load(eq(List.of(fc)), eq(List.of()));
        order.verify(reviewAgent).review(any(), any(), any(), any(), any(), any());
    }

    @Test
    void classifier_runs_once_per_file_and_classifications_reach_prompt_builder() {
        FileChange a = new FileChange("Foo.java", "modified", 1, 0, false, "@@", List.of());
        FileChange b = new FileChange("Bar.java", "modified", 1, 0, false, "@@", List.of());
        when(fetcher.fetchFiles(any())).thenReturn(List.of(a, b));
        when(classifier.classify(a)).thenReturn(FileType.CONTROLLER);
        when(classifier.classify(b)).thenReturn(FileType.SERVICE);
        when(promptBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn("U");
        when(promptBuilder.systemPrompt()).thenReturn("S");
        when(modelProvider.complete(any(), any())).thenReturn(
                "{\"summary\":\"\",\"risks\":[],\"suggestions\":[]}");

        pipeline.review("https://github.com/o/r/pull/1");

        verify(classifier).classify(a);
        verify(classifier).classify(b);

        // The map handed to PromptBuilder must reflect what classifier returned —
        // not all-OTHER, not partial. Otherwise role-specific prompts disappear.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, FileType>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptBuilder).build(any(), captor.capture(), any(), any(), any(), any());
        Map<String, FileType> classifications = captor.getValue();
        assertEquals(FileType.CONTROLLER, classifications.get("Foo.java"));
        assertEquals(FileType.SERVICE, classifications.get("Bar.java"));
    }

    @Test
    void rule_risks_flow_into_prompt_and_are_merged_into_result() {
        FileChange fc = new FileChange("Foo.java", "modified", 1, 0, false, "@@", List.of());
        RiskItem ruleRisk = new RiskItem(RiskLevel.HIGH, "Foo.java", 12, "lock without unlock");
        ContextSlice slice = new ContextSlice("Foo.java", 7, 17,
                List.of("  7: x", "+ 12: lock", "  17: y"));

        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(riskDetector.scan(eq(List.of(fc)))).thenReturn(List.of(ruleRisk));
        when(contextLoader.load(eq(List.of(fc)), eq(List.of(ruleRisk)))).thenReturn(List.of(slice));
        when(promptBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn("U");
        when(promptBuilder.systemPrompt()).thenReturn("S");
        when(reviewAgent.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentReview(new ReviewResult("https://github.com/o/r/pull/1", "ok",
                        List.of(new RiskItem(RiskLevel.MEDIUM, "Foo.java", 42, "ai finding")),
                        List.of(),
                        new ReviewResult.Meta("deepseek", null, 1, 0)), 1, 0));

        ReviewResult r = pipeline.review("https://github.com/o/r/pull/1");

        // ContextLoader received the rule risks (not an empty list) — proves
        // the wiring carries findings forward, not just classification.
        verify(contextLoader).load(eq(List.of(fc)), eq(List.of(ruleRisk)));

        // PromptBuilder received both the rule risks and the context slice.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RiskItem>> riskCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContextSlice>> ctxCaptor = ArgumentCaptor.forClass(List.class);
        verify(promptBuilder).build(any(), any(), riskCaptor.capture(), ctxCaptor.capture(), any(), any());
        assertEquals(1, riskCaptor.getValue().size());
        assertEquals("lock without unlock", riskCaptor.getValue().get(0).message());
        assertEquals(1, ctxCaptor.getValue().size());

        // Final result merges rule + AI risks; rule findings come first.
        assertEquals(2, r.risks().size());
        assertEquals("lock without unlock", r.risks().get(0).message());
        assertEquals("ai finding", r.risks().get(1).message());
    }

    @Test
    void rule_and_ai_risks_with_same_file_line_message_are_deduplicated() {
        // The model dutifully echoes the rule finding verbatim. Without dedup
        // the UI would show the same row twice.
        FileChange fc = new FileChange("Foo.java", "modified", 1, 0, false, "@@", List.of());
        RiskItem ruleRisk = new RiskItem(RiskLevel.HIGH, "Foo.java", 12, "lock without unlock");
        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(riskDetector.scan(any())).thenReturn(List.of(ruleRisk));
        when(promptBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn("U");
        when(promptBuilder.systemPrompt()).thenReturn("S");
        when(reviewAgent.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentReview(new ReviewResult("", "ok", List.of(
                        new RiskItem(RiskLevel.MEDIUM, "Foo.java", 12, "lock without unlock")),
                        List.of(), null), 1, 0));

        ReviewResult r = pipeline.review("https://github.com/o/r/pull/1");

        assertEquals(1, r.risks().size(), "duplicate (file,line,message) should collapse");
        assertEquals("lock without unlock", r.risks().get(0).message());
    }

    @Test
    void empty_pr_short_circuits_without_calling_prompt_or_model() {
        when(fetcher.fetchFiles(any())).thenReturn(List.of());

        ReviewResult r = pipeline.review("https://github.com/owner/repo/pull/9");

        assertTrue(r.summary().contains("no changed files"));
        assertEquals(0, r.meta().filesAnalyzed());
        assertEquals("deepseek", r.meta().provider());

        // No prompt construction, no LLM call — saves tokens and avoids junk output.
        verify(promptBuilder, never()).build(any(), any(), any(), any(), any(), any());
        verify(promptBuilder, never()).systemPrompt();
        verify(modelProvider, never()).complete(any(), any());
    }

    @Test
    void invalid_pr_url_propagates_before_any_collaborator_runs() {
        assertThrows(IllegalArgumentException.class,
                () -> pipeline.review("not-a-pr-url"));

        // PrUrl.parse() should reject the URL before we hit GitHub or the LLM.
        verifyNoInteractions(fetcher);
        verifyNoInteractions(promptBuilder);
        verifyNoInteractions(modelProvider);
    }

    @Test
    void fetcher_failures_propagate_unchanged() {
        when(fetcher.fetchFiles(any()))
                .thenThrow(new GithubPrNotFoundException(
                        com.reviewpilot.model.PrUrl.parse("https://github.com/x/y/pull/1"),
                        new RuntimeException("boom")));

        assertThrows(GithubPrNotFoundException.class,
                () -> pipeline.review("https://github.com/x/y/pull/1"));

        // Still no LLM traffic when GitHub fetch failed — the controller maps
        // this to 404 and the user retries; we must not burn a DeepSeek call.
        verifyNoInteractions(modelProvider);
    }

    @Test
    void model_reply_parsing_is_handled_by_review_agent() {
        FileChange fc = new FileChange("a", "modified", 1, 0, false, "@@", List.of());
        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(promptBuilder.systemPrompt()).thenReturn("S");
        when(promptBuilder.build(any(), any(), any(), any(), any(), any())).thenReturn("U");
        when(reviewAgent.review(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentReview(new ReviewResult("https://github.com/owner/repo/pull/1", "ok",
                        List.of(), List.of(),
                        new ReviewResult.Meta("deepseek", null, 1, 0)), 1, 0));

        ReviewResult r = pipeline.review("https://github.com/owner/repo/pull/1");
        assertEquals("ok", r.summary());
    }
}
