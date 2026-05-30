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
 * Flags Spring {@code @Transactional} re-declarations on a method whose body
 * (within the same hunk) calls another {@code @Transactional} method on the
 * <em>same class</em> via {@code this.}, which silently bypasses the proxy and
 * runs without the inner transaction settings.
 *
 * <p>We can't statically resolve "same class" across hunks, so the heuristic
 * is: an added {@code @Transactional} annotation in the hunk plus an added
 * {@code this.foo(...)} call line. False positives are accepted in exchange
 * for catching the very common Spring pitfall.
 *
 * <p>HIGH severity.
 */
@Component
public class NestedTransactionRule implements RiskRule {

    @Override
    public String id() {
        return "nested-transaction";
    }

    @Override
    public boolean appliesTo(FileType type) {
        return type == FileType.SERVICE || type == FileType.OTHER;
    }

    @Override
    public List<RiskItem> scan(FileChange change) {
        List<RiskItem> out = new ArrayList<>();
        if (change == null || change.hunks() == null) return out;

        for (DiffHunk hunk : change.hunks()) {
            boolean hasTxAnnotation = false;
            for (DiffLine l : hunk.lines()) {
                if (l.type() == DiffLineType.ADDED && l.content().contains("@Transactional")) {
                    hasTxAnnotation = true;
                    break;
                }
            }
            if (!hasTxAnnotation) continue;

            for (DiffLine line : hunk.lines()) {
                if (line.type() != DiffLineType.ADDED) continue;
                String content = line.content();
                // A this.X(...) call inside a @Transactional method is the classic
                // self-invocation that bypasses the Spring proxy.
                if (content.contains("this.") && content.contains("(") && content.contains(")")) {
                    out.add(new RiskItem(
                            RiskLevel.HIGH,
                            change.filename(),
                            line.newLine(),
                            "Possible self-invocation inside a @Transactional method — Spring proxy is bypassed; extract to another bean."));
                    break; // one finding per hunk is enough
                }
            }
        }
        return out;
    }
}
