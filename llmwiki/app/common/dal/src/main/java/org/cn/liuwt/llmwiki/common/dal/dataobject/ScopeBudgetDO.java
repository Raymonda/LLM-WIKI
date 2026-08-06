package org.cn.liuwt.llmwiki.common.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scope_budget")
public class ScopeBudgetDO {
    private Long id;
    private Long scopeId;
    private Integer monthlyBudget;
    private Integer usedTokens;
    private LocalDateTime resetDate;
    private Integer warningNotified;
    private Integer exceededNotified;
}