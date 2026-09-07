package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAgentTest {

    @Test
    void concurrent_reviews_keep_their_own_tool_counts() throws Exception {
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(toolRegistry.execute(any(), any())).thenReturn("evidence");
        when(modelProvider.chat(anyList(), anyList())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            boolean firstReview = messages.get(1).content().contains("FIRST_REVIEW");
            boolean hasEvidence = messages.stream().anyMatch(m -> "tool".equals(m.role()));
            if (firstReview && !hasEvidence) {
                return new AgentResponse("", List.of(new ToolCall("c1", "fetch_file_content",
                        Map.of("path", "Foo.java"))), 0, 0);
            }
            if (firstReview) {
                waiting.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            return new AgentResponse("{\"summary\":\"ok\"}", List.of(), 0, 0);
        });
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> reviewAgent.review(List.of(), Map.of(), List.of(),
                    List.of(), "FIRST_REVIEW", new PrUrl("a", "b", 1)));
            assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS));
            AgentReview second = reviewAgent.review(List.of(), Map.of(), List.of(),
                    List.of(), "SECOND_REVIEW", new PrUrl("a", "b", 2));
            release.countDown();
            AgentReview completedFirst = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, completedFirst.reactRounds());
            assertEquals(1, completedFirst.toolCallCount());
            assertEquals(1, second.reactRounds());
            assertEquals(0, second.toolCallCount());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void context_limit_completion_returns_current_request_statistics() {
        when(modelProvider.chat(anyList(), anyList())).thenReturn(
                new AgentResponse("{\"summary\":\"limited\"}", List.of(), 0, 0));
        var limited = new ReviewAgent(modelProvider, toolRegistry, promptBuilder,
                new ReviewReplyReader(), 8, 1);
        AgentReview outcome = limited.review(List.of(), Map.of(), List.of(), List.of(),
                "title", new PrUrl("a", "b", 1));
        assertEquals("limited", outcome.result().summary());
        assertEquals(1, outcome.reactRounds());
        assertEquals(0, outcome.toolCallCount());
    }

    private ModelProvider modelProvider;
    private ToolRegistry toolRegistry;
    private PromptBuilder promptBuilder;
    private ReviewAgent reviewAgent;

    @BeforeEach
    void setup() {
        modelProvider = mock(ModelProvider.class);
        toolRegistry = mock(ToolRegistry.class);
        promptBuilder = new PromptBuilder();
        reviewAgent = new ReviewAgent(modelProvider, toolRegistry, promptBuilder, new ReviewReplyReader(), 8, 64000);
    }

    @Test
    void singleRound_noTools_directOutput() {
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse(
                        "{\"summary\":\"ok\",\"risks\":[],\"suggestions\":[]}",
                        List.of(), 0, 0));

        FileChange fc = new FileChange("Foo.java", "modified", 1, 0, false,
                "@@ -1 +1 @@\n+    int x;",
                List.of(new DiffHunk(1, 1, 0, 1,
                        List.of(new DiffLine(DiffLineType.ADDED, 0, 1, "    int x;")))));

        ReviewResult r = reviewAgent.review(
                List.of(fc),
                Map.of("Foo.java", FileType.SERVICE),
                List.of(),
                List.of(),
                "add field",
                PrUrl.parse("https://github.com/a/b/pull/1")).result();

        assertNotNull(r);
        assertEquals("ok", r.summary());
    }

    @Test
    void multiRound_withToolCalls() {
        // Round 1: LLM asks to fetch a file
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("call_1", "fetch_file_content",
                                Map.of("path", "Foo.java"))),
                        0, 0))
                // Round 2: LLM outputs final review
                .thenReturn(new AgentResponse(
                        "{\"summary\":\"done\",\"risks\":[],\"suggestions\":[]}",
                        List.of(), 0, 0));

        when(toolRegistry.execute(any(), any())).thenReturn("file content here");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        FileChange fc = file("Foo.java", "+    lock.lock();");
        ReviewResult r = reviewAgent.review(
                List.of(fc),
                Map.of("Foo.java", FileType.SERVICE),
                List.of(new RiskItem(RiskLevel.HIGH, "Foo.java", 5, "unreleased lock")),
                List.of(),
                "add lock",
                PrUrl.parse("https://github.com/a/b/pull/1")).result();

        assertNotNull(r);
        assertEquals("done", r.summary());
    }

    @Test
    void toolExecutionFailure_continuesNormally() {
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("call_1", "fetch_file_content",
                                Map.of("path", "missing.txt"))),
                        0, 0))
                .thenReturn(new AgentResponse(
                        "{\"summary\":\"partial\",\"risks\":[],\"suggestions\":[]}",
                        List.of(), 0, 0));

        when(toolRegistry.execute(any(), any())).thenReturn("File not found: missing.txt");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        FileChange fc = file("Foo.java", "+    int x;");
        ReviewResult r = reviewAgent.review(
                List.of(fc),
                Map.of("Foo.java", FileType.OTHER),
                List.of(),
                List.of(),
                "test",
                PrUrl.parse("https://github.com/a/b/pull/1")).result();

        assertEquals("partial", r.summary());
    }

    @Test
    void maxRoundsExceeded_forcesCompletion() {
        // Always return tool calls — never finishes
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("c1", "search_repo",
                                Map.of("query", "test"))),
                        0, 0));
        when(toolRegistry.execute(any(), any())).thenReturn("result");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        ReviewAgent tightAgent = new ReviewAgent(modelProvider, toolRegistry,
                promptBuilder, new ReviewReplyReader(), 2, 64000);

        FileChange fc = file("Foo.java", "+    int x;");
        ReviewResult r = tightAgent.review(
                List.of(fc),
                Map.of("Foo.java", FileType.OTHER),
                List.of(),
                List.of(),
                "test",
                PrUrl.parse("https://github.com/a/b/pull/1")).result();

        assertNotNull(r);
    }

    @Test
    void convergenceAfterFourToolCalls_stopsGivingTools() {
        // 4 tool calls, then stop
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("c1", "fetch_file_content", Map.of("path", "A.java"))),
                        0, 0))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("c2", "fetch_file_content", Map.of("path", "B.java"))),
                        0, 0))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("c3", "fetch_file_content", Map.of("path", "C.java"))),
                        0, 0))
                .thenReturn(new AgentResponse("",
                        List.of(new ToolCall("c4", "fetch_file_content", Map.of("path", "D.java"))),
                        0, 0))
                .thenReturn(new AgentResponse(
                        "{\"summary\":\"converged\",\"risks\":[],\"suggestions\":[]}",
                        List.of(), 0, 0));

        when(toolRegistry.execute(any(), any())).thenReturn("content");
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        FileChange fc = file("Foo.java", "+    int x;");
        ReviewResult r = reviewAgent.review(
                List.of(fc),
                Map.of("Foo.java", FileType.OTHER),
                List.of(),
                List.of(),
                "test",
                PrUrl.parse("https://github.com/a/b/pull/1")).result();

        assertEquals("converged", r.summary());
    }

    private static FileChange file(String name, String addedLine) {
        return new FileChange(name, "modified", 1, 0, false,
                "@@ -0,0 +1 @@\n" + addedLine,
                List.of(new DiffHunk(1, 1, 0, 1,
                        List.of(new DiffLine(DiffLineType.ADDED, 0, 1, addedLine)))));
    }
}
