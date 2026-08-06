package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("step_baseline")
public class StepBaselineDO {
    private Long id;
    private Long scopeId;
    private String stepName;
    private String docFormat;
    private Long avgMs;
    private Long p95Ms;
    private Integer sampleCount;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
