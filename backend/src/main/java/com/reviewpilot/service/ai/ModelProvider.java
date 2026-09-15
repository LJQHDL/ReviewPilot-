package com.reviewpilot.service.ai;

import java.util.List;

/**
 * LLM 聊天补全的抽象接口，隔离具体模型协议（当前实现为 OpenAI 兼容协议）。
 * <p>
 * {@link #complete(ModelEndpoint, String, String) complete} 是测试与旧 V2 管线使用的单发式接口；
 * {@link #chat} 是 V3 多轮接口，支持工具调用，供 ReAct Agent 循环使用。
 * <p>
 * 每次调用的端点信息（地址、密钥、模型等）经 {@link ModelEndpoint} 入参传入，provider 自身
 * <em>不持有</em>任何端点状态——这让「端点从哪来」与「如何调用协议」解耦：本次由配置填充，
 * 将来可改为运行时按请求注入，调用方与本接口均无需改动。
 */
public interface ModelProvider {

    /** 协议族符号名（如 "openai-compatible"）。注意：厂商/模型展示改用 {@link ModelEndpoint#name()} 与 {@link ModelEndpoint#model()}，不再依赖此值。 */
    String name();

    /**
     * 单发式补全（V1/V2）：system + user 两条消息，返回纯文本。
     *
     * @param ep 本次调用使用的端点（地址/密钥/模型/生成参数）
     */
    String complete(ModelEndpoint ep, String systemPrompt, String userPrompt);

    /**
     * 多轮聊天（V3）：把完整消息历史和可选工具定义发给 LLM，
     * 返回文本回复或工具调用列表。
     *
     * @param ep 本次调用使用的端点（地址/密钥/模型/生成参数）
     */
    AgentResponse chat(ModelEndpoint ep, List<Message> messages, List<Tool> tools);
}

