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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flags hardcoded secrets — string literals on ADDED lines that look like
 * passwords, API keys, or tokens. Pattern-based so it catches both
 * assignments ({@code password = "..."}) and config values.
 */
@Component
public class HardcodedSecretRule implements RiskRule {

    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwd", "pwd",
            "secret", "apiKey", "api_key", "apikey",
            "token", "accessToken", "access_token",
            "privateKey", "private_key",
            "authorization");

    private static final Pattern ASSIGN_PATTERN = Pattern.compile(
            "(?i)(?:" + String.join("|", SECRET_KEYS) + ")\\s*[:=]\\s*\"[^\"]{6,}\"");

    private static final Pattern SUSPICIOUS_VALUE = Pattern.compile(
            "(?i)(sk-[a-zA-Z0-9]{8,}|ghp_[a-zA-Z0-9]{8,}|AKIA[A-Z0-9]{16}|"
                    + "-----BEGIN (RSA |EC )?PRIVATE KEY-----)");

    @Override
    public String id() {
        return "hardcoded-secret";
    }

    @Override
    public boolean appliesTo(FileType type) {
        return type != FileType.TEST && type != FileType.SQL;
    }

    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null
                || change.binary() || change.filename() == null) return out;
        // Restrict to code/config files — avoid flagging documentation examples.
        if (!change.filename().endsWith(".java")
                && !change.filename().endsWith(".yml")
                && !change.filename().endsWith(".yaml")
                && !change.filename().endsWith(".properties")
                && !change.filename().endsWith(".env")) return out;

        for (DiffHunk hunk : change.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();

                if (ASSIGN_PATTERN.matcher(content).find()) {
                    out.add(new RiskItem(
                            RiskLevel.HIGH,
                            change.filename(),
                            line.newLine(),
                            "Hardcoded secret detected — use environment variables or a secrets manager instead."));
                    continue;
                }
                if (SUSPICIOUS_VALUE.matcher(content).find()) {
                    out.add(new RiskItem(
                            RiskLevel.HIGH,
                            change.filename(),
                            line.newLine(),
                            "Suspected API key or private key in code — use environment variables or a secrets manager."));
                }
            }
        }
        return out;
    }
}
