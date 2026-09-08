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
 * 标记新增行上的 {@code lock.lock()} / {@code .acquire()} 调用，且其所在 hunk 中
 * 没有对应的 {@code unlock()} / {@code release()}。这是启发式：不追踪控制流，
 * 因此只抓最显眼的"加锁没有 finally"气味——若配对解锁存在，本应出现在同一 hunk 里。
 *
 * <p>HIGH 严重度——未释放的锁会在竞争下死锁生产环境，
 * 是最不能被无声上线的隐患之一。
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

    /** 先整 hunk 排除含 unlock/release 的，再逐行报告加锁调用。 */
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

    /** 在 hunk 中找针（新增行与上下文行都算，被删的不算）。 */
    private static boolean hunkContains(DiffHunk hunk, String needle) {
        for (DiffLine l : hunk.lines()) {
            if (l.type() != DiffLineType.REMOVED && l.content().contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
