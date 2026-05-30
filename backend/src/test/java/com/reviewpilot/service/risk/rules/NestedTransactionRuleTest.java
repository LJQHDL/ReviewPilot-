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

class NestedTransactionRuleTest {

    private final NestedTransactionRule rule = new NestedTransactionRule();

    @Test
    void flags_self_invocation_inside_transactional_method() {
        // @Transactional + this.foo() in the same hunk → Spring proxy bypass.
        FileChange c = javaChange("OrderService.java",
                added(20, "@Transactional"),
                added(21, "public void place() {"),
                added(22, "    this.charge(order);"),
                added(23, "}"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.HIGH, risks.get(0).level());
        assertEquals(22, risks.get(0).line());
    }

    @Test
    void does_not_flag_when_no_transactional_annotation_in_hunk() {
        // Plain self-call without @Transactional in the hunk — no concern.
        FileChange c = javaChange("OrderService.java",
                added(21, "public void place() {"),
                added(22, "    this.charge(order);"),
                added(23, "}"));

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
