package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.TokenUsageDailyDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TokenUsageDailyMapper extends BaseMapper<TokenUsageDailyDO> {

    @Update("INSERT INTO token_usage_daily (scope_id, usage_date, operation_type, input_tokens, output_tokens, call_count) " +
            "VALUES (#{scopeId}, #{usageDate}, #{operationType}, #{inputTokens}, #{outputTokens}, 1) " +
            "ON DUPLICATE KEY UPDATE " +
            "input_tokens = input_tokens + #{inputTokens}, " +
            "output_tokens = output_tokens + #{outputTokens}, " +
            "call_count = call_count + 1")
    int upsertUsage(@Param("scopeId") Long scopeId,
                    @Param("usageDate") String usageDate,
                    @Param("operationType") String operationType,
                    @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens);
}
