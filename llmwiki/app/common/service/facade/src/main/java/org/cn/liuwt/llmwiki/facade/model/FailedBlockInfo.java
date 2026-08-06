package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class FailedBlockInfo {

    private String searchPreview;

    private String reason;

    private Integer matchCount;

    private List<Integer> matchLines;
}
