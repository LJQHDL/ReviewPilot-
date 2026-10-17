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
 * 标记 Spring {@code @Transactional} 方法在其方法体（同一 hunk 内）通过 {@code this.}
 * 调用<em>同类</em>另一个 {@code @Transactional} 方法的情形——这种自调用会静默绕过
 * 代理，内层事务设置完全失效。
 *
 * <p>跨 hunk 无法静态确定"是否同类"，因此启发式为：hunk 内出现新增的
 * {@code @Transactional} 注解，且出现新增的 {@code this.foo(...)} 调用行。
 * 为抓住这个极常见的 Spring 陷阱，接受一定误报。
 *
 * <p>HIGH 严重度。
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

    /** 两步判定：hunk 内先确认有 @Transactional，再找 this.X(...) 自调用行。 */
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
                // @Transactional 方法内的 this.X(...) 调用正是绕过 Spring 代理的经典自调用
                if (content.contains("this.") && content.contains("(") && content.contains(")")) {
                    out.add(new RiskItem(
                            RiskLevel.HIGH,
                            change.filename(),
                            line.newLine(),
                            "Possible self-invocation inside a @Transactional method — Spring proxy is bypassed; extract to another bean."));
                    break; // 每个 hunk 报一条就够了
                }
            }
        }
        return out;
    }
}
