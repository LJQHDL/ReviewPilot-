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
 * Flags use of cryptographically broken hash algorithms (MD5, SHA-1) on
 * ADDED lines. These are fine for checksums and non-security use, so the
 * rule fires at MEDIUM — the AI review step is expected to determine
 * whether the hash is used in a security context (password storage, digital
 * signatures, certificate verification).
 */
@Component
public class WeakHashRule implements RiskRule {

    private static final Pattern WEAK_HASH = Pattern.compile(
            "(?i)\"(MD5|SHA-1|SHA1)\"|MessageDigest\\.getInstance\\(\"(MD5|SHA-1|SHA1)\"\\)");

    @Override
    public String id() {
        return "weak-hash";
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
