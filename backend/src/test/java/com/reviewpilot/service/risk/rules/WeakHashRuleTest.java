package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeakHashRuleTest {

    private final WeakHashRule rule = new WeakHashRule();

    @Test
    void flags_MD5_MessageDigest() {
        FileChange f = javaFile("HashUtil.java",
                "+    MessageDigest md = MessageDigest.getInstance(\"MD5\");");
        List<RiskItem> r = rule.scan(f);
        assertEquals(1, r.size());
        assertTrue(r.get(0).message().contains("MD5"));
    }

    @Test
    void flags_SHA1_MessageDigest() {
        FileChange f = javaFile("HashUtil.java",
                "+    MessageDigest md = MessageDigest.getInstance(\"SHA-1\");");
        List<RiskItem> r = rule.scan(f);
        assertEquals(1, r.size());
    }

    @Test
    void ignores_SHA256() {
        FileChange f = javaFile("HashUtil.java",
                "+    MessageDigest md = MessageDigest.getInstance(\"SHA-256\");");
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
