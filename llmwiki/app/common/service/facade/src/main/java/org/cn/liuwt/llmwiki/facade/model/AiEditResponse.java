package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class AiEditResponse {
    private String type;
    private String content;
    private String diffRemoved;
    private String diffAdded;
    private Integer stepNumber;
    private List<SearchResultInfo> knowledgeResults;
    private String mode;
    private String explanation;
    private String code;
    private String anchorLines;
    private String anchorStatus;
    private Integer retryRound;
    private Integer failedBlockCount;
    private List<FailedBlockInfo> failedBlocks;
    private String phase;
}
