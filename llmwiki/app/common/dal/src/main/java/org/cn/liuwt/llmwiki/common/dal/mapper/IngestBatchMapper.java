package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestBatchMapper extends BaseMapper<IngestBatchDO> {
}
