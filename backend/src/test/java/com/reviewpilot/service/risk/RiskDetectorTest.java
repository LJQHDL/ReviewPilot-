package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskDetectorTest {

    private final FileClassifier classifier = new FileClassifier();

    @Test
    void empty_or_null_file_list_returns_empty() {
        RiskDetector detector = new RiskDetector(List.of(stubRule(false)), classifier);
        assertTrue(detector.scan(null).isEmpty());
        assertTrue(detector.scan(List.of()).isEmpty());
    }

    @Test
    void aggregates_findings_across_files_and_rules() {
        RiskRule alwaysFlag = new StubRule("always", true,
                new RiskItem(RiskLevel.LOW, null, 0, "stub finding"));
        RiskRule alsoFlag = new StubRule("also", true,
                new RiskItem(RiskLevel.HIGH, null, 0, "another"));

        RiskDetector detector = new RiskDetector(List.of(alwaysFlag, alsoFlag), classifier);

        // Two java files, each rule should fire on each → 2 × 2 = 4 findings.
        List<FileChange> files = List.of(javaChange("A.java"), javaChange("B.java"));
        List<RiskItem> findings = detector.scan(files);

        assertEquals(4, findings.size());
    }

    @Test
    void respects_rule_appliesTo_filter() {
        // Rule says "I only handle SQL files" — won't fire on a java file.
        RiskRule sqlOnly = new StubRule("sql-only", false,
                new RiskItem(RiskLevel.LOW, null, 0, "sql"));

        RiskDetector detector = new RiskDetector(List.of(sqlOnly), classifier);
        List<RiskItem> findings = detector.scan(List.of(javaChange("A.java")));

        assertTrue(findings.isEmpty());
    }

    @Test
    void single_throwing_rule_does_not_block_others() {
        RiskRule blowsUp = new RiskRule() {
            @Override public String id() { return "boom"; }
            @Override public List<RiskItem> scan(FileChange change) {
                throw new IllegalStateException("oops");
            }
        };
        RiskRule fine = new StubRule("fine", true,
                new RiskItem(RiskLevel.LOW, null, 0, "still works"));

        RiskDetector detector = new RiskDetector(List.of(blowsUp, fine), classifier);
        List<RiskItem> findings = detector.scan(List.of(javaChange("A.java")));

        assertEquals(1, findings.size());
        assertEquals("still works", findings.get(0).message());
    }

    // --- fixtures ---

    private static FileChange javaChange(String name) {
        DiffLine line = new DiffLine(DiffLineType.ADDED, 0, 1, "x");
        DiffHunk hunk = new DiffHunk(1, 1, 1, 1, List.of(line));
        return new FileChange(name, "modified", 1, 0, false, "patch", List.of(hunk));
    }

    private static RiskRule stubRule(boolean applies) {
        return new StubRule("stub", applies);
    }

    private static class StubRule implements RiskRule {
        private final String id;
        private final boolean applies;
        private final RiskItem[] findings;

        StubRule(String id, boolean applies, RiskItem... findings) {
            this.id = id;
            this.applies = applies;
            this.findings = findings;
        }

        @Override public String id() { return id; }
        @Override public boolean appliesTo(FileType type) { return applies; }
        @Override public List<RiskItem> scan(FileChange change) { return List.of(findings); }
    }
}
