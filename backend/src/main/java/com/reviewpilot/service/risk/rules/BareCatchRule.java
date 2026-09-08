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
 * 标记 {@code catch (Exception ...)} / {@code catch (Throwable ...)} 且静默吞掉异常的代码——
 * 即 catch 行之后的同一 hunk 内没有日志调用或 {@code throw}。常见的生产隐患：
 * 错误凭空消失，故障很久之后才以数据缺失的形式暴露。
 *
 * <p>MEDIUM 严重度——裸 catch 有时是刻意为之（清理路径），因此只标记不升级。
 */
@Component
public class BareCatchRule implements RiskRule {

    private static final Pattern BARE_CATCH = Pattern.compile(
            "catch\\s*\\(\\s*(Exception|Throwable|RuntimeException)\\b");

    @Override
    public String id() {
        return "bare-catch";
    }

    /** 只扫 Java 文件的 ADDED 行；命中裸 catch 且后文无处理痕迹则报一条风险。 */
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
     * 查看 catch 头之后约 5 行新增/上下文行，寻找异常被处理的任何迹象
     * （日志调用、throw、记录后 return）。一处都没有则判定为静默吞异常。
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
