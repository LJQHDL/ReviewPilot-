package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.classifier.FileClassifier;
import com.reviewpilot.service.classifier.FileType;
import com.reviewpilot.service.diff.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合所有 {@link RiskRule} Bean 对 PR 全部变更文件的检查结果。
 * 由 {@code ReviewPipeline} 在调用 AI 之前使用，把规则检出的风险提示注入 Prompt。
 *
 * <p>单文件流程：分类文件 → 询问每条规则是否 {@link RiskRule#appliesTo(FileType) 适用}
 * 该类型 → 执行扫描 → 收集结果。某条规则抛异常只记 WARN 并跳过——
 * 一条坏规则绝不阻塞整个评审。
 */
@Component
public class RiskDetector {

    private static final Logger log = LoggerFactory.getLogger(RiskDetector.class);

    private final List<RiskRule> rules;
    private final FileClassifier classifier;

    public RiskDetector(List<RiskRule> rules, FileClassifier classifier) {
        // Spring 注入 List<RiskRule>：新增规则只需往 rules 包放一个 @Component，无需改这里
        this.rules = List.copyOf(rules);
        this.classifier = classifier;
    }

    /** 扫描全部变更文件，返回所有规则命中的风险汇总。 */
    public List<RiskItem> scan(List<FileChange> files) {
        List<RiskItem> out = new ArrayList<>();
        if (files == null || files.isEmpty()) return out;

        for (FileChange file : files) {
            if (file == null) continue;
            FileType type = classifier.classify(file);
            for (RiskRule rule : rules) {
                if (!rule.appliesTo(type)) continue;
                try {
                    List<RiskItem> found = rule.scan(file);
                    if (found != null && !found.isEmpty()) {
                        out.addAll(found);
                    }
                } catch (RuntimeException e) {
                    log.warn("RiskRule {} failed on file {}: {}",
                            rule.id(), file.filename(), e.toString());
                }
            }
        }
        return out;
    }
}
