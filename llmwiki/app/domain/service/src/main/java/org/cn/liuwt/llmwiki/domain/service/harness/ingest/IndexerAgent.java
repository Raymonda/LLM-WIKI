package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictRoutingService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AsyncSchemaPatchService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.domain.service.harness.crossref.CrossRefDomainService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class IndexerAgent {

    private static final Logger log = LoggerFactory.getLogger(IndexerAgent.class);

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private AsyncSchemaPatchService asyncSchemaPatchService;

    @Autowired
    private CrossRefDomainService crossRefDomainService;

    @Autowired
    private ConflictRoutingService conflictRoutingService;

    @Autowired
    private SchemaSection6Parser schemaSection6Parser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public CompletableFuture<Integer> startLinksGeneration(IngestContext context) {
        return startLinksGeneration(context, null);
    }

    public CompletableFuture<Integer> startLinksGeneration(IngestContext context, String pageInventory) {
        Long scopeId = context.getScopeId();

        return crossRefDomainService.generateLinksForIngestAsync(
            context, scopeId, context.getExecutionId());
    }

    public int index(IngestContext context) {
        return index(context, null);
    }

    public int index(IngestContext context, CompletableFuture<Integer> preStartedLinksFuture) {
        Long scopeId = context.getScopeId();
        Long sourceId = context.getSourceId();
        String metadataJson = context.getMetadataJson();
        int tokensUsed = 0;

        CompletableFuture<Integer> linksFuture = preStartedLinksFuture != null
            ? preStartedLinksFuture
            : startLinksGeneration(context);

        List<CompletableFuture<Void>> conflictFutures = dispatchConflictRouting(scopeId, context);

        String patchSummary = buildPatchSummary(metadataJson, context);
        asyncSchemaPatchService.proposeAsync(
            scopeId,
            context.getExecutionId(),
            SchemaPatchModel.SourceType.INGEST,
            patchSummary
        );

        SourceDO sourceDO = sourceMapper.selectById(sourceId);
        if (sourceDO != null) {
            sourceDO.setStatus("processed");
            sourceMapper.updateById(sourceDO);
        }

        try {
            tokensUsed += linksFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("UPDATE_LINKS async wait failed: {}", e.getMessage());
        }

        for (CompletableFuture<Void> cf : conflictFutures) {
            try {
                cf.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Conflict routing future timeout: {}", e.getMessage());
            }
        }

        return tokensUsed;
    }

    private List<CompletableFuture<Void>> dispatchConflictRouting(Long scopeId, IngestContext context) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<IngestContext.ConflictAnnotation> annotations = context.getConflictAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return futures;
        }

        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);

        for (IngestContext.ConflictAnnotation ann : annotations) {
            String pagePath = ann.pagePath();
            if (pagePath == null || pagePath.isBlank()) continue;

            WikiPageDO existingPage = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, pagePath)
            );
            if (existingPage == null) {
                log.debug("dispatchConflictRouting: page not found for path={}", pagePath);
                continue;
            }

            WikiPageDO newPageDO = context.getSummaryPage();
            if (newPageDO == null) {
                String summaryPath = generatePagePath(context.getMetadataJson());
                newPageDO = wikiPageMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, scopeId)
                        .eq(WikiPageDO::getFilePath, summaryPath)
                );
            }
            if (newPageDO == null) continue;

            String category = existingPage.getCategory();
            String aiHint = ann.resolution();
            ConflictRoutingService.ConflictRoute route = conflictRoutingService.route(
                category, aiHint, rulesConfig);

            CompletableFuture<Void> future = conflictRoutingService.dispatchIngestConflict(
                scopeId, context.getExecutionId(), newPageDO, existingPage,
                ann.conflictType(), route);
            futures.add(future);

            log.info("Ingest conflict routed: {} ↔ {}, strategy={}, autoLevel={}",
                newPageDO.getTitle(), existingPage.getTitle(),
                route.strategy().getKey(), route.autoLevel());
        }

        return futures;
    }

    private String buildPatchSummary(String metadataJson, IngestContext context) {
        StringBuilder summary = new StringBuilder();
        summary.append("【新建主摘要】\n").append(metadataJson == null ? "(无)" : metadataJson).append("\n\n");

        Map<String, String> pageContents = context.getPageContents();
        if (!pageContents.isEmpty()) {
            String summaryPath = generatePagePath(metadataJson);
            String summaryContent = pageContents.get(summaryPath);
            if (summaryContent != null && !summaryContent.isBlank()) {
                summary.append("【主摘要正文片段】\n")
                    .append(summaryContent.length() > 1500 ? summaryContent.substring(0, 1500) + "..." : summaryContent)
                    .append("\n\n");
            }

            List<String> otherKeys = pageContents.keySet().stream()
                .filter(k -> !k.equals(summaryPath))
                .toList();
            if (!otherKeys.isEmpty()) {
                summary.append("【关联页面更新摘要】（").append(otherKeys.size()).append(" 个页面）\n");
                for (String key : otherKeys) {
                    String content = pageContents.get(key);
                    if (content != null && !content.isBlank()) {
                        summary.append("--- ").append(key).append(" ---\n")
                            .append(content.length() > 800 ? content.substring(0, 800) + "..." : content)
                            .append("\n");
                    }
                }
            }
        }
        return summary.toString();
    }

    private String generatePagePath(String metadataJson) {
        String title = extractJsonField(metadataJson, "title");
        if (title == null || title.isEmpty()) title = "untitled";
        String normalized = title.toLowerCase();
        String sanitized = normalized.replaceAll("[^a-z0-9\\u4e00-\\u9fff_-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        if (sanitized.startsWith("-")) sanitized = sanitized.substring(1);
        if (sanitized.endsWith("-")) sanitized = sanitized.substring(0, sanitized.length() - 1);
        if (sanitized.isEmpty()) sanitized = "untitled";
        return "pages/" + sanitized + ".md";
    }

    private String extractJsonField(String json, String field) {
        if (json == null || json.isEmpty()) return "";
        try {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}") + 1;
            String cleanJson = (start >= 0 && end > start) ? json.substring(start, end) : json;
            JsonNode node = objectMapper.readTree(cleanJson);
            if (node.has(field)) return node.get(field).asText();
        } catch (Exception e) {
            log.debug("extractJsonField failed for '{}': {}", field, e.getMessage());
        }
        return "";
    }
}