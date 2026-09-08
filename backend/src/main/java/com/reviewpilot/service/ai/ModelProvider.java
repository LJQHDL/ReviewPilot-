package com.reviewpilot.service.ai;

import java.util.List;

/**
 * LLM 聊天补全的抽象接口，隔离具体模型提供方（当前实现为 DeepSeek）。
 * <p>
 * {@link #complete(String, String)} 是测试与旧 V2 管线使用的单发式接口；
 * {@link #chat(List, List)} 是 V3 多轮接口，支持工具调用，供 ReAct Agent 循环使用。
 */
public interface ModelProvider {

    /** 提供方符号名（与 yml 中 reviewpilot.ai.provider 对应）。 */
    String name();

    /**
     * 单发式补全（V1/V2）：system + user 两条消息，返回纯文本。
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * 多轮聊天（V3）：把完整消息历史和可选工具定义发给 LLM，
     * 返回文本回复或工具调用列表。
     */
    AgentResponse chat(List<Message> messages, List<Tool> tools);

    /** 供诊断展示用的模型标识（如 "deepseek-chat"）。 */
    default String modelName() { return null; }
}
