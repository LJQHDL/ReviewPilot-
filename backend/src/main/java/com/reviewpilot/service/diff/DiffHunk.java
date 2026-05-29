package com.reviewpilot.service.diff;

import java.util.List;

/**
 * One hunk in a unified diff, identified by its header
 * {@code @@ -oldStart,oldCount +newStart,newCount @@}.
 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {
}
