package com.reviewpilot.service.context;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextLoaderTest {

    private final ContextLoader loader = new ContextLoader();

    @Test
    void empty_inputs_return_empty() {
        assertTrue(loader.load(null, List.of()).isEmpty());
        assertTrue(loader.load(List.of(), List.of()).isEmpty());
        assertTrue(loader.load(List.of(file("A.java", 1, 10)), List.of()).isEmpty());
    }

    @Test
    void extracts_window_around_risk_line() {
        // Hunk covers new-file lines 1..20. Risk on line 10 → window 5..15.
        FileChange fc = file("A.java", 1, 20);
        RiskItem risk = new RiskItem(RiskLevel.HIGH, "A.java", 10, "msg");

        List<ContextSlice> slices = loader.load(List.of(fc), List.of(risk));

        assertEquals(1, slices.size());
        ContextSlice s = slices.get(0);
        assertEquals(5, s.startLine());
        assertEquals(15, s.endLine());
        assertEquals(11, s.lines().size());
    }

    @Test
    void merges_adjacent_windows_into_single_slice() {
        // Two risks within 2*WINDOW of each other → single merged slice.
        FileChange fc = file("A.java", 1, 30);
        RiskItem r1 = new RiskItem(RiskLevel.HIGH, "A.java", 10, "a");
        RiskItem r2 = new RiskItem(RiskLevel.HIGH, "A.java", 14, "b");

        List<ContextSlice> slices = loader.load(List.of(fc), List.of(r1, r2));

        assertEquals(1, slices.size(), "adjacent windows should merge");
        ContextSlice s = slices.get(0);
        assertEquals(5, s.startLine());
        assertEquals(19, s.endLine());
    }

    @Test
    void keeps_distant_windows_separate() {
        // Far-apart risks → two slices.
        FileChange fc = file("A.java", 1, 100);
        RiskItem r1 = new RiskItem(RiskLevel.HIGH, "A.java", 10, "a");
        RiskItem r2 = new RiskItem(RiskLevel.HIGH, "A.java", 80, "b");

        List<ContextSlice> slices = loader.load(List.of(fc), List.of(r1, r2));

        assertEquals(2, slices.size());
        assertEquals(5, slices.get(0).startLine());
        assertEquals(75, slices.get(1).startLine());
    }

    @Test
    void skips_risk_whose_line_falls_outside_any_hunk() {
        // Hunk covers 1..20 only; a risk on line 999 has no surrounding code in our diff.
        FileChange fc = file("A.java", 1, 20);
        RiskItem risk = new RiskItem(RiskLevel.HIGH, "A.java", 999, "msg");

        assertTrue(loader.load(List.of(fc), List.of(risk)).isEmpty());
    }

    @Test
    void skips_file_level_risks_with_zero_line() {
        FileChange fc = file("A.java", 1, 20);
        RiskItem risk = new RiskItem(RiskLevel.MEDIUM, "A.java", 0, "file-level");

        assertTrue(loader.load(List.of(fc), List.of(risk)).isEmpty());
    }

    private static FileChange file(String name, int from, int to) {
        // Build a single hunk that owns new-file lines [from, to] as CONTEXT lines.
        List<DiffLine> body = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            body.add(new DiffLine(DiffLineType.CONTEXT, n, n, "line " + n));
        }
        DiffHunk hunk = new DiffHunk(from, body.size(), from, body.size(), body);
        return new FileChange(name, "modified", 0, 0, false, "patch", List.of(hunk));
    }
}
