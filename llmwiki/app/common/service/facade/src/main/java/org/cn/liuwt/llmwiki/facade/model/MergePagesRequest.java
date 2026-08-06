package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class MergePagesRequest {
    private List<Long> pageIds;
    private String targetTitle;
    private String instruction;
}
