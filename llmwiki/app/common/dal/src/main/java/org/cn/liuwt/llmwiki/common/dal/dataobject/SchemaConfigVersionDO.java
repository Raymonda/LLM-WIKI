package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schema_config_version")
public class SchemaConfigVersionDO {
    private Long id;
    private Long scopeId;
    private String configKey;
    private String configValue;
    private String configValueStructured;
    private Long parentVersionId;
    private Integer versionNumber;
    private String sourceType;
    private Long sourceOpId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
