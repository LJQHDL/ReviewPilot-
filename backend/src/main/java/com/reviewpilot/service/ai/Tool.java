package com.reviewpilot.service.ai;

import java.util.Map;

/**
 * Tool definition exposed to the LLM. {@code parameters} is a JSON Schema
 * object describing the tool's arguments.
 */
public record Tool(String name, String description, Map<String, Object> parameters) {

    public Map<String, Object> toApiMap() {
        return Map.of(
                "type", "function",
                "function", Map.of("name", name, "description", description,
                        "parameters", parameters)
        );
    }
}
