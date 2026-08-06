package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class TokenUsageInfo {
    private long totalTokens;
    private long ingestTokens;
    private long queryTokens;
    private long lintTokens;
    private long budget;
    private long remaining;
    private double usagePercent;
    private String alertLevel;
}