package com.reviewpilot.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewResultTest {
    @Test
    void capturesImmutableReviewSnapshot() {
        var risk = new RiskItem(RiskLevel.HIGH, "A.java", 1, "risk");
        var suggestion = new Suggestion("A.java", 1, "fix");
        var risks = new ArrayList<>(List.of(risk));
        var suggestions = new ArrayList<>(List.of(suggestion));
        var findings = new ArrayList<>(List.of("finding"));
        var result = new ReviewResult("url", "summary", risks, suggestions, findings, null);

        risks.clear();
        suggestions.clear();
        findings.clear();

        assertAll(
                () -> assertEquals(List.of(risk), result.risks()),
                () -> assertEquals(List.of(suggestion), result.suggestions()),
                () -> assertEquals(List.of("finding"), result.keyFindings()),
                () -> assertThrows(UnsupportedOperationException.class, () -> result.risks().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> result.suggestions().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> result.keyFindings().clear()));
    }

    @Test
    void preservesEmptyCollectionsAndJsonContract() throws Exception {
        var mapper = new ObjectMapper();
        var result = mapper.readValue("{\"prUrl\":\"url\",\"summary\":\"summary\"}", ReviewResult.class);
        assertEquals(mapper.readTree("""
                {"prUrl":"url","summary":"summary","risks":[],"suggestions":[],"keyFindings":[]}
                """), mapper.valueToTree(result));
        var populated = new ReviewResult("url", "summary",
                List.of(new RiskItem(RiskLevel.HIGH, "A.java", 1, "risk")),
                List.of(new Suggestion("A.java", 1, "fix")), List.of("finding"),
                new ReviewResult.Meta("provider", "model", 1, 10));
        assertEquals(populated, mapper.readValue(mapper.writeValueAsString(populated), ReviewResult.class));
    }
}
