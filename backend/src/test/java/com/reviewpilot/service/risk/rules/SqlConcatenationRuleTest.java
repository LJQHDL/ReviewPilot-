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

class SqlConcatenationRuleTest {

    private final SqlConcatenationRule rule = new SqlConcatenationRule();

    @Test
    void flags_select_string_concatenated_with_identifier() {
        FileChange c = javaChange("UserDao.java",
                added(40, "String sql = \"SELECT * FROM users WHERE id = \" + userId;"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.HIGH, risks.get(0).level());
        assertEquals(40, risks.get(0).line());
    }

    @Test
    void does_not_flag_constant_sql_string_without_concatenation() {
        // Pure constant string — the parameterized variant gets bound elsewhere.
        FileChange c = javaChange("UserDao.java",
                added(40, "String sql = \"SELECT * FROM users WHERE id = ?\";"));

        assertTrue(rule.scan(c).isEmpty());
    }

    @Test
    void does_not_flag_non_java_file() {
        // A .sql file isn't where this rule is meant to fire.
        FileChange c = new FileChange(
                "schema.sql", "modified", 1, 0, false, "patch",
                List.of(new DiffHunk(1, 1, 1, 1,
                        List.of(new DiffLine(DiffLineType.ADDED, 0, 1,
                                "SELECT * FROM users WHERE id = " + "x")))));

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
