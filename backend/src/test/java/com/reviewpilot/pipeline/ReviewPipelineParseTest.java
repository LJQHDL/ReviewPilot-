package com.reviewpilot.pipeline;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPipelineParseTest {

    /**
     * The pipeline only constructs collaborators when the public review() entry
     * point is invoked, so for parser-only tests we can pass nulls — none of
     * them get touched by parseModelReply / stripFences.
     */
    private final ReviewPipeline pipeline = new ReviewPipeline(null, null, null, null, null,
            new com.reviewpilot.service.ai.ModelProvider() {
                public String name() { return "stub"; }
                public String complete(String s, String u) { return ""; }
            });

    @Test
    void parses_well_formed_json() {
        String reply = """
                {
                  "summary": "fix login flow",
                  "risks": [
                    {"level": "HIGH", "file": "Login.java", "line": 42, "message": "lock not released"}
                  ],
                  "suggestions": [
                    {"file": "Login.java", "line": 42, "message": "wrap in try/finally"}
                  ]
                }
                """;
        ReviewResult r = pipeline.parseModelReply(reply, "https://github.com/o/r/pull/1");
        assertEquals("fix login flow", r.summary());
        assertEquals(1, r.risks().size());
        assertEquals(RiskLevel.HIGH, r.risks().get(0).level());
        assertEquals(42, r.risks().get(0).line());
        assertEquals(1, r.suggestions().size());
    }

    @Test
    void strips_markdown_code_fence() {
        String reply = """
                ```json
                {"summary":"ok","risks":[],"suggestions":[]}
                ```
                """;
        ReviewResult r = pipeline.parseModelReply(reply, "u");
        assertEquals("ok", r.summary());
        assertTrue(r.risks().isEmpty());
    }

    @Test
    void unknown_level_defaults_to_low() {
        String reply = """
                {"summary":"x","risks":[{"level":"WAT","file":"a","line":1,"message":"hm"}],"suggestions":[]}
                """;
        ReviewResult r = pipeline.parseModelReply(reply, "u");
        assertEquals(RiskLevel.LOW, r.risks().get(0).level());
    }

    @Test
    void invalid_json_falls_back_to_summary() {
        String reply = "Sorry, I can only respond in plain English: this PR looks fine.";
        ReviewResult r = pipeline.parseModelReply(reply, "u");
        // Raw text becomes the summary so the user gets *something*.
        assertTrue(r.summary().contains("looks fine"));
        assertTrue(r.risks().isEmpty());
        assertTrue(r.suggestions().isEmpty());
    }

    @Test
    void blank_message_items_are_dropped() {
        String reply = """
                {"summary":"y","risks":[
                  {"level":"HIGH","file":"a","line":1,"message":""},
                  {"level":"LOW","file":"b","line":2,"message":"keep me"}
                ],"suggestions":[]}
                """;
        ReviewResult r = pipeline.parseModelReply(reply, "u");
        assertEquals(1, r.risks().size());
        assertEquals("keep me", r.risks().get(0).message());
    }

    @Test
    void stripFences_keeps_content_without_fences() {
        assertEquals("{\"a\":1}", ReviewPipeline.stripFences("{\"a\":1}").trim());
    }
}
