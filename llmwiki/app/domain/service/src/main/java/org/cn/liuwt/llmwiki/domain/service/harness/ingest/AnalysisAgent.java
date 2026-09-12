package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.InformationCatalog.CrossChapterRelation;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(AnalysisAgent.class);

    @Autowired
    private AnalyzerAgent analyzerAgent;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    @Value("${llmwiki.llm.barrier.acquire-timeout-ms:120000}")
    private long barrierAcquireTimeoutMs;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService subDocExecutor = Executors.newFixedThreadPool(4);

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down subDocExecutor");
        subDocExecutor.shutdown();
        try {
            if (!subDocExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                subDocExecutor.shutdownNow();
                if (!subDocExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("subDocExecutor did not reach quiescence after shutdownNow");
                }
            }
        } catch (InterruptedException e) {
            subDocExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public int process(IngestContext context, Long executionId, Long stepId) {
        int totalTokens = analyzerAgent.analyze(context, executionId, stepId);

        detectAndAnalyzeSubDocuments(context);

        resolveEntityActions(context);
        buildInformationCatalogAndDossiers(context);
        buildEntityRelationshipSummary(context);
        assessCompleteness(context);
        detectSchemaGaps(context);
        return totalTokens;
    }

    private void detectAndAnalyzeSubDocuments(IngestContext context) {
        if (chatClient == null || !chatClient.isAvailable()) return;

        List<DocumentStructureAnalyzer.Chapter> chapters = context.getChapters();
        String sourceContent = context.getSourceContent();
        List<IngestContext.SubDocument> subDocs = SubDocumentDetector.detect(chapters, sourceContent);

        if (subDocs == null || subDocs.isEmpty()) {
            log.info("No sub-documents detected, using standard analysis path, scopeId={}", context.getScopeId());
            return;
        }

        context.setSubDocuments(subDocs);
        log.info("Detected {} sub-documents, running parallel sub-analysis, scopeId={}", subDocs.size(), context.getScopeId());

        String pagesContext = context.getPagesContext();
        String docTitle = extractJsonField(context.getMetadataJson(), "title");

        List<CompletableFuture<SubDocResult>> futures = new ArrayList<>();
        for (int i = 0; i < subDocs.size(); i++) {
            IngestContext.SubDocument subDoc = subDocs.get(i);
            int idx = i;
            CompletableFuture<SubDocResult> future = CompletableFuture.supplyAsync(() -> {
                if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.ANALYZE, barrierAcquireTimeoutMs)) {
                    log.warn("Sub-document analysis barrier timeout for '{}', skipping", subDoc.title());
                    return new SubDocResult(idx, subDoc.title(), null);
                }
                try {
                    String prompt = schemaInjector.prependForAnalyzer(context.getScopeId(),
                        PromptRegistry.forIngest().subDocumentAnalyze(docTitle != null ? docTitle : "文档汇编"));
                    String userMessage = PromptTemplate.buildSourceAndAnalysisUserMessage(
                        subDoc.sourceContent(), pagesContext != null ? pagesContext : "");
                    String response = chatClient.chat(prompt, userMessage);
                    return new SubDocResult(idx, subDoc.title(), response);
                } catch (Exception e) {
                    log.warn("Sub-document analysis failed for '{}': {}", subDoc.title(), e.getMessage());
                    return new SubDocResult(idx, subDoc.title(), null);
                } finally {
                    llmBarrier.release(LlmConcurrencyBarrier.Bucket.ANALYZE);
                }
            }, subDocExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        ArrayNode allSubEntities = mapper.createArrayNode();
        Map<String, String> sourceMap = new LinkedHashMap<>();

        for (CompletableFuture<SubDocResult> future : futures) {
            SubDocResult result;
            try {
                result = future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                continue;
            }
            if (result.jsonResponse() == null) continue;

            try {
                String cleaned = cleanJson(result.jsonResponse());
                JsonNode node = mapper.readTree(cleaned);
                if (node.has("entities") && node.get("entities").isArray()) {
                    for (JsonNode entity : node.get("entities")) {
                        ObjectNode entityObj = (ObjectNode) entity;
                        entityObj.put("subDocument", result.title());
                        allSubEntities.add(entityObj);

                        String name = entity.has("name") ? entity.get("name").asText() : "";
                        if (!name.isEmpty()) {
                            sourceMap.put(name, subDocs.get(result.index()).sourceContent());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse sub-document analysis for '{}': {}", result.title(), e.getMessage());
            }
        }

        if (allSubEntities.size() > 0) {
            mergeSubDocumentEntities(context, allSubEntities);
        }
        if (!sourceMap.isEmpty()) {
            context.setSubDocumentSourceMap(sourceMap);
            log.info("SubDocumentSourceMap built: {} entity→subDoc mappings, scopeId={}", sourceMap.size(), context.getScopeId());
        }
    }

    private record SubDocResult(int index, String title, String jsonResponse) {}

    private void mergeSubDocumentEntities(IngestContext context, ArrayNode subEntities) {
        try {
            String metadataJson = context.getMetadataJson();
            if (metadataJson == null || metadataJson.isEmpty()) return;

            JsonNode root = mapper.readTree(cleanJson(metadataJson));
            if (!root.has("entities") || !root.get("entities").isArray()) return;

            ArrayNode mainEntities = (ArrayNode) root.get("entities");
            java.util.Set<String> existingNames = new java.util.HashSet<>();
            for (JsonNode e : mainEntities) {
                if (e.has("name")) existingNames.add(e.get("name").asText().toLowerCase());
            }

            int added = 0;
            for (JsonNode subEntity : subEntities) {
                String name = subEntity.has("name") ? subEntity.get("name").asText() : "";
                if (name.isEmpty() || existingNames.contains(name.toLowerCase())) continue;
                String significance = subEntity.has("significance") ? subEntity.get("significance").asText() : "contextual";
                if ("contextual".equals(significance)) continue;
                mainEntities.add(subEntity);
                existingNames.add(name.toLowerCase());
                added++;
            }

            if (added > 0) {
                context.setMetadataJson(mapper.writeValueAsString(root));
                log.info("Merged {} sub-document entities into main metadata, scopeId={}", added, context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("mergeSubDocumentEntities failed: {}", e.getMessage());
        }
    }

    private String extractJsonField(String json, String field) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonNode node = mapper.readTree(cleanJson(json));
            return node.has(field) ? node.get(field).asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    void resolveEntityActions(IngestContext context) {
        String metadataJson = context.getMetadataJson();
        if (metadataJson == null || metadataJson.isEmpty()) return;
        Long scopeId = context.getScopeId();

        try {
            JsonNode root = mapper.readTree(cleanJson(metadataJson));
            if (!root.has("entities") || !root.get("entities").isArray()) return;

            Map<String, WikiPageDO> activePagesByTitle = loadActivePagesByTitle(scopeId);

            ArrayNode entities = (ArrayNode) root.get("entities");
            for (int i = 0; i < entities.size(); i++) {
                JsonNode entity = entities.get(i);
                String name = entity.has("name") ? entity.get("name").asText() : "";
                if (name.isEmpty()) continue;

                WikiPageDO existing = activePagesByTitle.get(name.toLowerCase());

                ObjectNode objNode = (ObjectNode) entity;
                if (existing != null) {
                    objNode.put("action", "补充");
                    objNode.put("isNew", false);
                    objNode.put("id", existing.getId());
                    objNode.put("path", existing.getFilePath());
                } else {
                    objNode.put("action", "新建");
                    objNode.put("isNew", true);
                }
            }

            context.setMetadataJson(mapper.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("resolveEntityActions failed: {}", e.getMessage());
        }
    }

    private Map<String, WikiPageDO> loadActivePagesByTitle(Long scopeId) {
        Map<String, WikiPageDO> index = new java.util.HashMap<>();
        List<WikiPageDO> pages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getLifecycleStatus, "ACTIVE")
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath)
        );
        for (WikiPageDO page : pages) {
            if (page.getTitle() != null) {
                index.putIfAbsent(page.getTitle().toLowerCase(), page);
            }
        }
        return index;
    }

    void buildInformationCatalogAndDossiers(IngestContext context) {
        try {
            String metadataJson = context.getMetadataJson();
            if (metadataJson == null || metadataJson.isEmpty()) return;

            JsonNode root = mapper.readTree(cleanJson(metadataJson));

            List<Map<String, String>> entities = new ArrayList<>();
            if (root.has("entities") && root.get("entities").isArray()) {
                for (JsonNode entityNode : root.get("entities")) {
                    Map<String, String> entity = new LinkedHashMap<>();
                    if (entityNode.has("name")) entity.put("name", entityNode.get("name").asText());
                    if (entityNode.has("type")) entity.put("type", entityNode.get("type").asText());
                    if (entityNode.has("aliases")) {
                        JsonNode aliasesNode = entityNode.get("aliases");
                        if (aliasesNode.isArray()) {
                            List<String> aliasList = new ArrayList<>();
                            for (JsonNode a : aliasesNode) {
                                aliasList.add(a.asText());
                            }
                            entity.put("aliases", String.join(",", aliasList));
                        } else {
                            entity.put("aliases", aliasesNode.asText());
                        }
                    }
                    if (entityNode.has("significance")) entity.put("significance", entityNode.get("significance").asText());
                    entities.add(entity);
                }
            }

            if (entities.isEmpty()) return;

            InformationCatalogBuilder catalogBuilder = new InformationCatalogBuilder();
            InformationCatalog catalog = catalogBuilder.build(
                context.getSourceContent(),
                context.getChapters(),
                entities
            );
            context.setInformationCatalog(catalog);

            EntityDossierBuilder dossierBuilder = new EntityDossierBuilder();
            Map<String, EntityDossier> dossiers = dossierBuilder.buildAll(
                catalog,
                context.getSourceContent(),
                entities,
                context.getStrategy()
            );
            context.setEntityDossiers(dossiers);

            log.info("InformationCatalog and EntityDossiers built: {} entities indexed, {} dossiers created, scopeId={}",
                catalog.entityIndex().size(), dossiers.size(), context.getScopeId());
        } catch (Exception e) {
            log.warn("Failed to build InformationCatalog/EntityDossiers, falling back to chunk-based approach: {}", e.getMessage());
        }
    }

    void buildEntityRelationshipSummary(IngestContext context) {
        try {
            Map<String, EntityDossier> dossiers = context.getEntityDossiers();
            InformationCatalog catalog = context.getInformationCatalog();
            if ((dossiers == null || dossiers.isEmpty()) && catalog == null) return;

            StringBuilder sb = new StringBuilder();
            sb.append("## 实体关系图谱\n\n");

            if (dossiers != null && !dossiers.isEmpty()) {
                sb.append("### 实体关联\n");
                for (Map.Entry<String, EntityDossier> entry : dossiers.entrySet()) {
                    EntityDossier dossier = entry.getValue();
                    if (dossier.relatedEntities() == null || dossier.relatedEntities().isEmpty()) continue;
                    sb.append("- **").append(entry.getKey()).append("**: ");
                    List<String> relParts = new ArrayList<>();
                    for (String related : dossier.relatedEntities()) {
                        String hint = dossier.relationshipHints() != null ? dossier.relationshipHints().get(related) : null;
                        relParts.add(hint != null ? related + "（" + hint + "）" : related);
                    }
                    sb.append(String.join("、", relParts)).append("\n");
                }
                sb.append("\n");
            }

            if (catalog != null && catalog.crossChapterRelations() != null && !catalog.crossChapterRelations().isEmpty()) {
                sb.append("### 跨章节关系\n");
                int count = 0;
                for (CrossChapterRelation rel : catalog.crossChapterRelations()) {
                    if (count >= 20) break;
                    sb.append("- ").append(rel.entityName())
                        .append(" [").append(relationTypeLabel(rel.relationType())).append("] ")
                        .append(rel.definedInSection()).append(" → ").append(rel.referencedInSection())
                        .append("\n");
                    count++;
                }
                sb.append("\n");
            }

            if (catalog != null && catalog.coOccurrenceGraph() != null && !catalog.coOccurrenceGraph().isEmpty()) {
                sb.append("### 实体共现\n");
                int count = 0;
                for (Map.Entry<String, java.util.Set<String>> entry : catalog.coOccurrenceGraph().entrySet()) {
                    if (entry.getValue().size() < 2 || count >= 15) continue;
                    sb.append("- ").append(entry.getKey()).append(" ↔ ")
                        .append(String.join("、", entry.getValue())).append("\n");
                    count++;
                }
            }

            String summary = sb.toString();
            context.setEntityRelationshipSummary(summary);
            log.info("Entity relationship summary built: {} chars, scopeId={}", summary.length(), context.getScopeId());
        } catch (Exception e) {
            log.warn("buildEntityRelationshipSummary failed: {}", e.getMessage());
        }
    }

    private String relationTypeLabel(Object relationType) {
        if (relationType == null) return "关联";
        String type = relationType.toString();
        return switch (type) {
            case "DEFINED_IN" -> "定义于";
            case "REFERENCED_BY" -> "被引用";
            case "DEPENDS_ON" -> "依赖";
            case "SUPERSEDES" -> "替代";
            default -> "关联";
        };
    }

    void assessCompleteness(IngestContext context) {
        String metadataJson = context.getMetadataJson();
        if (metadataJson == null || metadataJson.isEmpty()) {
            context.setCompletenessScore(0.0);
            return;
        }

        try {
            JsonNode root = mapper.readTree(cleanJson(metadataJson));
            if (!root.has("entities") || !root.get("entities").isArray()) {
                context.setCompletenessScore(1.0);
                return;
            }

            ArrayNode entitiesNode = (ArrayNode) root.get("entities");
            int totalEntities = entitiesNode.size();
            if (totalEntities == 0) {
                context.setCompletenessScore(1.0);
                return;
            }

            Map<String, EntityDossier> dossiers = context.getEntityDossiers();
            double programmaticScore = 0.5;
            if (dossiers != null && !dossiers.isEmpty()) {
                int withDefinitions = 0;
                for (JsonNode entityNode : entitiesNode) {
                    String name = entityNode.has("name") ? entityNode.get("name").asText() : "";
                    if (name.isEmpty()) continue;
                    EntityDossier dossier = dossiers.get(name);
                    if (dossier != null && dossier.definitionText() != null && !dossier.definitionText().isEmpty()) {
                        withDefinitions++;
                    }
                }
                programmaticScore = (double) withDefinitions / totalEntities;
                log.info("Completeness assessment: {}/{} entities with definitions, score={}, scopeId={}",
                    withDefinitions, totalEntities, programmaticScore, context.getScopeId());
            }

            double llmConfidenceScore = 1.0;
            if (root.has("coverageAssessment")) {
                JsonNode coverage = root.get("coverageAssessment");
                String confidence = coverage.has("confidence") ? coverage.get("confidence").asText() : "medium";
                llmConfidenceScore = switch (confidence) {
                    case "high" -> 1.0;
                    case "medium" -> 0.8;
                    case "low" -> 0.5;
                    default -> 0.7;
                };

                if (coverage.has("uncoveredTopics") && coverage.get("uncoveredTopics").isArray()) {
                    int uncoveredCount = coverage.get("uncoveredTopics").size();
                    if (uncoveredCount > 0) {
                        for (JsonNode topic : coverage.get("uncoveredTopics")) {
                            context.addSchemaGapHint("分析阶段发现未覆盖主题: " + topic.asText());
                        }
                        llmConfidenceScore = Math.max(0.3, llmConfidenceScore - uncoveredCount * 0.1);
                    }
                }
            }

            double combinedScore = programmaticScore * 0.4 + llmConfidenceScore * 0.6;
            context.setCompletenessScore(combinedScore);

            if (combinedScore < 0.5) {
                log.warn("Low completeness (score={}), programmatic={}, llmConfidence={}, scopeId={}",
                    combinedScore, programmaticScore, llmConfidenceScore, context.getScopeId());
            }
        } catch (Exception e) {
            log.warn("assessCompleteness failed: {}", e.getMessage());
            context.setCompletenessScore(0.0);
        }
    }

    void detectSchemaGaps(IngestContext context) {
        String metadataJson = context.getMetadataJson();
        if (metadataJson == null || metadataJson.isEmpty()) return;

        try {
            JsonNode root = mapper.readTree(cleanJson(metadataJson));

            if (root.has("category")) {
                String category = root.get("category").asText();
                if (category != null && !category.isEmpty() && !"未分类".equals(category)) {
                    String schemaHeader = schemaInjector.buildSchemaHeader(context.getScopeId());
                    if (schemaHeader != null && !schemaHeader.isEmpty() && !schemaHeader.contains(category)) {
                        context.addSchemaGapHint("Document category '" + category + "' not found in current schema");
                    }
                }
            }

            DocumentStructureAnalyzer.DocumentType docType = context.getDocumentType();
            if (docType == DocumentStructureAnalyzer.DocumentType.STRUCTURED) {
                List<DocumentStructureAnalyzer.Chapter> chapters = context.getChapters();
                if (chapters == null || chapters.isEmpty()) {
                    context.addSchemaGapHint("Document classified as STRUCTURED but no chapters were detected");
                }
            }
        } catch (Exception e) {
            log.warn("detectSchemaGaps failed: {}", e.getMessage());
        }
    }

    private String cleanJson(String json) {
        if (json == null || json.isEmpty()) return json;
        int start = json.indexOf("{");
        int end = json.lastIndexOf("}") + 1;
        return (start >= 0 && end > start) ? json.substring(start, end) : json;
    }
}
