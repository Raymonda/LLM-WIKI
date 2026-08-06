package org.cn.liuwt.llmwiki.common.util.constant;

public enum PageLifecycle {
    ACTIVE,
    DEPRECATED,
    MERGING,
    MERGED,
    DELETED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isExcluded() {
        return this != ACTIVE;
    }

    public boolean isLocked() {
        return this == MERGING;
    }
}
