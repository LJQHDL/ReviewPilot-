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
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsecureRandomRuleTest {

    private final InsecureRandomRule rule = new InsecureRandomRule();

    @Test
    void flags_new_Random_in_service() {
        FileChange f = javaFile("TokenService.java",
                "+    Random rnd = new Random();");
        List<RiskItem> r = rule.scan(f);
        assertEquals(1, r.size());
        assertTrue(r.get(0).message().contains("SecureRandom"));
    }

    @Test
    void ignores_secureRandom() {
        FileChange f = javaFile("TokenService.java",
                "+    SecureRandom rnd = new SecureRandom();");
        List<RiskItem> r = rule.scan(f);
        assertTrue(r.isEmpty());
    }

    @Test
    void ignores_non_java_files() {
        FileChange f = new FileChange("script.py", "modified", 1, 0, false,
                "@@ -0,0 +1 @@\n+    rnd = Random()",
                List.of(new DiffHunk(1, 1, 0, 1, List.of(
                        new DiffLine(DiffLineType.ADDED, 0, 1, "    rnd = Random()")))));
        List<RiskItem> r = rule.scan(f);
        assertTrue(r.isEmpty());
    }

    private static FileChange javaFile(String name, String addedLine) {
        return new FileChange(name, "modified", 1, 0, false,
                "@@ -0,0 +1 @@\n" + addedLine,
                List.of(new DiffHunk(1, 1, 0, 1, List.of(
                        new DiffLine(DiffLineType.ADDED, 0, 1, addedLine)))));
    }
}
