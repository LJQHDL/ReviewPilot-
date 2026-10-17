package com.reviewpilot.service.risk;

import com.reviewpilot.model.RiskItem;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 合并规则检出与 AI 检出的风险列表，按 (file, line, message) 去重。 */
@Component
public class RiskMerger {
    /**
     * 把规则风险与 AI 风险合成一个按 (文件, 行号, 消息) 去重的列表：
     * 忠实复述规则发现的模型不会在 UI 上造成重复行。
     * 规则结果排在最前，即使模型完全遗漏它们也依然可见。
     */
    public List<RiskItem> merge(List<RiskItem> ruleRisks, List<RiskItem> aiRisks) {
        Set<String> seen = new LinkedHashSet<>();
        List<RiskItem> out = new ArrayList<>(ruleRisks.size() + aiRisks.size());
        for (RiskItem r : ruleRisks) addIfNew(r, seen, out);
        for (RiskItem r : aiRisks) addIfNew(r, seen, out);
        return out;
    }

    /** 用 "file|line|message" 作为去重键，首次出现才加入结果列表。 */
    private static void addIfNew(RiskItem r, Set<String> seen, List<RiskItem> out) {
        if (r == null) return;
        String key = (r.file() == null ? "" : r.file()) + "|" + r.line() + "|" + (r.message() == null ? "" : r.message());
        if (seen.add(key)) out.add(r);
    }

}
