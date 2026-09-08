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
 * 标记硬编码密钥——ADDED 行中形似密码、API Key 或 Token 的字符串字面量。
 * 用模式匹配以便同时抓住赋值（{@code password = "..."}）和配置值两种写法。
 */
@Component
public class HardcodedSecretRule implements RiskRule {

    /** 疑似密钥的字段名清单。 */
    private static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwd", "pwd",
            "secret", "apiKey", "api_key", "apikey",
            "token", "accessToken", "access_token",
            "privateKey", "private_key",
            "authorization");

    /** 键名 = "至少 6 字符的字符串字面量" 赋值模式。 */
    private static final Pattern ASSIGN_PATTERN = Pattern.compile(
            "(?i)(?:" + String.join("|", SECRET_KEYS) + ")\\s*[:=]\\s*\"[^\"]{6,}\"");

    /** 已知厂商密钥前缀（DeepSeek/GitHub/AWS/PEM 私钥头），出现即高可信命中。 */
    private static final Pattern SUSPICIOUS_VALUE = Pattern.compile(
            "(?i)(sk-[a-zA-Z0-9]{8,}|ghp_[a-zA-Z0-9]{8,}|AKIA[A-Z0-9]{16}|"
                    + "-----BEGIN (RSA |EC )?PRIVATE KEY-----)");

    @Override
    public String id() {
        return "hardcoded-secret";
    }

    /** 测试与 SQL 文件不扫，避免示例数据造成误报。 */
    @Override
    public boolean appliesTo(FileType type) {
        return type != FileType.TEST && type != FileType.SQL;
    }

    /** 只扫代码/配置文件（Java/yml/yaml/properties/.env），跳过文档中的示例。 */
    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null
                || change.binary() || change.filename() == null) return out;
        // 限定代码/配置文件——避免把文档示例标记为密钥
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
