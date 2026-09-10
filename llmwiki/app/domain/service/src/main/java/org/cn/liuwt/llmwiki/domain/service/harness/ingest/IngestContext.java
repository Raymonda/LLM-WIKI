package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentProfile;
import org.cn.liuwt.llmwiki.domain.service.harness.ExecutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentChunker;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.SchemaViolation;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ViolationGroupSummary;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ViolationType;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.Severity;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class IngestContext {

    private final Long scopeId;
    private final Long sourceId;
    private final Long executionId;
    private final String guidance;

    private String sourceContent;
    private String originalSourceContent;
    private String sourceName;
    private String parsedContent;
    private List<DocumentChunker.Chunk> chunks;
    private int chunkCount;

    private List<ParallelAnalysisResult> chunkResults;
    private String mergedAnalysis;
    private String metadataJson;

    private String pagesContext;
    private Map<String, List<Integer>> entityChunkMap;
    private List<WriterAgent.EntityContextualRef> contextualEntities;

    private List<ExtractedImage> extractedImages;
    private List<ChartInfo> chartInfos;
    private List<String> chartDescriptionBlocks;

    private DocumentStructureAnalyzer.DocumentType documentType;
    private List<DocumentStructureAnalyzer.Chapter> chapters;
    private int totalChapterCount;
    private final ConcurrentHashMap<String, WikiPageDO> chapterPages = new ConcurrentHashMap<>();

    private DocumentProfile documentProfile;
    private ExecutionStrategy strategy;

    private WikiPageDO summaryPage;
    private final ConcurrentHashMap<String, WikiPageDO> entityPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WikiPageDO> updatedPages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pageContents = new ConcurrentHashMap<>();

    private String writingPlanJson;
    private int totalTokens;
    private ComplianceResult planComplianceResult;
    private ComplianceResult productComplianceResult;
    private boolean skipComplianceCheck;
    private boolean suppressNotifications;
    private List<ConflictAnnotation> conflictAnnotations = new ArrayList<>();
    private Map<String, Integer> conflictRouteSummary = new java.util.LinkedHashMap<>();
    private List<String> schemaPatchHints = new ArrayList<>();

    private String targetTitle;

    private Map<String, DeprecatedPageInfo> deprecatedPagesIndex;

    private InformationCatalog informationCatalog;
    private Map<String, EntityDossier> entityDossiers;
    private WriterQualityVerifier.VerificationReport verificationReport;

    private String entityRelationshipSummary;
    private String parseValidationReport;
    private double completenessScore;
    private String knowledgeValueAssessment;
    private List<String> schemaGapHints = new ArrayList<>();
    private List<SubDocument> subDocuments;
    private Map<String, String> subDocumentSourceMap = new HashMap<>();

    private transient CompletableFuture<Integer> linksFuture;
    private transient CompletableFuture<Void> reconcilerFuture;
    private boolean writerPostChecksDone;
    private boolean bulkIndexed;

    public IngestContext(Long scopeId, Long sourceId, Long executionId, String guidance) {
        this.scopeId = scopeId;
        this.sourceId = sourceId;
        this.executionId = executionId;
        this.guidance = guidance;
    }

    public Long getScopeId() { return scopeId; }
    public Long getSourceId() { return sourceId; }
    public Long getExecutionId() { return executionId; }
    public String getGuidance() { return guidance; }

    public String getTargetTitle() { return targetTitle; }
    public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

    public Map<String, DeprecatedPageInfo> getDeprecatedPagesIndex() { return deprecatedPagesIndex; }
    public void setDeprecatedPagesIndex(Map<String, DeprecatedPageInfo> deprecatedPagesIndex) { this.deprecatedPagesIndex = deprecatedPagesIndex; }

    public DeprecatedPageInfo findDeprecatedPage(String entityName) {
        if (deprecatedPagesIndex == null || entityName == null) return null;
        DeprecatedPageInfo exact = deprecatedPagesIndex.get(entityName.toLowerCase());
        return exact;
    }

    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }

    public String getOriginalSourceContent() { return originalSourceContent; }
    public void setOriginalSourceContent(String originalSourceContent) { this.originalSourceContent = originalSourceContent; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getParsedContent() { return parsedContent; }
    public void setParsedContent(String parsedContent) { this.parsedContent = parsedContent; }

    public List<DocumentChunker.Chunk> getChunks() { return chunks; }
    public void setChunks(List<DocumentChunker.Chunk> chunks) { this.chunks = chunks; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public List<ParallelAnalysisResult> getChunkResults() { return chunkResults; }
    public void setChunkResults(List<ParallelAnalysisResult> chunkResults) { this.chunkResults = chunkResults; }

    public String getMergedAnalysis() { return mergedAnalysis; }
    public void setMergedAnalysis(String mergedAnalysis) { this.mergedAnalysis = mergedAnalysis; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public String getPagesContext() { return pagesContext; }
    public void setPagesContext(String pagesContext) { this.pagesContext = pagesContext; }

    public Map<String, List<Integer>> getEntityChunkMap() { return entityChunkMap; }
    public void setEntityChunkMap(Map<String, List<Integer>> entityChunkMap) { this.entityChunkMap = entityChunkMap; }

    public List<WriterAgent.EntityContextualRef> getContextualEntities() { return contextualEntities; }
    public void setContextualEntities(List<WriterAgent.EntityContextualRef> contextualEntities) { this.contextualEntities = contextualEntities; }

    public List<ExtractedImage> getExtractedImages() { return extractedImages; }
    public void setExtractedImages(List<ExtractedImage> extractedImages) { this.extractedImages = extractedImages; }
    public boolean hasExtractedImages() { return extractedImages != null && !extractedImages.isEmpty(); }

    public List<ChartInfo> getChartInfos() { return chartInfos; }
    public void setChartInfos(List<ChartInfo> chartInfos) { this.chartInfos = chartInfos; }
    public boolean hasChartInfos() { return chartInfos != null && !chartInfos.isEmpty(); }

    public List<String> getChartDescriptionBlocks() { return chartDescriptionBlocks; }
    public void setChartDescriptionBlocks(List<String> chartDescriptionBlocks) { this.chartDescriptionBlocks = chartDescriptionBlocks; }
    public boolean hasChartDescriptionBlocks() { return chartDescriptionBlocks != null && !chartDescriptionBlocks.isEmpty(); }

    public String buildChartManifest() {
        if (chartInfos == null || chartInfos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 源文档图表清单\n\n");
        sb.append("以下图表已从源文档中提取并保存为图片，请在写作时保留对应的图片引用（`![标签](路径)` 格式）。\n\n");
        for (int i = 0; i < chartInfos.size(); i++) {
            ChartInfo c = chartInfos.get(i);
            sb.append(i + 1).append(". **").append(c.source()).append("**");
            if (c.page() > 0) sb.append("（第").append(c.page()).append("页）");
            if (!c.description().isEmpty()) sb.append(" — ").append(c.description());
            sb.append("\n   路径: `").append(c.relativePath()).append("`\n");
        }
        sb.append("\n> **重要**：请在生成的 Wiki 页面中保留上述图表引用，使用 `![标签](路径)` 格式嵌入图片。\n");
        return sb.toString();
    }

    public String buildImageManifest() {
        StringBuilder sb = new StringBuilder();

        if (chartInfos != null && !chartInfos.isEmpty()) {
            sb.append(buildChartManifest());
        }

        if (extractedImages != null && !extractedImages.isEmpty()) {
            Set<String> chartPaths = chartInfos != null
                ? chartInfos.stream().map(ChartInfo::relativePath).collect(Collectors.toSet())
                : Set.of();
            List<ExtractedImage> nonChartImages = extractedImages.stream()
                .filter(img -> !chartPaths.contains(img.relativePath()))
                .toList();

            if (!nonChartImages.isEmpty()) {
                sb.append("## 源文档嵌入图片\n\n");
                sb.append("以下图片已从源文档中提取，请在写作时适当引用（`![描述](路径)` 格式）。\n\n");
                for (int i = 0; i < nonChartImages.size(); i++) {
                    ExtractedImage img = nonChartImages.get(i);
                    sb.append(i + 1).append(". ").append(img.description());
                    sb.append("\n   路径: `").append(img.relativePath()).append("`\n");
                }
                sb.append("\n> **重要**：图片描述包含 VL 模型分析结果，请根据描述在相关内容处引用图片。\n");
            }
        }

        return sb.toString();
    }

    public DocumentStructureAnalyzer.DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentStructureAnalyzer.DocumentType documentType) { this.documentType = documentType; }

    public List<DocumentStructureAnalyzer.Chapter> getChapters() { return chapters; }
    public void setChapters(List<DocumentStructureAnalyzer.Chapter> chapters) { this.chapters = chapters; }

    public int getTotalChapterCount() { return totalChapterCount; }
    public void setTotalChapterCount(int totalChapterCount) { this.totalChapterCount = totalChapterCount; }

    public ConcurrentHashMap<String, WikiPageDO> getChapterPages() { return chapterPages; }

    public DocumentProfile getDocumentProfile() { return documentProfile; }
    public void setDocumentProfile(DocumentProfile documentProfile) { this.documentProfile = documentProfile; }

    public ExecutionStrategy getStrategy() { return strategy; }
    public void setStrategy(ExecutionStrategy strategy) { this.strategy = strategy; }

    public WikiPageDO getSummaryPage() { return summaryPage; }
    public void setSummaryPage(WikiPageDO summaryPage) { this.summaryPage = summaryPage; }

    public ConcurrentHashMap<String, WikiPageDO> getEntityPages() { return entityPages; }
    public ConcurrentHashMap<String, WikiPageDO> getUpdatedPages() { return updatedPages; }

    public ConcurrentHashMap<String, String> getPageContents() { return pageContents; }

    public String getWritingPlanJson() { return writingPlanJson; }
    public void setWritingPlanJson(String writingPlanJson) { this.writingPlanJson = writingPlanJson; }

    public int getTotalTokens() { return totalTokens; }
    public void addTokens(int tokens) { this.totalTokens += tokens; }

    public ComplianceResult getPlanComplianceResult() { return planComplianceResult; }
    public void setPlanComplianceResult(ComplianceResult planComplianceResult) { this.planComplianceResult = planComplianceResult; }

    public ComplianceResult getProductComplianceResult() { return productComplianceResult; }
    public void setProductComplianceResult(ComplianceResult productComplianceResult) { this.productComplianceResult = productComplianceResult; }

    public boolean isSkipComplianceCheck() { return skipComplianceCheck; }
    public void setSkipComplianceCheck(boolean skipComplianceCheck) { this.skipComplianceCheck = skipComplianceCheck; }

    public boolean isSuppressNotifications() { return suppressNotifications; }
    public void setSuppressNotifications(boolean suppressNotifications) { this.suppressNotifications = suppressNotifications; }

    public List<ConflictAnnotation> getConflictAnnotations() { return conflictAnnotations; }
    public void setConflictAnnotations(List<ConflictAnnotation> conflictAnnotations) { this.conflictAnnotations = conflictAnnotations; }
    public void addConflictAnnotation(ConflictAnnotation annotation) { this.conflictAnnotations.add(annotation); }

    public Map<String, Integer> getConflictRouteSummary() { return conflictRouteSummary; }
    public void setConflictRouteSummary(Map<String, Integer> summary) { this.conflictRouteSummary = summary; }
    public void incrementConflictRoute(String level) {
        conflictRouteSummary.merge(level, 1, Integer::sum);
    }

    public List<String> getSchemaPatchHints() { return schemaPatchHints; }
    public void setSchemaPatchHints(List<String> schemaPatchHints) { this.schemaPatchHints = schemaPatchHints; }

    public InformationCatalog getInformationCatalog() { return informationCatalog; }
    public void setInformationCatalog(InformationCatalog informationCatalog) { this.informationCatalog = informationCatalog; }

    public Map<String, EntityDossier> getEntityDossiers() { return entityDossiers; }
    public void setEntityDossiers(Map<String, EntityDossier> entityDossiers) { this.entityDossiers = entityDossiers; }

    public EntityDossier getEntityDossier(String entityName) {
        return entityDossiers != null ? entityDossiers.get(entityName) : null;
    }

    public WriterQualityVerifier.VerificationReport getVerificationReport() { return verificationReport; }
    public void setVerificationReport(WriterQualityVerifier.VerificationReport verificationReport) { this.verificationReport = verificationReport; }

    public String getEntityRelationshipSummary() { return entityRelationshipSummary; }
    public void setEntityRelationshipSummary(String entityRelationshipSummary) { this.entityRelationshipSummary = entityRelationshipSummary; }

    public String getParseValidationReport() { return parseValidationReport; }
    public void setParseValidationReport(String parseValidationReport) { this.parseValidationReport = parseValidationReport; }

    public double getCompletenessScore() { return completenessScore; }
    public void setCompletenessScore(double completenessScore) { this.completenessScore = completenessScore; }

    public String getKnowledgeValueAssessment() { return knowledgeValueAssessment; }
    public void setKnowledgeValueAssessment(String knowledgeValueAssessment) { this.knowledgeValueAssessment = knowledgeValueAssessment; }

    public List<String> getSchemaGapHints() { return schemaGapHints; }
    public void addSchemaGapHint(String hint) { this.schemaGapHints.add(hint); }

    public List<SubDocument> getSubDocuments() { return subDocuments; }
    public void setSubDocuments(List<SubDocument> subDocuments) { this.subDocuments = subDocuments; }

    public Map<String, String> getSubDocumentSourceMap() { return subDocumentSourceMap; }
    public void setSubDocumentSourceMap(Map<String, String> subDocumentSourceMap) { this.subDocumentSourceMap = subDocumentSourceMap; }

    public CompletableFuture<Integer> getLinksFuture() { return linksFuture; }
    public void setLinksFuture(CompletableFuture<Integer> linksFuture) { this.linksFuture = linksFuture; }

    public CompletableFuture<Void> getReconcilerFuture() { return reconcilerFuture; }
    public void setReconcilerFuture(CompletableFuture<Void> reconcilerFuture) { this.reconcilerFuture = reconcilerFuture; }

    public boolean isWriterPostChecksDone() { return writerPostChecksDone; }
    public void setWriterPostChecksDone(boolean writerPostChecksDone) { this.writerPostChecksDone = writerPostChecksDone; }
    public boolean isBulkIndexed() { return bulkIndexed; }
    public void setBulkIndexed(boolean bulkIndexed) { this.bulkIndexed = bulkIndexed; }
    public void addSchemaPatchHint(String hint) { this.schemaPatchHints.add(hint); }

    public record ParallelAnalysisResult(int chunkIndex, String content, boolean hasError) {}
    public record ExtractedImage(String relativePath, String description) {}
    public record ChartInfo(String relativePath, String description, String source, int page) {}
    public record ConflictAnnotation(String pagePath, String conflictType, String existingClaim, String newClaim, String resolution, String sourceRef) {}
    public record SubDocument(String title, String sourceContent, List<DocumentStructureAnalyzer.Chapter> childChapters) {}
    public record DeprecatedPageInfo(Long pageId, String title, String category, String filePath) {}

    private static final Logger log = LoggerFactory.getLogger(IngestContext.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    public String toParseOutputJson(SourceDO sourceDO) {
        try {
            Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("chunkCount", chunkCount);
            output.put("sourceFormat", sourceDO.getFormat());
            output.put("sourceFilePath", sourceDO.getFilePath());
            output.put("sourceStatus", sourceDO.getStatus());
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("toParseOutputJson failed: {}", e.getMessage());
            return "{}";
        }
    }

    public String toWriteOutputJson() {
        try {
            Map<String, Object> output = new java.util.LinkedHashMap<>();
            ComplianceResult planResult = planComplianceResult;
            boolean planSkipped = planResult != null && planResult.requiresReview();
            if (planSkipped) {
                output.put("status", "skipped");
                output.put("reason", "WritingPlan Schema 违规");
                output.put("complianceSummary", buildComplianceSummary(planResult));
                output.put("complianceViolations", planResult.summarize());
            } else {
                output.put("status", "completed");
                output.put("message", "页面写作完成");
            }
            if (strategy != null) {
                output.put("strategyPreset", strategy.getPreset().name());
                output.put("strategyReasoning", strategy.getReasoning());
            }
            if (documentType != null) {
                output.put("documentType", documentType.name());
            }
            if (totalChapterCount > 0) {
                output.put("totalChapterCount", totalChapterCount);
            }
            if (writingPlanJson != null) {
                output.put("writingPlanJson", writingPlanJson);
            }
            if (summaryPage != null) {
                output.put("summaryPageId", summaryPage.getId());
                output.put("summaryPageTitle", summaryPage.getTitle());
                output.put("summaryPagePath", summaryPage.getFilePath());
            }
            ComplianceResult productResult = productComplianceResult;
            if (productResult != null && productResult.hasViolations()) {
                output.put("complianceSummary", buildComplianceSummary(productResult));
                output.put("complianceViolations", productResult.summarize());
            }
            if (!entityPages.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> entityPageInfos = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, WikiPageDO> entry : entityPages.entrySet()) {
                    java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
                    WikiPageDO pageDO = entry.getValue();
                    info.put("name", entry.getKey());
                    info.put("id", pageDO.getId());
                    info.put("title", pageDO.getTitle());
                    info.put("path", pageDO.getFilePath());
                    info.put("isNew", pageDO.getSourceCount() <= 1);
                    info.put("action", pageDO.getSourceCount() <= 1 ? "新建" : "补充");
                    entityPageInfos.add(info);
                }
                output.put("entityPages", entityPageInfos);
            }
            if (!updatedPages.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> updatedPageInfos = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, WikiPageDO> entry : updatedPages.entrySet()) {
                    java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
                    WikiPageDO pageDO = entry.getValue();
                    info.put("path", entry.getKey());
                    info.put("id", pageDO.getId());
                    info.put("title", pageDO.getTitle());
                    info.put("filePath", pageDO.getFilePath());
                    info.put("action", "更新");
                    updatedPageInfos.add(info);
                }
                output.put("updatedPages", updatedPageInfos);
            }
            if (!chapterPages.isEmpty()) {
                java.util.List<java.util.Map<String, Object>> chapterPageInfos = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, WikiPageDO> entry : chapterPages.entrySet()) {
                    java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
                    WikiPageDO pageDO = entry.getValue();
                    info.put("name", entry.getKey());
                    info.put("id", pageDO.getId());
                    info.put("title", pageDO.getTitle());
                    info.put("path", pageDO.getFilePath());
                    info.put("category", pageDO.getCategory());
                    info.put("isNew", pageDO.getSourceCount() <= 1);
                    info.put("action", pageDO.getSourceCount() <= 1 ? "新建" : "补充");
                    chapterPageInfos.add(info);
                }
                output.put("chapterPages", chapterPageInfos);
            }
            return MAPPER.writeValueAsString(output);
        } catch (Exception e) {
            log.warn("toWriteOutputJson failed: {}", e.getMessage());
            return "{\"status\":\"completed\",\"message\":\"页面写作完成\"}";
        }
    }

    private static Map<String, Object> buildComplianceSummary(ComplianceResult result) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        if (result == null || !result.hasViolations()) {
            summary.put("totalViolations", 0);
            summary.put("groups", List.of());
            summary.put("overallMessage", "规范检查通过");
            return summary;
        }

        List<ViolationGroupSummary> grouped = result.summarizeGrouped();
        int total = result.violations().size();
        int highCount = 0, mediumCount = 0, lowCount = 0;
        for (SchemaViolation v : result.violations()) {
            switch (v.severity()) {
                case HIGH -> highCount++;
                case MEDIUM -> mediumCount++;
                case LOW -> lowCount++;
            }
        }

        summary.put("totalViolations", total);
        summary.put("highCount", highCount);
        summary.put("mediumCount", mediumCount);
        summary.put("lowCount", lowCount);

        List<Map<String, Object>> groups = new java.util.ArrayList<>();
        for (ViolationGroupSummary g : grouped) {
            Map<String, Object> gm = new java.util.LinkedHashMap<>();
            gm.put("type", g.type().name());
            gm.put("typeLabel", switch (g.type()) {
                case CATEGORY -> "分类";
                case PAGE_STRUCTURE -> "页面结构";
                case NAMING -> "命名";
            });
            gm.put("count", g.totalCount());
            gm.put("severity", g.highestSeverity().name());
            gm.put("affectedPages", g.affectedPages());
            gm.put("overview", g.overview());
            groups.add(gm);
        }
        summary.put("groups", groups);

        StringBuilder msg = new StringBuilder();
        msg.append("发现 ").append(total).append(" 处规范偏差");
        List<String> parts = new java.util.ArrayList<>();
        if (highCount > 0) parts.add(highCount + " 处高优");
        if (mediumCount > 0) parts.add(mediumCount + " 处中优");
        if (lowCount > 0) parts.add(lowCount + " 处低优");
        if (!parts.isEmpty()) {
            msg.append("：").append(String.join("、", parts));
        }
        summary.put("overallMessage", msg.toString());

        return summary;
    }

    public static IngestContext reconstructFromSteps(Long scopeId, Long sourceId, Long executionId, String guidance,
                                                      List<ExecutionStepModel> steps, StorageProvider storageProvider,
                                                      SourceDO sourceDO) {
        IngestContext context = new IngestContext(scopeId, sourceId, executionId, guidance);
        String scopeIdStr = String.valueOf(scopeId);

        for (ExecutionStepModel step : steps) {
            if (!"completed".equals(step.getStatus())) continue;
            String rawStepName = step.getStepName();
            String stepName = IngestStep.normalizeStepName(rawStepName);
            String outputData = step.getOutputData();
            if (outputData == null || outputData.isEmpty()) continue;

            try {
                com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(outputData);

                if ("UPLOAD".equals(stepName) || "PARSE_DOCUMENT".equals(rawStepName)) {
                    reconstructParseStep(context, node, sourceDO, storageProvider, scopeIdStr);
                } else if ("ANALYZE".equals(stepName) || "ANALYZE_CHUNKS".equals(rawStepName)) {
                    if (node.has("mergedAnalysis")) {
                        context.setMergedAnalysis(node.get("mergedAnalysis").asText());
                    }
                    if (node.has("metadata")) {
                        context.setMetadataJson(node.get("metadata").isObject() ? node.get("metadata").toString() : node.get("metadata").asText());
                    }
                    restoreDocumentType(context, node);
                    restoreStrategy(context, node);
                } else if ("MERGE_RESULTS".equals(rawStepName) && context.getMergedAnalysis() == null) {
                    context.setMergedAnalysis(outputData);
                } else if ("EXTRACT_METADATA".equals(rawStepName) && context.getMetadataJson() == null) {
                    context.setMetadataJson(outputData);
                } else if ("WRITE".equals(stepName) || "WRITE_SUMMARY".equals(rawStepName)) {
                    reconstructWriteStep(context, node, storageProvider, scopeIdStr);
                }
            } catch (Exception e) {
                log.warn("Failed to parse outputData for step {}: {}", rawStepName, e.getMessage());
                if ("ANALYZE".equals(stepName) && context.getMergedAnalysis() == null) {
                    context.setMergedAnalysis(outputData);
                }
            }
        }

        return context;
    }

    private static void reconstructParseStep(IngestContext context, com.fasterxml.jackson.databind.JsonNode node,
                                              SourceDO sourceDO, StorageProvider storageProvider, String scopeIdStr) {
        if (sourceDO != null && sourceDO.getName() != null) {
            context.setSourceName(sourceDO.getName());
        }
        String sourceFilePath = node.has("sourceFilePath") ? node.get("sourceFilePath").asText() : null;
        String sourceFormat = node.has("sourceFormat") ? node.get("sourceFormat").asText() : null;

        String readPath = "parsed/" + context.getSourceId() + ".parsed.md";
        byte[] parsedBytes = storageProvider.read(scopeIdStr, readPath);
        if (parsedBytes != null) {
            context.setSourceContent(new String(parsedBytes, StandardCharsets.UTF_8));
            context.setParsedContent(context.getSourceContent());
        } else if (sourceFilePath != null && !isBinaryFormat(sourceFormat)) {
            byte[] sourceBytes = storageProvider.read(scopeIdStr, sourceFilePath);
            if (sourceBytes != null) {
                context.setSourceContent(new String(sourceBytes, StandardCharsets.UTF_8));
            }
        }

        if (context.getSourceContent() != null) {
            DocumentChunker chunker = new DocumentChunker();
            List<DocumentChunker.Chunk> chunks = chunker.chunk(context.getSourceContent());
            context.setChunks(chunks);
            context.setChunkCount(chunks.size());

            DocumentStructureAnalyzer.StructureReport report =
                new DocumentStructureAnalyzer().analyze(context.getSourceContent(), chunks, context.getSourceName());
            context.setDocumentType(report.type());
            context.setChapters(report.chapters());
            context.setTotalChapterCount(report.chapters().size());
        }
    }

    private static void reconstructWriteStep(IngestContext context, com.fasterxml.jackson.databind.JsonNode node,
                                              StorageProvider storageProvider, String scopeIdStr) {
        if (node.hasNonNull("writingPlanJson")) {
            context.setWritingPlanJson(node.get("writingPlanJson").asText());
        }
        restoreDocumentType(context, node);
        restoreStrategy(context, node);

        if (node.hasNonNull("summaryPagePath")) {
            WikiPageDO summaryPage = new WikiPageDO();
            if (node.hasNonNull("summaryPageId") && node.get("summaryPageId").isNumber()) {
                summaryPage.setId(node.get("summaryPageId").asLong());
            }
            if (node.hasNonNull("summaryPageTitle")) {
                summaryPage.setTitle(node.get("summaryPageTitle").asText());
            }
            summaryPage.setFilePath(node.get("summaryPagePath").asText());
            context.setSummaryPage(summaryPage);
            loadPageContent(context, storageProvider, scopeIdStr, summaryPage.getFilePath());
        }

        readPageInfos(context, node.get("entityPages"), "name", context.getEntityPages(), storageProvider, scopeIdStr);
        readPageInfos(context, node.get("updatedPages"), "path", context.getUpdatedPages(), storageProvider, scopeIdStr);
        readPageInfos(context, node.get("chapterPages"), "name", context.getChapterPages(), storageProvider, scopeIdStr);
    }

    private static void readPageInfos(IngestContext context, com.fasterxml.jackson.databind.JsonNode arrayNode,
                                       String keyField, ConcurrentHashMap<String, WikiPageDO> target,
                                       StorageProvider storageProvider, String scopeIdStr) {
        if (arrayNode == null || !arrayNode.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode entry : arrayNode) {
            String key = entry.hasNonNull(keyField) ? entry.get(keyField).asText() : null;
            if (key == null || key.isBlank()) continue;
            String filePath = entry.hasNonNull("filePath") ? entry.get("filePath").asText()
                : (entry.hasNonNull("path") ? entry.get("path").asText() : null);
            if (filePath == null || filePath.isBlank()) continue;
            WikiPageDO page = new WikiPageDO();
            if (entry.hasNonNull("id") && entry.get("id").isNumber()) {
                page.setId(entry.get("id").asLong());
            }
            if (entry.hasNonNull("title")) {
                page.setTitle(entry.get("title").asText());
            }
            if (entry.hasNonNull("category")) {
                page.setCategory(entry.get("category").asText());
            }
            page.setFilePath(filePath);
            target.put(key, page);
            loadPageContent(context, storageProvider, scopeIdStr, filePath);
        }
    }

    private static void loadPageContent(IngestContext context, StorageProvider storageProvider,
                                         String scopeIdStr, String pagePath) {
        if (pagePath == null || pagePath.isBlank() || context.getPageContents().containsKey(pagePath)) return;
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, "wiki/" + pagePath);
            if (bytes != null) {
                context.getPageContents().put(pagePath, new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("Failed to load page content for resume: path={}, error={}", pagePath, e.getMessage());
        }
    }

    private static void restoreDocumentType(IngestContext context, com.fasterxml.jackson.databind.JsonNode node) {
        if (node.hasNonNull("documentType")) {
            String typeName = node.get("documentType").asText();
            try {
                context.setDocumentType(DocumentStructureAnalyzer.DocumentType.valueOf(typeName));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown documentType in step output: {}", typeName);
            }
        }
        if (node.hasNonNull("chapterCount")) {
            context.setTotalChapterCount(node.get("chapterCount").asInt());
        }
        if (node.hasNonNull("totalChapterCount")) {
            context.setTotalChapterCount(node.get("totalChapterCount").asInt());
        }
    }

    private static void restoreStrategy(IngestContext context, com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.hasNonNull("strategyPreset")) return;
        String presetName = node.get("strategyPreset").asText();
        ExecutionStrategy strategy = switch (presetName) {
            case "LARGE_POLICY" -> ExecutionStrategy.forLargePolicy();
            case "CHAPTER_BASED" -> ExecutionStrategy.forChapterBased();
            case "TECHNICAL_RICH" -> ExecutionStrategy.forTechnicalRich();
            case "NARRATIVE" -> ExecutionStrategy.forNarrative();
            case "COMPACT" -> ExecutionStrategy.forCompact();
            default -> null;
        };
        if (strategy == null) {
            log.warn("Unknown strategyPreset in step output: {}", presetName);
            return;
        }
        if (node.hasNonNull("strategyReasoning")) {
            strategy.setReasoning(node.get("strategyReasoning").asText());
        }
        context.setStrategy(strategy);
    }

    private static boolean isBinaryFormat(String format) {
        if (format == null) return false;
        String lower = format.toLowerCase();
        return "pdf".equals(lower) || "docx".equals(lower) || "xlsx".equals(lower)
            || "pptx".equals(lower) || "doc".equals(lower) || "xls".equals(lower)
            || "ppt".equals(lower);
    }
}