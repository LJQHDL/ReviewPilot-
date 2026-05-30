package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
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
 * Flags {@code catch (Exception ...)} or {@code catch (Throwable ...)} blocks
 * that swallow the exception silently — i.e. the catch line has no logger call
 * or {@code throw} in the same hunk after it. A common production foot-gun:
 * the error vanishes and the failure mode shows up much later as missing data.
 *
 * <p>MEDIUM severity — bare catches sometimes are intentional (cleanup paths),
 * so we flag without escalating.
 */
@Component
public class BareCatchRule implements RiskRule {

    private static final Pattern BARE_CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(Exception|Throwable|RuntimeException)\\b");

    @Override
    public String id() {
        return "bare-catch";
    }

    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            List<DiffLine> lines = hunk.lines();
            for (int i = 0; i < lines.size(); i++) {
                DiffLine line = lines.get(i);
                if (line.type() != DiffLineType.ADDED) continue;
                if (!BARE_CATCH.matcher(line.content()).find()) continue;

                if (!followedByHandling(lines, i)) {
                    out.add(new RiskItem(
                            RiskLevel.MEDIUM,
                            change.filename(),
                            line.newLine(),
                            "Bare catch of Exception/Throwable with no log or rethrow nearby — failure will be silently swallowed."));
                }
            }
        }
        return out;
    }

    /**
     * Look at the next ~5 added/context lines after a catch header for any
     * sign the exception is being handled (logger call, throw, return after
     * recording). If none, treat it as silent swallow.
     */
    private static boolean followedByHandling(List<DiffLine> lines, int startIdx) {
        int looked = 0;
        for (int j = startIdx + 1; j < lines.size() && looked < 5; j++) {
            DiffLine l = lines.get(j);
            if (l.type() == DiffLineType.REMOVED) continue;
            String c = l.content();
            looked++;
            if (c.contains("log.") || c.contains("logger.") || c.contains("LOG.")
                    || c.contains("throw ") || c.contains("printStackTrace")) {
                return true;
            }
        }
        return false;
    }
}
