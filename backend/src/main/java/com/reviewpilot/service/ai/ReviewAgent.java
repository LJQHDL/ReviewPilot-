package com.reviewpilot.service.ai;

import com.reviewpilot.model.PrUrl;
import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.context.ContextSlice;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReAct Agent: replaces the single-shot LLM call with a multi-turn
 * Reasoning + Acting loop. The LLM autonomously decides what tools to
 * call and when to stop and produce the final review.
 */
@Service
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final PromptBuilder promptBuilder;
    private final ReviewReplyReader replyReader;

    private final int maxRounds;
    private final int maxTotalChars;

    public ReviewAgent(ModelProvider modelProvider,
                       ToolRegistry toolRegistry,
                       PromptBuilder promptBuilder,
                       ReviewReplyReader replyReader,
                       @Value("${reviewpilot.agent.react.max-rounds:8}") int maxRounds,
                       @Value("${reviewpilot.agent.react.max-total-chars:64000}") int maxTotalChars) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.promptBuilder = promptBuilder;
        this.replyReader = replyReader;
        this.maxRounds = maxRounds;
        this.maxTotalChars = maxTotalChars;
    }

    public AgentReview review(List<FileChange> files,
                                Map<String, FileType> classifications,
                                List<RiskItem> ruleRisks,
                                List<ContextSlice> contexts,
                                String prTitle,
                                PrUrl pr) {
        // 1. Build initial messages
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(promptBuilder.reactSystemPrompt()));
        messages.add(Message.user(promptBuilder.reactUserPrompt(
                files, classifications, ruleRisks, contexts, prTitle,
                toolRegistry.getDefinitions())));

        // 2. ReAct loop
        int round = 0;
        int toolCallCount = 0;

        while (round < maxRounds) {
            round++;

            // Token budget: compress if over 80%
            if (estimateChars(messages) > maxTotalChars * 0.8) {
                messages = compressToolResults(messages);
            }
            // Still over limit after compression → force finish
            if (estimateChars(messages) > maxTotalChars) {
                messages.add(Message.user("上下文已满，请立即输出审查结果 JSON，不要调工具。"));
                return new AgentReview(forceComplete(messages), round, toolCallCount);
            }

            // Convergence: 4 tool calls then no more tools
            boolean giveTools = toolCallCount < 4;
            List<Tool> tools = giveTools ? toolRegistry.getDefinitions() : List.of();

            AgentResponse resp = modelProvider.chat(messages, tools);
            log.debug("ReAct round {}: toolCalls={} contentLen={}",
                    round, resp.hasToolCalls(), resp.content().length());

            if (resp.hasToolCalls()) {
                // Add the assistant message with tool_calls first (required by API spec)
                messages.add(Message.assistant(resp.content(), resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    String result = toolRegistry.execute(call, pr);
                    messages.add(Message.tool(call.id(), result));
                    toolCallCount++;
                }
                if (toolCallCount >= 4 && giveTools) {
                    messages.add(Message.user(
                            "已进行 " + toolCallCount + " 次工具调用。请立即输出最终审" +
                            "查结果 JSON，不要继续调用工具。"));
                }
                continue;
            }

            // No tool calls → LLM is done, parse result
            return new AgentReview(parseReply(resp.content(), messages), round, toolCallCount);
        }

        // 3. Exceeded maxRounds — force completion
        messages.add(Message.user("已达到最大轮次 " + maxRounds + "。请立即输出审查结果 JSON。"));
        return new AgentReview(forceComplete(messages), round, toolCallCount);
    }

    /** Force the LLM to output a final result (no tools). */
    private ReviewResult forceComplete(List<Message> messages) {
        AgentResponse resp = modelProvider.chat(messages, List.of());
        return parseReply(resp.content(), messages);
    }

    private ReviewResult parseReply(String raw, List<Message> messages) {
        return replyReader.read(raw, feedback -> {
            messages.add(Message.assistant(raw, List.of()));
            messages.add(Message.user(feedback));
            return modelProvider.chat(messages, List.of()).content();
        });
    }

    /** Rough char-count estimate for token budget management. */
    private static int estimateChars(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            n += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) n += m.toolCalls().toString().length();
        }
        return n;
    }

    /**
     * Compress early tool results to save context. For tool messages over
     * 2000 chars, truncate and mark the compression.
     */
    private static List<Message> compressToolResults(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if ("tool".equals(m.role()) && m.content() != null
                    && m.content().length() > 2000) {
                String truncated = m.content().substring(0, 1000)
                        + "\n[...compressed " + (m.content().length() - 2000)
                        + " chars]";
                out.add(Message.tool(m.toolCallId(), truncated));
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
