package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("wiki_page_link")
public class WikiPageLinkDO {
    private Long id;
    private Long scopeId;
    private Long fromPageId;
    private Long toPageId;
    private String linkType;
    private String linkContext;
    private String createdBy;
    private Double confidence;
    private Long executionId;
    private java.time.LocalDateTime createdAt;
}