package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schema_config")
public class SchemaConfigDO {
    private Long id;
    private String configKey;
    private String configValue;
    private String configValueStructured;
    private String configGroup;
    private Long scopeId;
    private String description;
    private LocalDateTime updatedAt;
}