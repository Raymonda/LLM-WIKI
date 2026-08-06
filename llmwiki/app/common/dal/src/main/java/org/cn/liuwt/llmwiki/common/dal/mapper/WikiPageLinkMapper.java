package org.cn.liuwt.llmwiki.common.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WikiPageLinkMapper extends BaseMapper<WikiPageLinkDO> {
}