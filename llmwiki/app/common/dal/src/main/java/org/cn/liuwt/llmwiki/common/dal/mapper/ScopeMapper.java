package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScopeMapper extends BaseMapper<ScopeDO> {

    @Select("SELECT id FROM scope WHERE id = #{scopeId} FOR UPDATE")
    Long lockScopeRow(@Param("scopeId") Long scopeId);
}