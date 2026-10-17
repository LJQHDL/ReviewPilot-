package com.reviewpilot.service.diff;

import java.util.List;

/**
 * unified diff 中的一个 hunk（代码块），由 {@code @@ -oldStart,oldCount +newStart,newCount @@} 头部定位。
 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {
    /** 保存差异行快照，确保代码行与块的定位信息保持一致。 */
    public DiffHunk {
        lines = List.copyOf(lines);
    }
}
