package com.reviewpilot.service.ai;

import java.util.Map;

/**
 * A tool call requested by the LLM — part of an assistant message's response.
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {}
