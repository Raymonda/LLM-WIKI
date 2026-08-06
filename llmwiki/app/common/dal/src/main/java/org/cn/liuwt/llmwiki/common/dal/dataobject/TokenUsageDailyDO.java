package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("token_usage_daily")
public class TokenUsageDailyDO {
    private Long id;
    private Long scopeId;
    private LocalDate usageDate;
    private String operationType;
    private Long inputTokens;
    private Long outputTokens;
    private Integer callCount;
    private LocalDateTime updatedAt;
}
