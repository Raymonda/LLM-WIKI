package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scope_member")
public class ScopeMemberDO {
    private Long id;
    private Long scopeId;
    private Long userId;
    private String role;
    private LocalDateTime joinedAt;
}