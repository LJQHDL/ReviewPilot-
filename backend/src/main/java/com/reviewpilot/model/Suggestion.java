package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Actionable suggestion the reviewer should consider. Tied to a file/line where
 * meaningful (line=0 if file-wide or repo-wide).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Suggestion(String file, int line, String message) {
}
