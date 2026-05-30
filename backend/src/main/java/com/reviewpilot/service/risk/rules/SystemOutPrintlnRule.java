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

/**
 * Flags {@code System.out.println} / {@code System.err.println} on added Java
 * lines outside test code. Production code should go through a logger; raw
 * stdout writes are usually a forgotten {@code printf}-debug.
 *
 * <p>LOW severity — it's a code-quality nudge, not a correctness bug.
 */
@Component
public class SystemOutPrintlnRule implements RiskRule {

    @Override
    public String id() {
        return "system-out-println";
    }

    @Override
    public boolean appliesTo(FileType type) {
        // Tests legitimately use System.out for debug output — skip them.
        return type != FileType.TEST;
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
                if (content.contains("System.out.println") || content.contains("System.err.println")
                        || content.contains("System.out.print") || content.contains("System.err.print")) {
                    out.add(new RiskItem(
                            RiskLevel.LOW,
                            change.filename(),
                            line.newLine(),
                            "Avoid System.out/err in production code — use the project logger so output respects log levels and routing."));
                }
            }
        }
        return out;
    }
}
