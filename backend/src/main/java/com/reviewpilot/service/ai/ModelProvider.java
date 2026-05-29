package com.reviewpilot.service.ai;

/**
 * Abstraction over an LLM chat completion. Each provider takes a system + user
 * message pair and returns the model's text response.
 * <p>
 * Kept deliberately small so swapping DeepSeek for Claude/OpenAI/Ollama is a
 * single-file change. PR#3 ships {@code DeepSeekProvider}; later providers can
 * be plugged in by adding a {@code @Component} and switching
 * {@code reviewpilot.ai.provider} in application.yml.
 */
public interface ModelProvider {

    /** Symbolic name (matches reviewpilot.ai.provider in yml). */
    String name();

    /**
     * Run a single completion.
     *
     * @param systemPrompt instructions defining the assistant's role/output schema
     * @param userPrompt   the actual review payload (built by PromptBuilder)
     * @return model's reply text (NOT the raw HTTP body)
     */
    String complete(String systemPrompt, String userPrompt);
}
