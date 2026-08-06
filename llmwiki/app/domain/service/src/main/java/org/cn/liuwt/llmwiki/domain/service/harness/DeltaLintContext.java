package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService.GlobalSummary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record DeltaLintContext(
    GlobalSummary summary,
    List<LintFindingDO> previousFindings,
    Map<String, Long> healthDistribution,
    List<WikiPageDO> changedPages,
    List<WikiPageDO> newPages,
    Set<Long> ingestAffectedPageIds,
    LocalDateTime lastLintCompletedAt,
    boolean isFullScan
) {
    public boolean isIncremental() {
        return !isFullScan && lastLintCompletedAt != null && (!changedPages.isEmpty() || !newPages.isEmpty());
    }

    public List<WikiPageDO> focusPages() {
        if (!isIncremental()) return List.of();
        java.util.List<WikiPageDO> merged = new java.util.ArrayList<>(newPages);
        java.util.Set<Long> newIds = newPages.stream().map(WikiPageDO::getId).collect(java.util.stream.Collectors.toSet());
        for (WikiPageDO p : changedPages) {
            if (!newIds.contains(p.getId())) {
                merged.add(p);
            }
        }
        return merged;
    }

    public int totalPageCount() {
        return healthDistribution.values().stream().mapToInt(Long::intValue).sum();
    }
}
