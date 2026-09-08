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
 * 标记测试代码之外的 Java 新增行中的 {@code System.out.println} / {@code System.err.println}。
 * 生产代码应走日志框架；裸的 stdout 输出多半是忘删的 printf-debug。
 *
 * <p>LOW 严重度——这是代码质量提醒，不是正确性缺陷。
 */
@Component
public class SystemOutPrintlnRule implements RiskRule {

    @Override
    public String id() {
        return "system-out-println";
    }

    @Override
    public boolean appliesTo(FileType type) {
        // 测试代码用 System.out 做调试输出是合理的——跳过
        return type != FileType.TEST;
    }

    /** 子串匹配四种 System.out/err 打印调用。 */
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
