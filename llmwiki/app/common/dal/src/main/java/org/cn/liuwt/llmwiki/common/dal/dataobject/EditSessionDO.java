package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("edit_session")
public class EditSessionDO {
    private Long id;
    private Long scopeId;
    private Long pageId;
    private Long draftId;
    private String currentContent;
    private String originalContent;
    private String contentHash;
    private String outline;
    private String historySummary;
    private Integer stepCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
}
