package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

@Data
public class ContributorInfo {
    private Long userId;
    private String userName;
    private Integer promotedPageCount;
}