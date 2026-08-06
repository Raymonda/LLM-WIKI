package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("source")
public class SourceDO {
    private Long id;
    private String name;
    private String filePath;
    private String format;
    private Long size;
    private String status;
    private Long scopeId;
    private Long uploadUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime fileModifiedAt;
    private String contentHash;
}