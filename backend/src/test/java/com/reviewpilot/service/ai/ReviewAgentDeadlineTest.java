package com.reviewpilot.service.ai;

import com.reviewpilot.model.Deadline;
import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Once the request budget is gone the loop must stop asking and produce a result.
 * The budget is what keeps a review that the client has already abandoned from
 * starting another paid model round-trip.
 */
class ReviewAgentDeadlineTest {

    private static final String JSON = "{\"summary\":\"done\",\"risks\":[],\"suggestions\":[]}";

    @Test
    void an_expired_budget_answers_immediately_without_calling_tools() {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        PromptBuilder promptBuilder = new PromptBuilder();
        ReviewAgent agent = new ReviewAgent(modelProvider, toolRegistry, promptBuilder,
                new ReviewReplyReader(), 8, 64000);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse(JSON, List.of(), 11, 7));

        Deadline expired = new Deadline(System.currentTimeMillis() - 1);
        AgentReview review = agent.review(
                List.of(), Map.of(), List.of(), List.of(), "title",
                new PrUrl("owner", "repo", 1), expired);

        assertEquals("done", review.result().summary());
        assertEquals(1, review.reactRounds());
        // no tool execution once the budget is spent
        verify(toolRegistry, never()).execute(any(), any());
    }

    @Test
    void token_usage_accumulates_across_every_call_the_loop_makes() {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        PromptBuilder promptBuilder = new PromptBuilder();
        ReviewAgent agent = new ReviewAgent(modelProvider, toolRegistry, promptBuilder,
                new ReviewReplyReader(), 8, 64000);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(toolRegistry.execute(any(), any())).thenReturn("File: x\n```\ny\n```");
        // round 1 asks for a tool, round 2 answers
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("", List.of(
                        new ToolCall("c1", "fetch_file_content", Map.of("path", "a/A.java"))), 100, 5))
                .thenReturn(new AgentResponse(JSON, List.of(), 200, 25));

        AgentReview review = agent.review(
                List.of(), Map.of(), List.of(), List.of(), "title",
                new PrUrl("owner", "repo", 1), Deadline.NONE);

        assertEquals(2, review.reactRounds());
        assertEquals(1, review.toolCallCount());
        assertEquals(300, review.promptTokens());
        assertEquals(30, review.completionTokens());
        assertTrue(review.result().summary().isBlank() == false);
    }
}
