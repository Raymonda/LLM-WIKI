package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class PromotionStats {
    private Integer adoptedTeamCount;
    private Integer promotedPageCount;
    private List<PromotedPageInfo> recentPromotedPages;
}