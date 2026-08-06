package org.cn.liuwt.llmwiki.facade.model;

import java.time.LocalDateTime;

public class LintFindingInfo {
    private Long id;
    private Long scopeId;
    private String pagePath;
    private Long assetId;
    private String findingType;
    private String priority;
    private String title;
    private String detail;
    private String extra;
    private String rulingBriefJson;
    private String handlingMethod;
    private Integer riskScore;
    private LocalDateTime autoResolvedAt;
    private Long repairExecutionId;
    private String status;
    private Long executionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String userFeedback;
    private Integer feedbackCount;
    private LocalDateTime archivedAt;
    private String orphanDiagnosis;
    private String crossrefSuggestions;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }

    public String getPagePath() { return pagePath; }
    public void setPagePath(String pagePath) { this.pagePath = pagePath; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getFindingType() { return findingType; }
    public void setFindingType(String findingType) { this.findingType = findingType; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getExtra() { return extra; }
    public void setExtra(String extra) { this.extra = extra; }

    public String getRulingBriefJson() { return rulingBriefJson; }
    public void setRulingBriefJson(String rulingBriefJson) { this.rulingBriefJson = rulingBriefJson; }

    public String getHandlingMethod() { return handlingMethod; }
    public void setHandlingMethod(String handlingMethod) { this.handlingMethod = handlingMethod; }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public LocalDateTime getAutoResolvedAt() { return autoResolvedAt; }
    public void setAutoResolvedAt(LocalDateTime autoResolvedAt) { this.autoResolvedAt = autoResolvedAt; }

    public Long getRepairExecutionId() { return repairExecutionId; }
    public void setRepairExecutionId(Long repairExecutionId) { this.repairExecutionId = repairExecutionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUserFeedback() { return userFeedback; }
    public void setUserFeedback(String userFeedback) { this.userFeedback = userFeedback; }

    public Integer getFeedbackCount() { return feedbackCount; }
    public void setFeedbackCount(Integer feedbackCount) { this.feedbackCount = feedbackCount; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }

    public String getOrphanDiagnosis() { return orphanDiagnosis; }
    public void setOrphanDiagnosis(String orphanDiagnosis) { this.orphanDiagnosis = orphanDiagnosis; }

    public String getCrossrefSuggestions() { return crossrefSuggestions; }
    public void setCrossrefSuggestions(String crossrefSuggestions) { this.crossrefSuggestions = crossrefSuggestions; }
}
