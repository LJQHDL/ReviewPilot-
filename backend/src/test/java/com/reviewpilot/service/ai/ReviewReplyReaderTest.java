package com.reviewpilot.service.ai;

import com.reviewpilot.model.ReviewResult;
import com.reviewpilot.model.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewReplyReaderTest {

    private final ReviewReplyReader reader = new ReviewReplyReader();

    @Test
    void repaired_review_keeps_all_fields_and_only_retries_once() {
        var repairs = new java.util.concurrent.atomic.AtomicInteger();
        ReviewResult result = reader.read("broken", feedback -> {
            assertTrue(feedback.contains("Parser error"));
            repairs.incrementAndGet();
            return """
                    {"summary":"fixed","keyFindings":["important"],
                     "risks":[{"file":"A.java","line":7,"level":"HIGH","message":"risk"}],
                     "suggestions":[{"file":"A.java","line":7,"message":"fix"}]}
                    """;
        });
        assertEquals(1, repairs.get());
        assertEquals("fixed", result.summary());
        assertEquals(java.util.List.of("important"), result.keyFindings());
        assertEquals(7, result.risks().get(0).line());
        assertEquals("fix", result.suggestions().get(0).message());
    }

    @Test
    void valid_reply_does_not_invoke_repair() {
        ReviewResult result = reader.read("{\"summary\":\"ok\"}", feedback -> {
            throw new AssertionError("Valid JSON must not incur another model call");
        });
        assertEquals("ok", result.summary());
    }

    @Test
    void empty_or_non_object_reply_uses_format_repair() {
        for (String raw : java.util.List.of("", "null", "[]")) {
            assertEquals("repaired", reader.read(raw,
                    feedback -> "{\"summary\":\"repaired\"}").summary());
        }
    }

    @Test
    void transport_failure_during_repair_is_not_swallowed() {
        var failure = new AiProviderException("upstream unavailable");
        assertEquals(failure, org.junit.jupiter.api.Assertions.assertThrows(AiProviderException.class,
                () -> reader.read("bad", feedback -> { throw failure; })));
    }

    @Test
    void parses_well_formed_json() throws Exception {
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
        ReviewResult r = reader.parse(reply);
        assertEquals("fix login flow", r.summary());
        assertEquals(1, r.risks().size());
        assertEquals(RiskLevel.HIGH, r.risks().get(0).level());
        assertEquals(42, r.risks().get(0).line());
        assertEquals(1, r.suggestions().size());
    }

    @Test
    void strips_markdown_code_fence() throws Exception {
        String reply = """
                ```json
                {"summary":"ok","risks":[],"suggestions":[]}
                ```
                """;
        ReviewResult r = reader.parse(reply);
        assertEquals("ok", r.summary());
        assertTrue(r.risks().isEmpty());
    }

    @Test
    void unknown_level_defaults_to_low() throws Exception {
        String reply = """
                {"summary":"x","risks":[{"level":"WAT","file":"a","line":1,"message":"hm"}],"suggestions":[]}
                """;
        ReviewResult r = reader.parse(reply);
        assertEquals(RiskLevel.LOW, r.risks().get(0).level());
    }

    @Test
    void invalid_json_retries_once_then_returns_summary_fallback() {
        java.util.concurrent.atomic.AtomicInteger repairs = new java.util.concurrent.atomic.AtomicInteger();
        ReviewResult r = reader.read("not JSON", feedback -> {
            repairs.incrementAndGet();
            return "still not JSON";
        });
        assertEquals(1, repairs.get());
        assertTrue(r.summary().contains("Parse error"));
        assertTrue(r.risks().isEmpty());
        assertTrue(r.suggestions().isEmpty());
    }

    @Test
    void blank_message_items_are_dropped() throws Exception {
        String reply = """
                {"summary":"y","risks":[
                  {"level":"HIGH","file":"a","line":1,"message":""},
                  {"level":"LOW","file":"b","line":2,"message":"keep me"}
                ],"suggestions":[]}
                """;
        ReviewResult r = reader.parse(reply);
        assertEquals(1, r.risks().size());
        assertEquals("keep me", r.risks().get(0).message());
    }

    @Test
    void stripFences_keeps_content_without_fences() throws Exception {
        assertEquals("{\"a\":1}", com.reviewpilot.service.ai.JsonReplyCleaner.stripFences("{\"a\":1}").trim());
    }

    @Test
    void parses_json_with_leading_explanatory_prose() throws Exception {
        // Models sometimes preface the JSON with "Here is the JSON:" or similar.
        // We must still recover the structured risks/suggestions, not fall back
        // to dumping the whole reply as summary text.
        String reply = """
                Here is the JSON you asked for:
                {"summary":"all good","risks":[{"level":"HIGH","file":"a","line":1,"message":"x"}],"suggestions":[]}
                """;
        ReviewResult r = reader.parse(reply);

        assertEquals("all good", r.summary());
        assertEquals(1, r.risks().size());
        assertEquals(RiskLevel.HIGH, r.risks().get(0).level());
        assertEquals("x", r.risks().get(0).message());
    }

    @Test
    void parses_json_with_trailing_prose_after_closing_brace() throws Exception {
        String reply = """
                {"summary":"ok","risks":[],"suggestions":[]}
                Hope this helps!
                """;
        ReviewResult r = reader.parse(reply);
        assertEquals("ok", r.summary());
        assertTrue(r.risks().isEmpty());
    }

    @Test
    void parses_json_wrapped_in_fence_AND_leading_prose() throws Exception {
        // Worst case: prose + fence + JSON. Both layers must peel off.
        String reply = """
                Sure, here you go:
                ```json
                {"summary":"combo","risks":[],"suggestions":[]}
                ```
                """;
        ReviewResult r = reader.parse(reply);
        assertEquals("combo", r.summary());
    }

    @Test
    void extractJsonObject_returns_input_when_no_braces() throws Exception {
        // Reply with no JSON at all should pass through unchanged so the
        // parser produces a clear error and we fall back to text summary.
        assertEquals("not json at all", com.reviewpilot.service.ai.JsonReplyCleaner.extractJsonObject("not json at all"));
    }
}
