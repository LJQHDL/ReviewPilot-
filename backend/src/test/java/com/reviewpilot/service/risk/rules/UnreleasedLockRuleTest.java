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

class UnreleasedLockRuleTest {

    private final UnreleasedLockRule rule = new UnreleasedLockRule();

    @Test
    void flags_lock_without_unlock_in_same_hunk() {
        // Added .lock() with no unlock anywhere in the hunk.
        FileChange c = javaChange("Worker.java",
                added(10, "lock.lock();"),
                added(11, "doWork();"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.HIGH, risks.get(0).level());
        assertEquals(10, risks.get(0).line());
    }

    @Test
    void does_not_flag_when_unlock_present_in_hunk() {
        // The classic try/finally pattern — unlock visible, no finding.
        FileChange c = javaChange("Worker.java",
                added(10, "lock.lock();"),
                added(11, "try { doWork(); } finally { lock.unlock(); }"));

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
