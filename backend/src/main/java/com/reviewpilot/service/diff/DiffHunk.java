package com.reviewpilot.service.diff;

import java.util.List;

/**
 * unified diff 中的一个 hunk（代码块），由 {@code @@ -oldStart,oldCount +newStart,newCount @@} 头部定位。
 */
/** 差异代码块，记录一段变更的起止位置和其中的差异行。 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {
}
