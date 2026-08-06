package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wiki_page_tag")
public class WikiPageTagDO {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private String tag;
}