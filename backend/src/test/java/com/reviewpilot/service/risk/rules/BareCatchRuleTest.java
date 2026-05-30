package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BareCatchRuleTest {

    private final BareCatchRule rule = new BareCatchRule();

    @Test
    void flags_bare_catch_with_silent_swallow() {
        // catch (Exception e) followed by nothing but a closing brace — swallowed.
        FileChange c = javaChange("Foo.java",
                added(30, "} catch (Exception e) {"),
                added(31, "    // ignored"),
                added(32, "}"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.MEDIUM, risks.get(0).level());
        assertEquals(30, risks.get(0).line());
    }

    @Test
    void does_not_flag_when_logger_call_follows() {
        // The exception is logged — not silent. No finding.
        FileChange c = javaChange("Foo.java",
                added(30, "} catch (Exception e) {"),
                added(31, "    log.warn(\"failed\", e);"),
                added(32, "    throw e;"),
                added(33, "}"));

        assertTrue(rule.scan(c).isEmpty());
    }

    private static FileChange javaChange(String name, DiffLine... lines) {
        DiffHunk hunk = new DiffHunk(1, lines.length, 1, lines.length, List.of(lines));
        return new FileChange(name, "modified", lines.length, 0, false, "patch", List.of(hunk));
    }

    private static DiffLine added(int newLine, String content) {
        return new DiffLine(DiffLineType.ADDED, 0, newLine, content);
    }
}
