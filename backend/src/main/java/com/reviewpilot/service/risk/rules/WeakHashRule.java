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
 * 标记 ADDED 行中对已被密码学攻破的哈希算法（MD5、SHA-1）的使用。
 * 校验和等非安全场景用它们无妨，因此本规则只报 MEDIUM——
 * 由 AI 评审环节判断该哈希是否用在安全上下文（密码存储、数字签名、证书校验）中。
 */
@Component
public class WeakHashRule implements RiskRule {

    /** 匹配算法名字符串字面量与 MessageDigest.getInstance 两种写法。 */
    private static final Pattern WEAK_HASH = Pattern.compile(
            "(?i)\"(MD5|SHA-1|SHA1)\"|MessageDigest\\.getInstance\\(\"(MD5|SHA-1|SHA1)\"\\)");

    @Override
    public String id() {
        return "weak-hash";
    }

    /** 只在业务代码（SERVICE/CONTROLLER）中检查。 */
    @Override
    public boolean appliesTo(FileType type) {
        return type == FileType.SERVICE || type == FileType.CONTROLLER;
    }

    /** 逐 Java 文件、逐 ADDED 行做正则匹配。 */
    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                if (WEAK_HASH.matcher(line.content()).find()) {
                    out.add(new RiskItem(
                            RiskLevel.MEDIUM,
                            change.filename(),
                            line.newLine(),
                            "MD5/SHA-1 is cryptographically broken — use SHA-256 or SHA-3 for security-sensitive hashing."));
                }
            }
        }
        return out;
    }
}
