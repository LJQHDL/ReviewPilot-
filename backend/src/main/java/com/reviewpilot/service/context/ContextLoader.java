package com.reviewpilot.service.context;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Pulls a small window of code around each rule-detected risk so the AI sees
 * the surrounding lines, not just the diff hunk header.
 *
 * <p>We deliberately re-use what's already in {@code FileChange.hunks()}
 * rather than calling GitHub's raw-content API. The diff itself includes ~3
 * lines of context above and below each change, which is enough to anchor the
 * AI's reasoning. Adding a second HTTP call would double network latency for
 * almost no extra signal — and would force authentication for private repos.
 *
 * <p>Algorithm: for each risk, find the new-file line in the file's hunks,
 * collect ±{@link #WINDOW} surrounding lines, then merge slices that overlap
 * or touch (so two adjacent risks share one snippet instead of producing
 * duplicates). Risks whose line we can't locate are skipped — there's no
 * useful fallback.
 */
@Component
public class ContextLoader {

    /** How many surrounding lines to include on each side of a risk line. */
    static final int WINDOW = 5;

    /**
     * Returns one {@link ContextSlice} per merged window. The slices preserve
     * file order from the input list and are sorted by {@code startLine}
     * within each file so the prompt reads top-to-bottom.
     */
    public List<ContextSlice> load(List<FileChange> files, List<RiskItem> risks) {
        if (files == null || files.isEmpty() || risks == null || risks.isEmpty()) {
            return List.of();
        }

        Map<String, TreeSet<Integer>> linesByFile = groupRiskLines(risks);
        if (linesByFile.isEmpty()) return List.of();

        List<ContextSlice> out = new ArrayList<>();
        for (FileChange file : files) {
            TreeSet<Integer> lines = linesByFile.get(file.filename());
            if (lines == null || lines.isEmpty()) continue;
            out.addAll(slicesFor(file, lines));
        }
        return out;
    }

    private static Map<String, TreeSet<Integer>> groupRiskLines(List<RiskItem> risks) {
        Map<String, TreeSet<Integer>> map = new HashMap<>();
        for (RiskItem r : risks) {
            if (r == null || r.file() == null || r.file().isBlank() || r.line() <= 0) continue;
            map.computeIfAbsent(r.file(), k -> new TreeSet<>()).add(r.line());
        }
        return map;
    }

    private static List<ContextSlice> slicesFor(FileChange file, TreeSet<Integer> riskLines) {
        // Flatten new-file content from hunks into a {newLine -> rendered display line} map.
        Map<Integer, String> displayByLine = new HashMap<>();
        if (file.hunks() != null) {
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.type() == DiffLineType.REMOVED) continue;
                    int n = line.newLine();
                    if (n <= 0) continue;
                    char marker = line.type() == DiffLineType.ADDED ? '+' : ' ';
                    displayByLine.put(n, String.format("%c %4d: %s", marker, n, line.content()));
                }
            }
        }
        if (displayByLine.isEmpty()) return List.of();

        List<int[]> windows = new ArrayList<>();
        for (int risk : riskLines) {
            int from = Math.max(1, risk - WINDOW);
            int to = risk + WINDOW;
            if (!windows.isEmpty() && from <= windows.get(windows.size() - 1)[1] + 1) {
                // Adjacent or overlapping window — merge into the previous one.
                windows.get(windows.size() - 1)[1] = Math.max(windows.get(windows.size() - 1)[1], to);
            } else {
                windows.add(new int[]{from, to});
            }
        }

        List<ContextSlice> result = new ArrayList<>();
        for (int[] w : windows) {
            List<String> rendered = new ArrayList<>();
            int actualStart = -1, actualEnd = -1;
            for (int n = w[0]; n <= w[1]; n++) {
                String s = displayByLine.get(n);
                if (s == null) continue;
                if (actualStart < 0) actualStart = n;
                actualEnd = n;
                rendered.add(s);
            }
            if (!rendered.isEmpty()) {
                result.add(new ContextSlice(file.filename(), actualStart, actualEnd, rendered));
            }
        }
        return result;
    }
}
