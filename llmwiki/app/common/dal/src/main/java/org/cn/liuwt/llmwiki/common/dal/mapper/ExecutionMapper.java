package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExecutionMapper extends BaseMapper<ExecutionDO> {

    @Select("SELECT * FROM execution WHERE type = 'ingest' AND scope_id = #{scopeId} AND status = 'running' FOR UPDATE")
    List<ExecutionDO> selectRunningIngestForUpdate(@Param("scopeId") Long scopeId);
}