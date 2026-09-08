package com.reviewpilot.service.github;

import com.reviewpilot.service.diff.FileChange;

import java.util.List;

/**
 * PR 的变更文件清单 + 清单是否完整的标志。GitHub 对 {@code /pulls/{n}/files} 分页，
 * 大型 PR 只能部分抓取——调用方必须把截断上报出来，而不是把不完整的评审当作完整结果。
 */
public record FetchedFiles(List<FileChange> files, boolean truncated) {

    /** 工厂方法：文件数未触顶时的"完整"结果。 */
    public static FetchedFiles complete(List<FileChange> files) {
        return new FetchedFiles(files, false);
    }
}
