package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("wiki_page")
public class WikiPageDO {
    private Long id;
    private String title;
    private String filePath;
    private String category;
    private String summary;
    private Long scopeId;
    private Integer sourceCount;
    private String healthStatus;
    private String pageType;
    private String visibility;
    private Long promotedFromScopeId;
    private Long promotedFromPageId;
    private String promotedFromUsername;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime contentUpdatedAt;
    private Long schemaVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private String lifecycleStatus;

    private LocalDateTime deprecatedAt;
    private String deprecatedReason;
    private LocalDateTime deletedAt;
    private Long mergedIntoPageId;
    private Integer userModified;
    private String contentHash;
    private String postSaveStatus;
    private String postSaveError;
}