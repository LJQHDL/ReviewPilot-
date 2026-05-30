package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemOutPrintlnRuleTest {

    private final SystemOutPrintlnRule rule = new SystemOutPrintlnRule();

    @Test
    void flags_system_out_println_in_added_line() {
        FileChange c = javaChange("Foo.java",
                added(50, "System.out.println(\"debug: \" + value);"));

        List<RiskItem> risks = rule.scan(c);
        assertEquals(1, risks.size());
        assertEquals(RiskLevel.LOW, risks.get(0).level());
    }

    @Test
    void does_not_apply_to_test_files() {
        // Tests legitimately use System.out for debug output.
        assertFalse(rule.appliesTo(FileType.TEST));
        // RiskDetector skips it; even if scan ran directly on a test path, the
        // rule itself stays generic — appliesTo is the gate.
    }

    @Test
    void does_not_flag_logger_call() {
        FileChange c = javaChange("Foo.java",
                added(50, "log.info(\"value={}\", value);"));
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
