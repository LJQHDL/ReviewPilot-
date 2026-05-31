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
 * Flags catch blocks that catch one exception type and re-throw a DIFFERENT
 * type — the classic "exception swallow / type laundering" pattern that hides
 * the real cause from callers.
 *
 * <p>Concrete pattern: within a few lines of a catch header, a {@code throw new
 * <Different>Exception(...)} appears. Most production cases are
 * {@code catch (IllegalArgumentException) -> throw new SerializationException}
 * style — caller can no longer distinguish "bad input" from "encoding bug" from
 * "config drift".
 *
 * <p>Distinct from {@link BareCatchRule}, which only fires for silent swallows
 * (no log/throw at all). This rule fires when the exception IS re-thrown but
 * the type changed, which is a different and often more dangerous bug because
 * it looks like the exception is being handled.
 *
 * <p>If the new throw passes the original exception as cause (e.g.
 * {@code throw new X("msg", e)}), severity is MEDIUM (cause chain preserved,
 * just re-typed). If the cause is dropped, severity is HIGH — debuggability
 * permanently lost at runtime.
 *
 * <p>Heuristic, patch-only — does not parse Java. Tuned for low false-positive
 * rate by:
 *   - Requiring the catch header and the {@code throw new} on different ADDED
 *     lines within the same hunk (no flagging of context-only re-throws).
 *   - Requiring the thrown type's simple name to differ from the caught type.
 */
@Component
public class ExceptionSwallowingRule implements RiskRule {

    private static final Pattern CATCH_HEADER = Pattern.compile(
            "catch\\s*\\(\\s*([\\w.]+)\\s+");

    private static final Pattern THROW_NEW = Pattern.compile(
            "throw\\s+new\\s+([\\w.]+)\\s*\\(([^)]*)\\)");

    private static final int LOOK_AHEAD_LINES = 8;

    @Override
    public String id() {
        return "exception-swallowing";
    }

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

    private void scanHunk(String filename, DiffHunk hunk, List<RiskItem> out) {
        List<DiffLine> lines = hunk.lines();
        for (int i = 0; i < lines.size(); i++) {
            DiffLine catchLine = lines.get(i);
            if (catchLine.type() != DiffLineType.ADDED) continue;

            Matcher cm = CATCH_HEADER.matcher(catchLine.content());
            if (!cm.find()) continue;
            String caughtType = simpleName(cm.group(1));

            // Look ahead for a `throw new X(...)` on an ADDED line within range.
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

    /** Return whether the throw-new args reference the caught exception variable. */
    private static boolean mentionsExceptionVar(String catchHeader, String throwArgs) {
        // Rough: pull out the variable name after the type, then look for it in args.
        // catch (FooException e) -> "e"; catch (FooException ex) -> "ex".
        Matcher m = Pattern.compile("catch\\s*\\(\\s*[\\w.]+\\s+(\\w+)\\s*\\)").matcher(catchHeader);
        if (!m.find()) return false;
        String var = m.group(1);
        // Check the var appears as a standalone argument, not as part of another identifier.
        return Pattern.compile("(^|[^\\w])" + Pattern.quote(var) + "([^\\w]|$)").matcher(throwArgs).find();
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
