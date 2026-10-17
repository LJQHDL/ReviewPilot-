package com.reviewpilot.service.ai;

import com.reviewpilot.model.Deadline;
import com.reviewpilot.model.PrUrl;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Once the request budget is gone the loop must stop asking: the budget is what
 * keeps a review the client has already abandoned from starting another paid
 * model round-trip. The loop may reuse the last assistant reply, but it must
 * never call the model again — not even for a forced completion or a JSON repair.
 */
class ReviewAgentDeadlineTest {

    private static final String JSON = "{\"summary\":\"done\",\"risks\":[],\"suggestions\":[]}";
    private static final PrUrl PR = new PrUrl("owner", "repo", 1);

    private static ReviewAgent agent(ModelProvider modelProvider, ToolRegistry toolRegistry,
                                     int maxRounds) {
        return new ReviewAgent(modelProvider, toolRegistry, new PromptBuilder(),
                new ReviewReplyReader(), maxRounds, 64000);
    }

    private static AgentResponse slowToolCall(String content) {
        return new AgentResponse(content,
                List.of(new ToolCall("c1", "fetch_file_content", Map.of("path", "a/A.java"))), 11, 7);
    }

    @Test
    void an_expired_budget_never_calls_the_model() {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());

        AgentReview review = agent(modelProvider, toolRegistry, 8).review(
                List.of(), Map.of(), List.of(), List.of(), "title", PR,
                new Deadline(System.currentTimeMillis() - 1));

        verify(modelProvider, never()).chat(anyList(), anyList());
        verify(toolRegistry, never()).execute(any(), any());
        assertTrue(review.result().summary().contains("时间预算已用尽"), review.result().summary());
        assertEquals(1, review.reactRounds());
    }

    @Test
    void an_expired_budget_reuses_the_last_assistant_reply() throws Exception {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(modelProvider.chat(anyList(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return slowToolCall(JSON);
        });

        AgentReview review = agent(modelProvider, toolRegistry, 8).review(
                List.of(), Map.of(), List.of(), List.of(), "title", PR, Deadline.of(20));

        assertEquals("done", review.result().summary());
        verify(modelProvider, times(1)).chat(anyList(), anyList());
        verify(toolRegistry, never()).execute(any(), any());
    }

    @Test
    void an_expired_budget_degrades_when_the_last_reply_is_not_json() throws Exception {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(modelProvider.chat(anyList(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return slowToolCall("I could not finish the review");
        });

        AgentReview review = agent(modelProvider, toolRegistry, 8).review(
                List.of(), Map.of(), List.of(), List.of(), "title", PR, Deadline.of(20));

        assertTrue(review.result().summary().contains("时间预算已用尽"), review.result().summary());
        assertTrue(review.result().risks().isEmpty());
        verify(modelProvider, times(1)).chat(anyList(), anyList());
    }

    @Test
    void an_expired_budget_wins_over_the_max_rounds_force_completion() throws Exception {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(modelProvider.chat(anyList(), anyList())).thenAnswer(invocation -> {
            Thread.sleep(50);
            return slowToolCall(JSON);
        });

        AgentReview review = agent(modelProvider, toolRegistry, 1).review(
                List.of(), Map.of(), List.of(), List.of(), "title", PR, Deadline.of(20));

        assertEquals("done", review.result().summary());
        verify(modelProvider, times(1)).chat(anyList(), anyList());
    }

    @Test
    void token_usage_accumulates_across_every_call_the_loop_makes() {
        ModelProvider modelProvider = mock(ModelProvider.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getDefinitions()).thenReturn(List.of());
        when(toolRegistry.execute(any(), any())).thenReturn("File: x\n```\ny\n```");
        // round 1 asks for a tool, round 2 answers
        when(modelProvider.chat(anyList(), anyList()))
                .thenReturn(new AgentResponse("", List.of(
                        new ToolCall("c1", "fetch_file_content", Map.of("path", "a/A.java"))), 100, 5))
                .thenReturn(new AgentResponse(JSON, List.of(), 200, 25));

        AgentReview review = agent(modelProvider, toolRegistry, 8).review(
                List.of(), Map.of(), List.of(), List.of(), "title", PR, Deadline.NONE);

        assertEquals(2, review.reactRounds());
        assertEquals(1, review.toolCallCount());
        assertEquals(300, review.promptTokens());
        assertEquals(30, review.completionTokens());
        assertTrue(review.result().summary().isBlank() == false);
    }
}
