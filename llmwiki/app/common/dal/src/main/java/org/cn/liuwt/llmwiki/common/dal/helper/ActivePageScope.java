package org.cn.liuwt.llmwiki.common.dal.helper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;

public final class ActivePageScope {

    public static final String ACTIVE_SQL = "lifecycle_status = 'ACTIVE'";
    public static final String ACTIVE_VISIBLE_SQL = ACTIVE_SQL + " AND visibility != 'private'";

    private ActivePageScope() {}

    public static LambdaQueryWrapper<WikiPageDO> active(Long scopeId) {
        return new LambdaQueryWrapper<WikiPageDO>()
            .eq(WikiPageDO::getScopeId, scopeId)
            .eq(WikiPageDO::getLifecycleStatus, PageLifecycle.ACTIVE.name());
    }

    public static LambdaQueryWrapper<WikiPageDO> activeVisible(Long scopeId) {
        return active(scopeId)
            .ne(WikiPageDO::getVisibility, "private");
    }
}
