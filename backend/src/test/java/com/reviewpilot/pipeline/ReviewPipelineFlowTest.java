package com.reviewpilot.pipeline;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.service.ai.ModelProvider;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.github.GithubPrFetcher;
import com.reviewpilot.service.github.GithubPrNotFoundException;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

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

    private GithubPrFetcher fetcher;
    private PromptBuilder promptBuilder;
    private ModelProvider modelProvider;
    private ReviewPipeline pipeline;

    @BeforeEach
    void setup() {
        fetcher = mock(GithubPrFetcher.class);
        promptBuilder = mock(PromptBuilder.class);
        modelProvider = mock(ModelProvider.class);
        when(modelProvider.name()).thenReturn("deepseek");
        pipeline = new ReviewPipeline(fetcher, promptBuilder, modelProvider);
    }

    @Test
    void happy_path_invokes_collaborators_in_order_and_fills_meta() {
        FileChange fc = new FileChange("src/Foo.java", "modified",
                3, 1, false, "@@ -1 +1 @@", List.of());
        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(promptBuilder.systemPrompt()).thenReturn("SYSTEM");
        when(promptBuilder.build(eq(List.of(fc)), any(), any(), any())).thenReturn("USER-PROMPT");
        when(modelProvider.complete("SYSTEM", "USER-PROMPT")).thenReturn("""
                {"summary":"refactor Foo","risks":[],"suggestions":[]}
                """);

        String url = "https://github.com/owner/repo/pull/12";
        ReviewResult r = pipeline.review(url);

        // Schema fields surfaced from the model reply.
        assertEquals(url, r.prUrl());
        assertEquals("refactor Foo", r.summary());
        assertTrue(r.risks().isEmpty());
        assertTrue(r.suggestions().isEmpty());

        // Meta is owned by the pipeline (not the parser).
        assertNotNull(r.meta(), "meta must be filled by the pipeline");
        assertEquals("deepseek", r.meta().provider());
        assertEquals(1, r.meta().filesAnalyzed());
        assertTrue(r.meta().elapsedMs() >= 0, "elapsedMs should be measured");

        // Verify ordering: fetch → build user prompt → ask for system prompt
        // → call LLM. promptBuilder.build must be fed exactly the fetch
        // output, and the model call must use the system prompt from
        // PromptBuilder, not a literal.
        InOrder order = inOrder(fetcher, promptBuilder, modelProvider);
        order.verify(fetcher).fetchFiles(any());
        order.verify(promptBuilder).build(eq(List.of(fc)), any(), any(), any());
        order.verify(promptBuilder).systemPrompt();
        order.verify(modelProvider).complete("SYSTEM", "USER-PROMPT");

        // Defensive: prompt builder receives the exact list the fetcher returned.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FileChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(promptBuilder).build(captor.capture(), any(), any(), any());
        assertSame(fc, captor.getValue().get(0));
    }

    @Test
    void empty_pr_short_circuits_without_calling_prompt_or_model() {
        when(fetcher.fetchFiles(any())).thenReturn(List.of());

        ReviewResult r = pipeline.review("https://github.com/owner/repo/pull/9");

        assertTrue(r.summary().contains("no changed files"));
        assertEquals(0, r.meta().filesAnalyzed());
        assertEquals("deepseek", r.meta().provider());

        // No prompt construction, no LLM call — saves tokens and avoids junk output.
        verify(promptBuilder, never()).build(any(), any(), any(), any());
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
    void model_reply_with_markdown_fence_is_still_parsed_in_main_flow() {
        FileChange fc = new FileChange("a", "modified", 1, 0, false, "@@", List.of());
        when(fetcher.fetchFiles(any())).thenReturn(List.of(fc));
        when(promptBuilder.systemPrompt()).thenReturn("S");
        when(promptBuilder.build(any(), any(), any(), any())).thenReturn("U");
        when(modelProvider.complete(any(), any())).thenReturn("""
                ```json
                {"summary":"ok","risks":[],"suggestions":[]}
                ```
                """);

        ReviewResult r = pipeline.review("https://github.com/owner/repo/pull/1");
        // Fence stripping happens inside the main flow, not just in the
        // parser-only test — guards against the orchestrator forgetting to
        // call parseModelReply on the raw text.
        assertEquals("ok", r.summary());
    }
}
