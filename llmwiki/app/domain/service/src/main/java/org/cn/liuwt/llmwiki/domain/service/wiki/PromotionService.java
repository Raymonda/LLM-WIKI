package org.cn.liuwt.llmwiki.domain.service.wiki;

import org.cn.liuwt.llmwiki.facade.model.PromotionStats;
import org.cn.liuwt.llmwiki.facade.model.ContributorInfo;

import java.util.List;

public interface PromotionService {
    void updateVisibility(Long scopeId, String pagePath, String visibility, Long userId);
    void softRecall(Long scopeId, String pagePath, Long userId);
    PromotionStats getPromotionStats(Long userId);
    List<ContributorInfo> getContributors(Long scopeId);
}