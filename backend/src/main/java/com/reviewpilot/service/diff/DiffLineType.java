package com.reviewpilot.service.diff;

/** diff 行类型：新增（+）、删除（-）、上下文（空格前缀）。 */
public enum DiffLineType {
    ADDED,
    REMOVED,
    CONTEXT
}
