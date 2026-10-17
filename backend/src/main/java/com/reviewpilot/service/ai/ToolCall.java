package com.reviewpilot.service.ai;

import java.util.Map;

/**
 * LLM 在 assistant 回复中发起的一次工具调用请求：调用 id、工具名与参数。
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {}
