package org.cn.liuwt.llmwiki.domain.service.harness.modify;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PageModifyContext {

    private final Long scopeId;
    private final Long pageId;
    private final Long executionId;
    private final Long sourceId;
    private final String instruction;
    private final boolean skipComplianceCheck;

    private String pagePath;
    private String pageTitle;
    private String currentContent;
    private String modifyPlanJson;
    private String globalSummary;
    private String metadataJson;
    private int totalTokens;

    private final ConcurrentHashMap<String, WikiPageDO> modifiedPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> allModifiedContent = new ConcurrentHashMap<>();

    private ModifyPlan modifyPlan;

    public PageModifyContext(Long scopeId, Long pageId, Long executionId, Long sourceId,
                             String instruction, boolean skipComplianceCheck) {
        this.scopeId = scopeId;
        this.pageId = pageId;
        this.executionId = executionId;
        this.sourceId = sourceId;
        this.instruction = instruction;
        this.skipComplianceCheck = skipComplianceCheck;
    }

    public String toAnalyzeOutputJson() {
        if (modifyPlanJson == null) return "{}";
        return modifyPlanJson;
    }

    public String toWriteOutputJson() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("targetPages", modifiedPages.size());
        node.set("modifiedPages", mapper.valueToTree(modifiedPages.keySet()));
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    public Long getScopeId() { return scopeId; }
    public Long getPageId() { return pageId; }
    public Long getExecutionId() { return executionId; }
    public Long getSourceId() { return sourceId; }
    public String getInstruction() { return instruction; }
    public boolean isSkipComplianceCheck() { return skipComplianceCheck; }

    public String getPagePath() { return pagePath; }
    public void setPagePath(String pagePath) { this.pagePath = pagePath; }

    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }

    public String getCurrentContent() { return currentContent; }
    public void setCurrentContent(String currentContent) { this.currentContent = currentContent; }

    public String getModifyPlanJson() { return modifyPlanJson; }
    public void setModifyPlanJson(String modifyPlanJson) { this.modifyPlanJson = modifyPlanJson; }

    public String getGlobalSummary() { return globalSummary; }
    public void setGlobalSummary(String globalSummary) { this.globalSummary = globalSummary; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    public void addTokens(int tokens) { this.totalTokens += tokens; }

    public ModifyPlan getModifyPlan() { return modifyPlan; }
    public void setModifyPlan(ModifyPlan modifyPlan) { this.modifyPlan = modifyPlan; }

    public ConcurrentHashMap<String, WikiPageDO> getModifiedPages() { return modifiedPages; }
    public ConcurrentHashMap<String, String> getAllModifiedContent() { return allModifiedContent; }

    public void addModifiedPage(String path, WikiPageDO pageDO) {
        modifiedPages.put(path, pageDO);
    }

    public void addModifiedContent(String path, String content) {
        allModifiedContent.put(path, content);
    }

    public static class ModifyPlan {
        private List<TargetChange> targetChanges = new ArrayList<>();
        private List<AffectedPage> affectedPages = new ArrayList<>();

        public List<TargetChange> getTargetChanges() { return targetChanges; }
        public void setTargetChanges(List<TargetChange> targetChanges) { this.targetChanges = targetChanges; }

        public List<AffectedPage> getAffectedPages() { return affectedPages; }
        public void setAffectedPages(List<AffectedPage> affectedPages) { this.affectedPages = affectedPages; }

        public static class TargetChange {
            private String section;
            private String change;
            private String rationale;

            public String getSection() { return section; }
            public void setSection(String section) { this.section = section; }
            public String getChange() { return change; }
            public void setChange(String change) { this.change = change; }
            public String getRationale() { return rationale; }
            public void setRationale(String rationale) { this.rationale = rationale; }
        }

        public static class AffectedPage {
            private String pagePath;
            private String relevantChange;
            private String whyAffected;

            public String getPagePath() { return pagePath; }
            public void setPagePath(String pagePath) { this.pagePath = pagePath; }
            public String getRelevantChange() { return relevantChange; }
            public void setRelevantChange(String relevantChange) { this.relevantChange = relevantChange; }
            public String getWhyAffected() { return whyAffected; }
            public void setWhyAffected(String whyAffected) { this.whyAffected = whyAffected; }
        }
    }
}
