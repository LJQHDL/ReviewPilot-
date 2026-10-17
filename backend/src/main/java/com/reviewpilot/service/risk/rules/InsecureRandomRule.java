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
 * 标记 ADDED 行中的 {@code new Random()}（java.util.Random）——当上下文暗示安全用途
 * （token、session、crypto、password）时更应关注。
 * <p>
 * {@code java.util.Random} 以系统时间播种、极易预测——任何安全敏感场景都不该用它，
 * 正确替代是 {@code java.security.SecureRandom}。
 * <p>
 * MEDIUM 严重度：该行也可能在生成非安全标识符；期望 AI 评审环节结合上下文细化等级。
 */
@Component
public class InsecureRandomRule implements RiskRule {

    @Override
    public String id() {
        return "insecure-random";
    }

    /** 只在业务代码（SERVICE/CONTROLLER）中检查。 */
    @Override
    public boolean appliesTo(FileType type) {
        return type == FileType.SERVICE || type == FileType.CONTROLLER;
    }

    /** 子串匹配 new Random( 即可，无需完整解析表达式。 */
    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();
                if (content.contains("new Random(") || content.contains("new Random()")) {
                    out.add(new RiskItem(
                            RiskLevel.MEDIUM,
                            change.filename(),
                            line.newLine(),
                            "java.util.Random is not cryptographically secure — use java.security.SecureRandom for tokens, sessions, or crypto."));
                }
            }
        }
        return out;
    }
}
