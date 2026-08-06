package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class UserDO {
    private Long id;
    private String username;
    private String passwordHash;
    private String email;
    private String avatar;
    private String role;
    private String status;
    private Long scopeId;
    private Integer consentKnowledgePromotion;
    private String language;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}