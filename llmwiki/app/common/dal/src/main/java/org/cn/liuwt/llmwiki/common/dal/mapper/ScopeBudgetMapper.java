package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeBudgetDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ScopeBudgetMapper extends BaseMapper<ScopeBudgetDO> {

    @Update("UPDATE scope_budget SET used_tokens = used_tokens + #{delta} WHERE scope_id = #{scopeId}")
    int incrementUsedTokens(@Param("scopeId") Long scopeId, @Param("delta") int delta);

    @Update("UPDATE scope_budget SET used_tokens = 0, warning_notified = 0, exceeded_notified = 0, " +
        "reset_date = #{nextReset} WHERE reset_date <= #{now}")
    int resetExpiredMonthlyUsage(@Param("now") LocalDateTime now, @Param("nextReset") LocalDateTime nextReset);
}