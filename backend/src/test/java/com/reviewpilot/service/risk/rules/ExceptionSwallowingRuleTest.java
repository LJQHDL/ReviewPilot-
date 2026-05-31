package com.reviewpilot.service.risk.rules;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.model.RiskLevel;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionSwallowingRuleTest {

    private final ExceptionSwallowingRule rule = new ExceptionSwallowingRule();

    @Test
    void high_when_cause_is_dropped() {
        // catch (IllegalArgumentException e) -> throw new SerializationException("msg")
        // No `e` in the throw args → original cause is lost.
        FileChange c = javaChange("Encoder.java",
                added(40, "} catch (IllegalArgumentException e) {"),
                added(41, "    throw new SerializationException(\"must implement Serializable\");"),
                added(42, "}"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.HIGH, risks.get(0).level());
        assertEquals(40, risks.get(0).line());
        assertTrue(risks.get(0).message().contains("Catch of IllegalArgumentException"),
                "message must mention caught type");
        assertTrue(risks.get(0).message().contains("SerializationException"),
                "message must mention thrown type");
    }

    @Test
    void medium_when_cause_chain_is_preserved() {
        // catch (IOException e) -> throw new RuntimeException("wrap", e)
        // Cause is forwarded — caller can still walk the chain.
        FileChange c = javaChange("Reader.java",
                added(10, "} catch (IOException e) {"),
                added(11, "    throw new RuntimeException(\"failed to read\", e);"),
                added(12, "}"));

        List<RiskItem> risks = rule.scan(c);

        assertEquals(1, risks.size());
        assertEquals(RiskLevel.MEDIUM, risks.get(0).level());
    }

    @Test
    void no_finding_when_rethrown_type_matches() {
        // catch (IOException e) -> throw new IOException(...)
        // Same type — could be re-wrap with extra context, not type laundering.
        FileChange c = javaChange("Reader.java",
                added(10, "} catch (IOException e) {"),
                added(11, "    throw new IOException(\"context: \" + path, e);"),
                added(12, "}"));

        assertTrue(rule.scan(c).isEmpty(),
                "rethrowing the same exception type is not exception swallowing");
    }

    @Test
    void no_finding_for_non_java_file() {
        FileChange c = new FileChange("config.yml", "modified", 2, 0, false, "patch",
                List.of(new DiffHunk(1, 2, 1, 2, List.of(
                        added(1, "} catch (Exception e) {"),
                        added(2, "    throw new RuntimeError(\"x\");")))));

        assertTrue(rule.scan(c).isEmpty(),
                "rule must skip non-Java files even when patch happens to match");
    }

    private static FileChange javaChange(String name, DiffLine... lines) {
        DiffHunk hunk = new DiffHunk(1, lines.length, 1, lines.length, List.of(lines));
        return new FileChange(name, "modified", lines.length, 0, false, "patch", List.of(hunk));
    }

    private static DiffLine added(int newLine, String content) {
        return new DiffLine(DiffLineType.ADDED, 0, newLine, content);
    }
}
