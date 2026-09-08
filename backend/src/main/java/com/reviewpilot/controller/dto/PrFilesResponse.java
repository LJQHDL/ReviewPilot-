package com.reviewpilot.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * GET /api/pr/files 的对外响应视图，与内部 FileChange 解耦以避免 diff 层字段泄漏成公共契约。
 *
 * @param truncated 变更文件数是否超出抓取页数上限；为 true 时 files 只是子集、评审结果不完整
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrFilesResponse(List<FileEntry> files, boolean truncated) {

    /** @param patch 该文件的原始 unified diff，仅在 ?include=patch 时返回 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileEntry(String filename, String status, int additions, int deletions,
                            int hunkCount, String patch) {

        /** 从内部 FileChange 构造响应条目，按 withPatch 决定是否附带 patch。 */
        public static FileEntry of(FileChange f, boolean withPatch) {
            return new FileEntry(
                    f.filename(),
                    f.status(),
                    f.additions(),
                    f.deletions(),
                    f.hunks() == null ? 0 : f.hunks().size(),
                    withPatch ? f.patch() : null);
        }
    }
}
