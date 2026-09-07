package com.reviewpilot.service.ai;

import com.reviewpilot.model.Deadline;
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
 *
 * <p>Per-invocation state lives in {@link Loop} and comes back as
 * {@link AgentReview}; nothing is accumulated on the bean, so one instance can
 * serve concurrent PRs without their counters crossing over.
 */
@Service
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    static final int TOOL_CALL_LIMIT = 4;
    private static final double COMPRESS_AT = 0.8;
    private static final int TOOL_RESULT_COMPRESS_FROM = 2000;
    private static final int TOOL_RESULT_KEEP_CHARS = 1000;

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
                              PrUrl pr,
                              Deadline deadline) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(promptBuilder.reactSystemPrompt()));
        messages.add(Message.user(promptBuilder.reactUserPrompt(
                files, classifications, ruleRisks, contexts, prTitle,
                toolRegistry.getDefinitions())));

        Loop loop = new Loop();

        while (loop.round < maxRounds) {
            loop.round++;

            // Out of wall-clock budget: answer with what is already gathered
            // instead of starting another round the client will never see.
            if (deadline.expired()) {
                log.warn("Review budget exhausted after {} rounds / {} tool calls",
                        loop.round - 1, loop.toolCalls);
                messages.add(Message.user("审查时间预算已用尽，请立即输出审查结果 JSON，不要调用工具。"));
                return finish(forceComplete(messages, loop), loop);
            }

            // Token budget: compress if over 80%
            if (estimateChars(messages) > maxTotalChars * COMPRESS_AT) {
                messages = compressToolResults(messages);
            }
            // Still over limit after compression → force finish
            if (estimateChars(messages) > maxTotalChars) {
                messages.add(Message.user("上下文已满，请立即输出审查结果 JSON，不要调用工具。"));
                return finish(forceComplete(messages, loop), loop);
            }

            // Convergence: stop handing out tools once the budget is spent
            boolean giveTools = loop.toolCalls < TOOL_CALL_LIMIT;
            List<Tool> tools = giveTools ? toolRegistry.getDefinitions() : List.of();

            AgentResponse resp = chat(messages, tools, loop);
            log.debug("ReAct round {}: toolCalls={} contentLen={}",
                    loop.round, resp.hasToolCalls(), resp.content().length());

            if (resp.hasToolCalls()) {
                // Add the assistant message with tool_calls first (required by API spec)
                messages.add(Message.assistant(resp.content(), resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    // Each tool call is further GitHub I/O, so the budget is
                    // checked per call and not only between rounds.
                    String result = deadline.expired()
                            ? "上下文时间预算已用尽，无法再执行工具。请立即输出最终审查结果 JSON。"
                            : toolRegistry.execute(call, pr);
                    messages.add(Message.tool(call.id(), result));
                    loop.toolCalls++;
                }
                if (loop.toolCalls >= TOOL_CALL_LIMIT && giveTools) {
                    messages.add(Message.user(
                            "已进行 " + loop.toolCalls + " 次工具调用。请立即输出最终审" +
                            "查结果 JSON，不要继续调用工具。"));
                }
                continue;
            }

            // No tool calls → LLM is done, parse result
            return finish(parseReply(resp.content(), messages, loop), loop);
        }

        // Exceeded maxRounds — force completion
        messages.add(Message.user("已达到最大轮次 " + maxRounds + "。请立即输出审查结果 JSON。"));
        return finish(forceComplete(messages, loop), loop);
    }

    /** Force the LLM to output a final result (no tools). */
    private ReviewResult forceComplete(List<Message> messages, Loop loop) {
        AgentResponse resp = chat(messages, List.of(), loop);
        return parseReply(resp.content(), messages, loop);
    }

    private ReviewResult parseReply(String raw, List<Message> messages, Loop loop) {
        return replyReader.read(raw, feedback -> {
            messages.add(Message.assistant(raw, List.of()));
            messages.add(Message.user(feedback));
            return chat(messages, List.of(), loop).content();
        });
    }

    private AgentResponse chat(List<Message> messages, List<Tool> tools, Loop loop) {
        AgentResponse resp = modelProvider.chat(messages, tools);
        loop.promptTokens += resp.promptTokens();
        loop.completionTokens += resp.completionTokens();
        return resp;
    }

    private static AgentReview finish(ReviewResult result, Loop loop) {
        return new AgentReview(result, loop.round, loop.toolCalls,
                loop.promptTokens, loop.completionTokens);
    }

    /** Per-invocation accumulator — never shared between requests. */
    private static final class Loop {
        int round;
        int toolCalls;
        int promptTokens;
        int completionTokens;
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
     * {@value #TOOL_RESULT_COMPRESS_FROM} chars, keep the head and mark the cut.
     */
    private static List<Message> compressToolResults(List<Message> messages) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if ("tool".equals(m.role()) && m.content() != null
                    && m.content().length() > TOOL_RESULT_COMPRESS_FROM) {
                String truncated = m.content().substring(0, TOOL_RESULT_KEEP_CHARS)
                        + "\n[...compressed " + (m.content().length() - TOOL_RESULT_COMPRESS_FROM)
                        + " chars]";
                out.add(Message.tool(m.toolCallId(), truncated));
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
