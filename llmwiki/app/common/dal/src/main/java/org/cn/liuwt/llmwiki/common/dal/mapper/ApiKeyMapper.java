package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ApiKeyDO;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKeyDO> {
}
