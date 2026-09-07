package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class RiskMerger {
    /**
     * Combines rule-detected and AI-detected risks into one list, deduplicated
     * by (file, line, message) so a model that faithfully echoes a rule finding
     * doesn't produce a duplicate row in the UI. Rule findings come first so
     * they remain visible even if the model omits them entirely.
     */
    public List<RiskItem> merge(List<RiskItem> ruleRisks, List<RiskItem> aiRisks) {
        Set<String> seen = new LinkedHashSet<>();
        List<RiskItem> out = new ArrayList<>(ruleRisks.size() + aiRisks.size());
        for (RiskItem r : ruleRisks) addIfNew(r, seen, out);
        for (RiskItem r : aiRisks) addIfNew(r, seen, out);
        return out;
    }

    private static void addIfNew(RiskItem r, Set<String> seen, List<RiskItem> out) {
        if (r == null) return;
        String key = (r.file() == null ? "" : r.file()) + "|" + r.line() + "|" + (r.message() == null ? "" : r.message());
        if (seen.add(key)) out.add(r);
    }

}
