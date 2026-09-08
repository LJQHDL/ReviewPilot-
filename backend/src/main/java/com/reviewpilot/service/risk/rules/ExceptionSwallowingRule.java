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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标记捕获一种异常却改抛另一种类型的 catch 块——经典的"异常吞换/类型洗白"模式，
 * 它向调用方隐藏了真实原因。
 *
 * <p>具体形态：catch 头附近几行内出现 {@code throw new <不同类型>Exception(...)}。
 * 生产中最常见的例子是 {@code catch (IllegalArgumentException) -> throw new SerializationException}
 * 风格——调用方再也无法区分"入参非法""编码 bug""配置漂移"。
 *
 * <p>与 {@link BareCatchRule} 的区别：那条只报告完全静默的吞掉（无日志/无 throw）；
 * 本条报告异常确实被重抛但类型变了——这是另一种往往更危险的 bug，因为它看起来像在处理异常。
 *
 * <p>若新 throw 把原异常作为 cause 传入（如 {@code throw new X("msg", e)}），
 * 严重度为 MEDIUM（cause 链保住了，只是改了类型）；若丢弃 cause 则为 HIGH——
 * 运行时永久失去可调试性。
 *
 * <p>启发式、只看 patch——不解析 Java。通过以下手段压低误报率：
 *   - 要求 catch 头与 {@code throw new} 位于同一 hunk 的不同 ADDED 行（不报只在上下文里出现的重抛）。
 *   - 要求抛出类型的简单名与被捕获类型不同。
 */
@Component
public class ExceptionSwallowingRule implements RiskRule {

    private static final Pattern CATCH_HEADER = Pattern.compile(
            "catch\\s*\\(\\s*([\\w.]+)\\s+");

    private static final Pattern THROW_NEW = Pattern.compile(
            "throw\\s+new\\s+([\\w.]+)\\s*\\(([^)]*)\\)");

    private static final int LOOK_AHEAD_LINES = 8;   // catch 头之后最多探测的行数

    @Override
    public String id() {
        return "exception-swallowing";
    }

    /** 只扫 Java 文件，逐 hunk 检查。 */
    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;
        if (change.filename() == null || !change.filename().endsWith(".java")) return out;

        for (DiffHunk hunk : change.hunks()) {
            scanHunk(change.filename(), hunk, out);
        }
        return out;
    }

    /** 在 ADDED 的 catch 头之后向前探测 ADDED 的 throw new，类型不同即报告。 */
    private void scanHunk(String filename, DiffHunk hunk, List<RiskItem> out) {
        List<DiffLine> lines = hunk.lines();
        for (int i = 0; i < lines.size(); i++) {
            DiffLine catchLine = lines.get(i);
            if (catchLine.type() != DiffLineType.ADDED) continue;

            Matcher cm = CATCH_HEADER.matcher(catchLine.content());
            if (!cm.find()) continue;
            String caughtType = simpleName(cm.group(1));

            // 在范围内向前寻找位于 ADDED 行的 `throw new X(...)`
            int looked = 0;
            for (int j = i + 1; j < lines.size() && looked < LOOK_AHEAD_LINES; j++) {
                DiffLine probe = lines.get(j);
                if (probe.type() == DiffLineType.REMOVED) continue;
                looked++;
                if (probe.type() != DiffLineType.ADDED) continue;

                Matcher tm = THROW_NEW.matcher(probe.content());
                if (!tm.find()) continue;
                String thrownType = simpleName(tm.group(1));
                if (thrownType.equals(caughtType)) continue;

                // cause 是否随新异常传递，决定 MEDIUM 还是 HIGH
                boolean preservesCause = mentionsExceptionVar(catchLine.content(), tm.group(2));
                RiskLevel level = preservesCause ? RiskLevel.MEDIUM : RiskLevel.HIGH;
                String suffix = preservesCause
                        ? "cause is preserved but the exception type is re-mapped — callers can no longer distinguish the original failure mode."
                        : "cause is dropped (no `e` passed to the new exception) — the original stack trace and message are lost at runtime.";
                out.add(new RiskItem(
                        level,
                        filename,
                        catchLine.newLine(),
                        "Catch of " + caughtType + " re-thrown as " + thrownType + ": " + suffix));
                break;
            }
        }
    }

    /** 判断 throw-new 的参数列表是否引用了被捕获的异常变量（即 cause 是否保留）。 */
    private static boolean mentionsExceptionVar(String catchHeader, String throwArgs) {
        // 粗略做法：从类型后抽出变量名，再看它是否出现在参数里。
        // catch (FooException e) -> "e"；catch (FooException ex) -> "ex"
        Matcher m = Pattern.compile("catch\\s*\\(\\s*[\\w.]+\\s+(\\w+)\\s*\\)").matcher(catchHeader);
        if (!m.find()) return false;
        String var = m.group(1);
        // 要求该变量作为独立实参出现，而不是别的标识符的一部分
        return Pattern.compile("(^|[^\\w])" + Pattern.quote(var) + "([^\\w]|$)").matcher(throwArgs).find();
    }

    /** 取全限定名的简单类名（最后一个 '.' 之后）。 */
    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
