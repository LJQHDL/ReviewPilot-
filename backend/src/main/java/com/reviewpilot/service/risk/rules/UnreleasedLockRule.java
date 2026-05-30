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
 * Flags {@code lock.lock()} / {@code .acquire()} calls on added lines whose
 * surrounding hunk has no {@code unlock()} / {@code release()} call. A
 * heuristic: we don't trace control flow, so this only catches the obvious
 * "lock without finally" smell where the matching unlock would have shown up
 * in the same hunk if it existed.
 *
 * <p>HIGH severity — an unreleased lock will deadlock production under
 * contention, which is among the worst foot-guns to ship unnoticed.
 */
@Component
public class UnreleasedLockRule implements RiskRule {

    @Override
    public String id() {
        return "unreleased-lock";
    }

    @Override
    public boolean appliesTo(FileType type) {
        return type == FileType.CONTROLLER || type == FileType.SERVICE || type == FileType.OTHER;
    }

    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;

        for (DiffHunk hunk : change.hunks()) {
            boolean hunkHasUnlock = hunkContains(hunk, ".unlock()") || hunkContains(hunk, ".release()");
            if (hunkHasUnlock) continue;

            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();
                if (content.contains(".lock()") || content.contains(".lockInterruptibly()") || content.contains(".acquire()")) {
                    out.add(new RiskItem(
                            RiskLevel.HIGH,
                            change.filename(),
                            line.newLine(),
                            "Lock acquired without a matching unlock/release in the same hunk — wrap in try/finally."));
                }
            }
        }
        return out;
    }

    private static boolean hunkContains(DiffHunk hunk, String needle) {
        for (DiffLine l : hunk.lines()) {
            if (l.type() != DiffLineType.REMOVED && l.content().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
