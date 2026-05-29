package com.reviewpilot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One identified risk in a PR. {@code line} is the new-file line number when
 * available (0 if not file/line specific).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RiskItem(RiskLevel level, String file, int line, String message) {
}
