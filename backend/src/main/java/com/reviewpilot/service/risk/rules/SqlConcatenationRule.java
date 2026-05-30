package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import com.reviewpilot.service.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Flags string-concatenated SQL — added lines that build a SQL query by
 * concatenating user-controlled values via {@code +}, instead of using a
 * {@code PreparedStatement} placeholder. Classic SQL injection setup.
 *
 * <p>HIGH severity. We accept some false positives on innocuous SQL strings;
 * the cost of missing one of these is much higher than the cost of a noisy
 * comment.
 */
@Component
public class SqlConcatenationRule implements RiskRule {

    /** A line that declares/assigns a SQL-shaped string AND does {@code "..." +} concatenation. */
    private static final Pattern SQL_KEYWORD = Pattern.compile(
            "(?i)\\b(select|insert\\s+into|update|delete\\s+from|where)\\b");

    @Override
    public String id() {
        return "sql-concatenation";
    }

    @Override
    public boolean appliesTo(FileType type) {
        // Java service/repository code is where this typically lands. SQL files
        // themselves don't have variable concatenation in the Java sense.
        return type == FileType.SERVICE || type == FileType.CONTROLLER || type == FileType.OTHER;
    }

    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();

                // Heuristic: a quoted SQL keyword followed by `+ <identifier>` on
                // the same line. Avoids flagging plain SQL constants and avoids
                // flagging string concatenation that has nothing to do with SQL.
                if (!SQL_KEYWORD.matcher(content).find()) continue;
                if (!content.contains("\"")) continue;
                if (!hasConcatWithIdentifier(content)) continue;

                out.add(new RiskItem(
                        RiskLevel.HIGH,
                        change.filename(),
                        line.newLine(),
                        "SQL built via string concatenation — use a PreparedStatement / parameterized query to avoid injection."));
            }
        }
        return out;
    }

    private static boolean hasConcatWithIdentifier(String content) {
        // crude: find a `" +` followed eventually by an identifier char.
        int idx = content.indexOf("\" +");
        if (idx < 0) idx = content.indexOf("\"+");
        if (idx < 0) return false;
        for (int i = idx + 2; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isLetter(ch) || ch == '_') return true;
        }
        return false;
    }
}
