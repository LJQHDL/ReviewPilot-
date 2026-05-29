package com.reviewpilot.model;

/**
 * Risk severity used by both the rule-based RiskDetector (PR#5) and AI output
 * (PR#3). Order: HIGH > MEDIUM > LOW. The frontend renders this as a colored tag.
 */
public enum RiskLevel {
    HIGH,
    MEDIUM,
    LOW
}
