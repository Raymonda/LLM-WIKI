package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLogDO {
    private Long id;
    private Long actorUserId;
    private String actorUsername;
    private String action;
    private String targetType;
    private Long targetId;
    private String targetName;
    private Long scopeId;
    private String detailJson;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
}
