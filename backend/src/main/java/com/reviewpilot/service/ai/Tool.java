package com.reviewpilot.service.ai;

import java.util.Map;

/**
 * 暴露给 LLM 的工具定义，{@code parameters} 为描述工具入参的 JSON Schema 对象。
 */
/** AI 工具定义数据类，描述工具及其参数结构。 */
public record Tool(String name, String description, Map<String, Object> parameters) {}
