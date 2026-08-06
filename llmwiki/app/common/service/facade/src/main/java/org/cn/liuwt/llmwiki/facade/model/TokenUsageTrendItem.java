package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class TokenUsageTrendItem {
    private String date;
    private long ingestTokens;
    private long queryTokens;
    private long lintTokens;
    private long schemaTokens;
    private long modifyTokens;
}
