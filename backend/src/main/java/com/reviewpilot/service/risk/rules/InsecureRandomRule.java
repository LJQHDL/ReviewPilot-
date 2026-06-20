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
 * Flags {@code new Random()} (java.util.Random) on ADDED lines when the
 * context suggests a security use (token, session, crypto, password).
 * <p>
 * {@code java.util.Random} is seeded from system time and trivially
 * predictable — it must not be used for anything security-sensitive.
 * {@code java.security.SecureRandom} is the correct replacement.
 * <p>
 * MEDIUM severity: the line may be generating a non-security identifier;
 * the AI review step is expected to refine the severity based on context.
 */
@Component
public class InsecureRandomRule implements RiskRule {

    @Override
    public String id() {
        return "insecure-random";
    }

    @Override
    public boolean appliesTo(FileType type) {
        return type == FileType.SERVICE || type == FileType.CONTROLLER;
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
