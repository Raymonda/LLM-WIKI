package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("system_config")
public class SystemConfigDO {
    private Long id;
    private String configKey;
    private String configValue;
    private Long scopeId;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}