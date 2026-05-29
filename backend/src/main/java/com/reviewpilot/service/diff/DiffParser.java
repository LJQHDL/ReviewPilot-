package com.reviewpilot.service.diff;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff {@code patch} (as returned by GitHub's PR files API)
 * into a list of {@link DiffHunk}s with line-number annotations.
 * <p>
 * The parser intentionally accepts only the hunk-level subset GitHub emits:
 * a sequence of {@code @@ ... @@} headers followed by lines beginning with
 * {@code ' '}, {@code '+'} or {@code '-'}. File-level headers
 * ({@code diff --git}, {@code --- a/...}, {@code +++ b/...}) are not present in
 * the per-file {@code patch} field, so we don't try to handle them here.
 * "{@code \ No newline at end of file}" markers are silently skipped.
 */
@Component
public class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public List<DiffHunk> parse(String patch) {
        if (patch == null || patch.isEmpty()) {
            return Collections.emptyList();
        }

        List<DiffHunk> hunks = new ArrayList<>();
        // Split keeping empty trailing entries so a hunk that ends with a blank
        // context/added line is preserved verbatim.
        String[] lines = patch.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            Matcher m = HUNK_HEADER.matcher(lines[i]);
            if (!m.matches()) {
                i++;
                continue;
            }
            int oldStart = Integer.parseInt(m.group(1));
            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));

            List<DiffLine> body = new ArrayList<>();
            int oldLine = oldStart;
            int newLine = newStart;
            i++;

            while (i < lines.length && !lines[i].startsWith("@@")) {
                String raw = lines[i];
                if (raw.startsWith("\\")) {
                    // "\ No newline at end of file" marker — skip without advancing line counters.
                    i++;
                    continue;
                }
                if (raw.isEmpty()) {
                    // Some tools emit an empty line as a context line. Treat it as such.
                    body.add(new DiffLine(DiffLineType.CONTEXT, oldLine, newLine, ""));
                    oldLine++;
                    newLine++;
                    i++;
                    continue;
                }
                char prefix = raw.charAt(0);
                String content = raw.substring(1);
                switch (prefix) {
                    case '+' -> {
                        body.add(new DiffLine(DiffLineType.ADDED, 0, newLine, content));
                        newLine++;
                    }
                    case '-' -> {
                        body.add(new DiffLine(DiffLineType.REMOVED, oldLine, 0, content));
                        oldLine++;
                    }
                    case ' ' -> {
                        body.add(new DiffLine(DiffLineType.CONTEXT, oldLine, newLine, content));
                        oldLine++;
                        newLine++;
                    }
                    default -> {
                        // Unknown prefix — bail out of this hunk to avoid mis-attributing line numbers.
                        i = lines.length;
                    }
                }
                i++;
            }
            hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, body));
        }
        return hunks;
    }
}
