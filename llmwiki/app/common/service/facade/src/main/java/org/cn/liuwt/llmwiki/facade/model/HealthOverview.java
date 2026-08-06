package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class HealthOverview {
    private Map<String, Long> healthDistribution;
    private Map<String, Long> findingCountsByType;
    private Map<String, Long> findingCountsByPriority;
    private long totalActiveFindings;
    private long totalPages;
    private LocalDateTime lastLintTime;
    private List<LintFindingInfo> topFindings;
}