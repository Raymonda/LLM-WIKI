package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wiki_page_draft")
public class WikiPageDraftDO {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private String baseContentHash;
    private String title;
    private String content;
    private String category;
    private String tags;
    private String pageType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
}
