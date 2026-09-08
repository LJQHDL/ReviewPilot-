package com.reviewpilot.service.context;

import com.reviewpilot.model.RiskItem;
import com.reviewpilot.service.diff.DiffHunk;
import com.reviewpilot.service.diff.DiffLine;
import com.reviewpilot.service.diff.DiffLineType;
import com.reviewpilot.service.diff.FileChange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 围绕每条规则检出的风险，从 diff 中提取小窗口代码切片，让 AI 看到风险行前后的上下文而非只有 hunk 头。
 *
 * <p>刻意复用 {@code FileChange.hunks()} 里已有的内容，而不是再调 GitHub raw-content API：
 * diff 自带变更行上下约 3 行上下文，足以锚定 AI 的推理；再加一次 HTTP 调用会让网络延迟翻倍
 * 而信号几乎不增——还会迫使私有仓库鉴权。
 *
 * <p>算法：对每条风险，在文件的 hunks 中定位新文件行号，收集 ±{@link #WINDOW} 行，
 * 然后合并重叠或相邻的切片（让两条邻近风险共享一段代码而不是产生重复）。
 * 行号定位不到的风险直接跳过——没有有用的兜底方案。
 */
@Component
public class ContextLoader {

    /** 风险行每侧携带的上下文行数。 */
    static final int WINDOW = 5;

    /**
     * 每个合并后的窗口返回一条 {@link ContextSlice}。切片保持输入的文件顺序，
     * 文件内部按 {@code startLine} 排序，使 Prompt 自上而下可读。
     */
    public List<ContextSlice> load(List<FileChange> files, List<RiskItem> risks) {
        if (files == null || files.isEmpty() || risks == null || risks.isEmpty()) {
            return List.of();
        }

        Map<String, TreeSet<Integer>> linesByFile = groupRiskLines(risks);
        if (linesByFile.isEmpty()) return List.of();

        List<ContextSlice> out = new ArrayList<>();
        for (FileChange file : files) {
            TreeSet<Integer> lines = linesByFile.get(file.filename());
            if (lines == null || lines.isEmpty()) continue;
            out.addAll(slicesFor(file, lines));
        }
        return out;
    }

    /** 按文件聚合风险行号（TreeSet 天然去重升序）；无文件/无行号的风险被丢弃。 */
    private static Map<String, TreeSet<Integer>> groupRiskLines(List<RiskItem> risks) {
        Map<String, TreeSet<Integer>> map = new HashMap<>();
        for (RiskItem r : risks) {
            if (r == null || r.file() == null || r.file().isBlank() || r.line() <= 0) continue;
            map.computeIfAbsent(r.file(), k -> new TreeSet<>()).add(r.line());
        }
        return map;
    }

    /** 为单个文件构建"新文件行号 → 渲染行"映射，再按 ±WINDOW 切窗并合并相邻窗口。 */
    private static List<ContextSlice> slicesFor(FileChange file, TreeSet<Integer> riskLines) {
        // 把 hunks 里的新文件内容摊平成 {newLine -> 展示行} 映射（跳过被删除的行）
        Map<Integer, String> displayByLine = new HashMap<>();
        if (file.hunks() != null) {
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.type() == DiffLineType.REMOVED) continue;
                    int n = line.newLine();
                    if (n <= 0) continue;
                    // 新增行标 '+'，其余标空格，让模型能区分改动与原文
                    char marker = line.type() == DiffLineType.ADDED ? '+' : ' ';
                    displayByLine.put(n, String.format("%c %4d: %s", marker, n, line.content()));
                }
            }
        }
        if (displayByLine.isEmpty()) return List.of();

        List<int[]> windows = new ArrayList<>();
        for (int risk : riskLines) {
            int from = Math.max(1, risk - WINDOW);
            int to = risk + WINDOW;
            if (!windows.isEmpty() && from <= windows.get(windows.size() - 1)[1] + 1) {
                // 与前一个窗口相邻或重叠——就地扩展合并
                windows.get(windows.size() - 1)[1] = Math.max(windows.get(windows.size() - 1)[1], to);
            } else {
                windows.add(new int[]{from, to});
            }
        }

        // 按窗口收集实际存在的行；窗口内全是缺失行则跳过
        List<ContextSlice> result = new ArrayList<>();
        for (int[] w : windows) {
            List<String> rendered = new ArrayList<>();
            int actualStart = -1, actualEnd = -1;
            for (int n = w[0]; n <= w[1]; n++) {
                String s = displayByLine.get(n);
                if (s == null) continue;
                if (actualStart < 0) actualStart = n;
                actualEnd = n;
                rendered.add(s);
            }
            if (!rendered.isEmpty()) {
                result.add(new ContextSlice(file.filename(), actualStart, actualEnd, rendered));
            }
        }
        return result;
    }
}
