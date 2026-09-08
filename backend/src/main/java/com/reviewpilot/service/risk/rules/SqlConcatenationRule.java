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
 * 标记字符串拼接的 SQL——通过 {@code +} 拼接用户可控值来构造查询的新增行，
 * 而不是使用 {@code PreparedStatement} 占位符。典型的 SQL 注入温床。
 *
 * <p>HIGH 严重度。无害 SQL 常量上的少量误报可以接受：
 * 漏掉一条真实注入的代价远高于一条啰嗦的评论。
 */
@Component
public class SqlConcatenationRule implements RiskRule {

    /** 命中条件：一行同时含 SQL 形状的字面量与 {@code "..." +} 拼接。 */
    private static final Pattern SQL_KEYWORD = Pattern.compile(
            "(?i)\\b(select|insert\\s+into|update|delete\\s+from|where)\\b");

    @Override
    public String id() {
        return "sql-concatenation";
    }

    @Override
    public boolean appliesTo(FileType type) {
        // 这类问题通常落在 Java 的 service/repository 代码里；
        // SQL 文件本身不存在 Java 意义上的变量拼接
        return type == FileType.SERVICE || type == FileType.CONTROLLER || type == FileType.OTHER;
    }

    /** 三条件叠加（SQL 关键字 + 引号 + 与标识符的拼接）以降低误报。 */
    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();

                // 启发式：同一行内引号包裹的 SQL 关键字后跟 `+ 标识符`。
                // 既避开纯 SQL 常量，也避开与 SQL 无关的普通字符串拼接
                if (!SQL_KEYWORD.matcher(content).find()) continue;
                if (!content.contains("\"")) continue;
                if (!hasConcatWithIdentifier(content)) continue;

                out.add(new RiskItem(
                        RiskLevel.HIGH,
                        change.filename(),
                        line.newLine(),
                        "SQL built via string concatenation — use a PreparedStatement / parameterized query to avoid injection."));
            }
        }
        return out;
    }

    /** 粗略判断：找到 `" +` 之后是否还有字母/下划线（说明拼的是变量而非另一段字面量）。 */
    private static boolean hasConcatWithIdentifier(String content) {
        // crude: find a `" +` followed eventually by an identifier char.
        int idx = content.indexOf("\" +");
        if (idx < 0) idx = content.indexOf("\"+");
        if (idx < 0) return false;
        for (int i = idx + 2; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isLetter(ch) || ch == '_') return true;
        }
        return false;
    }
}
