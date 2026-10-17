package com.reviewpilot.service.diff;

import java.util.List;

/**
 * PR 中单个文件的变更记录，与 GitHub {@code GET /repos/.../pulls/{n}/files} 响应条目形状一致，
 * 并在非二进制文件时附带 {@link #patch()} 解析出的 hunks。
 */
/** 单个文件变更数据类，保存文件路径、状态、统计信息和差异内容。 */
public record FileChange(
        String filename,
        String status,         // "added" | "removed" | "modified" | "renamed"
        int additions,
        int deletions,
        boolean binary,
        String patch,          // 原始 unified diff（二进制或无改动时为 null）
        List<DiffHunk> hunks   // 二进制或 patch 为 null 时为空
) {
    /** 固定解析结果，避免外部修改列表影响后续风险检测。 */
    public FileChange {
        hunks = List.copyOf(hunks);
    }
}
