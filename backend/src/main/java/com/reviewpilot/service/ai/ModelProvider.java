package com.reviewpilot.service.ai;

import java.util.List;

/**
 * Abstraction over an LLM chat completion.
 * <p>
 * {@link #complete(String, String)} is the original single-shot interface
 * used by tests and the V2 pipeline. {@link #chat(List, List)} is the V3
 * multi-turn interface that supports tool calling for the ReAct agent loop.
 */
public interface ModelProvider {

    /** Symbolic name (matches reviewpilot.ai.provider in yml). */
    String name();

    /**
     * Run a single completion (V1/V2).
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Multi-turn chat with message history and optional tool definitions (V3).
     * The provider sends the full message list plus tool definitions to the
     * LLM and returns either a text reply or tool calls.
     */
    AgentResponse chat(List<Message> messages, List<Tool> tools);

    /** Model identifier for diagnostics (e.g. "deepseek-chat"). */
    default String modelName() { return null; }
}
