package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wiki_page_source")
public class WikiPageSourceDO {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private Long sourceId;
    private LocalDateTime createdAt;
}