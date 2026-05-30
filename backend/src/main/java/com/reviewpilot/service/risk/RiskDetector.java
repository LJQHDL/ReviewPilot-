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
 * Aggregates findings from every {@link RiskRule} bean across every changed
 * file in a PR. Used by {@code ReviewPipeline} (PR#6) ahead of the AI call so
 * the prompt can include rule-derived risk hints.
 *
 * <p>Per-file flow: classify the file → ask each rule whether it
 * {@link RiskRule#appliesTo(FileType) appliesTo} that type → run scan →
 * collect. A single rule throwing is logged at WARN and skipped — one buggy
 * rule never blocks the rest of the review.
 */
@Component
public class RiskDetector {

    private static final Logger log = LoggerFactory.getLogger(RiskDetector.class);

    private final List<RiskRule> rules;
    private final FileClassifier classifier;

    public RiskDetector(List<RiskRule> rules, FileClassifier classifier) {
        this.rules = List.copyOf(rules);
        this.classifier = classifier;
    }

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
