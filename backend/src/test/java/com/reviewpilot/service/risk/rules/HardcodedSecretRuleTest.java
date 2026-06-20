package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
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

class HardcodedSecretRuleTest {

    private final HardcodedSecretRule rule = new HardcodedSecretRule();

    @Test
    void flags_password_assignment_with_quoted_value() {
        FileChange f = javaFile("Config.java",
                "+    String password = \"superSecret123\";");
        List<RiskItem> r = rule.scan(f);
        assertEquals(1, r.size());
        assertTrue(r.get(0).message().contains("Hardcoded secret"));
    }

    @Test
    void flags_known_token_prefix() {
        FileChange f = javaFile("App.java",
                "+    String token = \"ghp_abc123456789\";");
        List<RiskItem> r = rule.scan(f);
        assertEquals(1, r.size());
    }

    @Test
    void ignores_plain_variable_assignment_without_secret_key() {
        FileChange f = javaFile("Config.java",
                "+    String host = \"localhost\";");
        List<RiskItem> r = rule.scan(f);
        assertTrue(r.isEmpty());
    }

    @Test
    void ignores_readme_md_file() {
        FileChange f = new FileChange("README.md", "modified", 1, 0, false,
                "@@ -0,0 +1 @@\n+    token: ghp_example",
                List.of(new DiffHunk(1, 1, 0, 1, List.of(
                        new DiffLine(DiffLineType.ADDED, 0, 1, "    token: ghp_example")))));
        List<RiskItem> r = rule.scan(f);
        assertTrue(r.isEmpty(), "should skip non-code files to avoid false positives");
    }

    @Test
    void appliesTo_excludes_test_and_sql() {
        assertFalse(rule.appliesTo(FileType.TEST));
        assertFalse(rule.appliesTo(FileType.SQL));
        assertTrue(rule.appliesTo(FileType.SERVICE));
        assertTrue(rule.appliesTo(FileType.CONTROLLER));
    }

    private static FileChange javaFile(String name, String addedLine) {
        return new FileChange(name, "modified", 1, 0, false,
                "@@ -0,0 +1 @@\n" + addedLine,
                List.of(new DiffHunk(1, 1, 0, 1, List.of(
                        new DiffLine(DiffLineType.ADDED, 0, 1, addedLine)))));
    }
}
