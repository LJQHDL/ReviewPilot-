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
 * ReAct Agent：用"推理 + 行动"多轮循环取代单发 LLM 调用，由 LLM 自主决定调用哪些工具、何时收敛输出最终评审。
 *
 * <p>每次调用的状态都保存在 {@link Loop} 中并随 {@link AgentReview} 返回；
 * Bean 上不累积任何状态，因此单实例可并发服务多个 PR 而计数器互不串扰。
 */
@Service
public class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    static final int TOOL_CALL_LIMIT = 4;                    // 单次评审工具调用上限，达到后引导收敛
    private static final double COMPRESS_AT = 0.8;           // 字符用量超预算 80% 时压缩旧工具结果
    private static final int TOOL_RESULT_COMPRESS_FROM = 2000; // 超过此长度的工具结果才压缩
    private static final int TOOL_RESULT_KEEP_CHARS = 1000;  // 压缩后保留的头部字符数

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

    /** 评审主循环：构造初始消息后反复"chat → 执行工具 → 回填结果"，直到收敛、超预算或达最大轮次。 */
    public AgentReview review(List<FileChange> files,
                              Map<String, FileType> classifications,
                              List<RiskItem> ruleRisks,
                              List<ContextSlice> contexts,
                              String prTitle,
                              PrUrl pr,
                              Deadline deadline) {
        // 初始消息：system 定义评审角色与工具用法，user 携带 diff/规则结果/工具清单
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(promptBuilder.reactSystemPrompt()));
        messages.add(Message.user(promptBuilder.reactUserPrompt(
                files, classifications, ruleRisks, contexts, prTitle,
                toolRegistry.getDefinitions())));

        // 每次调用的局部累加器，绝不放到 Bean 字段上
        Loop loop = new Loop();

        while (loop.round < maxRounds) {
            loop.round++;

            // 墙钟预算耗尽：用已收集的信息立即作答，
            // 不再启动客户端注定等不到的新一轮调用。
            if (deadline.expired()) {
                log.warn("Review budget exhausted after {} rounds / {} tool calls",
                        loop.round - 1, loop.toolCalls);
                messages.add(Message.user("审查时间预算已用尽，请立即输出审查结果 JSON，不要调用工具。"));
                return finish(forceComplete(messages, loop), loop);
            }

            // 字符预算超 80%：先压缩早期工具结果
            if (estimateChars(messages) > maxTotalChars * COMPRESS_AT) {
                messages = compressToolResults(messages);
            }
            // 压缩后仍超限：强制收尾
            if (estimateChars(messages) > maxTotalChars) {
                messages.add(Message.user("上下文已满，请立即输出审查结果 JSON，不要调用工具。"));
                return finish(forceComplete(messages, loop), loop);
            }

            // 收敛策略：工具调用预算用完后不再下发工具定义，逼模型直接给结论
            boolean giveTools = loop.toolCalls < TOOL_CALL_LIMIT;
            List<Tool> tools = giveTools ? toolRegistry.getDefinitions() : List.of();

            AgentResponse resp = chat(messages, tools, loop);
            log.debug("ReAct round {}: toolCalls={} contentLen={}",
                    loop.round, resp.hasToolCalls(), resp.content().length());

            if (resp.hasToolCalls()) {
                // API 规范要求先回填带 tool_calls 的 assistant 消息，再逐条追加 tool 结果
                messages.add(Message.assistant(resp.content(), resp.toolCalls()));
                for (ToolCall call : resp.toolCalls()) {
                    // 每次工具调用都是额外的 GitHub I/O，因此预算按调用逐个检查、
                    // 而不是只在轮次之间检查。
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

            // 无工具调用 → 模型已给出结论，解析为 ReviewResult
            return finish(parseReply(resp.content(), messages, loop), loop);
        }

        // 达到最大轮数仍未收敛——强制收尾（不带工具再问一次）
        messages.add(Message.user("已达到最大轮次 " + maxRounds + "。请立即输出审查结果 JSON。"));
        return finish(forceComplete(messages, loop), loop);
    }

    /** 以"禁用工具"的方式再问一次，强迫模型输出最终结果。 */
    private ReviewResult forceComplete(List<Message> messages, Loop loop) {
        AgentResponse resp = chat(messages, List.of(), loop);
        return parseReply(resp.content(), messages, loop);
    }

    /** 解析回复；失败时把错误反馈追加进对话并让模型重写一次（由 ReplyReader 驱动）。 */
    private ReviewResult parseReply(String raw, List<Message> messages, Loop loop) {
        return replyReader.read(raw, feedback -> {
            messages.add(Message.assistant(raw, List.of()));
            messages.add(Message.user(feedback));
            return chat(messages, List.of(), loop).content();
        });
    }

    /** 统一出口：每次 chat 的真实 token 用量累加进本次调用的 Loop。 */
    private AgentResponse chat(List<Message> messages, List<Tool> tools, Loop loop) {
        AgentResponse resp = modelProvider.chat(messages, tools);
        loop.promptTokens += resp.promptTokens();
        loop.completionTokens += resp.completionTokens();
        return resp;
    }

    /** 把循环内积累的结果与统计打包成一次性返回值。 */
    private static AgentReview finish(ReviewResult result, Loop loop) {
        return new AgentReview(result, loop.round, loop.toolCalls,
                loop.promptTokens, loop.completionTokens);
    }

    /** 单次调用的状态累加器——绝不跨请求共享。 */
    private static final class Loop {
        int round;
        int toolCalls;
        int promptTokens;
        int completionTokens;
    }

    /** 用字符数粗略估算上下文占用，作为 token 预算的代理指标。 */
    private static int estimateChars(List<Message> messages) {
        int n = 0;
        for (Message m : messages) {
            n += m.content() != null ? m.content().length() : 0;
            if (m.toolCalls() != null) n += m.toolCalls().toString().length();
        }
        return n;
    }

    /**
     * 压缩早期工具结果以节省上下文：超过 {@value #TOOL_RESULT_COMPRESS_FROM}
     * 字符的 tool 消息只保留头部 {@value #TOOL_RESULT_KEEP_CHARS} 字符并标注截断量。
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
