package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Component
public class StaleRefreshService {

    private static final Logger log = LoggerFactory.getLogger(StaleRefreshService.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private SearchService searchService;

    public void refreshPage(Long scopeId, Long pageId, Long sourceId) {
        if (chatClient == null || !chatClient.isAvailable()) {
            throw new RuntimeException("AI not available, cannot refresh stale page");
        }

        WikiPageDO page = wikiPageMapper.selectById(pageId);
        if (page == null) {
            throw new RuntimeException("Page not found: id=" + pageId);
        }

        String scopeIdStr = String.valueOf(scopeId);
        String pageStoragePath = "wiki/" + page.getFilePath();

        byte[] existingBytes = storageProvider.read(scopeIdStr, pageStoragePath);
        if (existingBytes == null) {
            throw new RuntimeException("Page file not found in storage: " + pageStoragePath);
        }
        String existingContent = new String(existingBytes, StandardCharsets.UTF_8);

        String sourceContent = readSourceContent(scopeIdStr, sourceId);
        if (sourceContent == null || sourceContent.isBlank()) {
            throw new RuntimeException("Source content not found or empty: sourceId=" + sourceId);
        }

        String mergePrompt = schemaInjector.prependForWriter(scopeId,
            PromptRegistry.forIngest().mergeIntoExistingPage(existingContent, sourceContent, "", "{}", "更新"));

        boolean acquired = false;
        String merged;
        try {
            acquired = llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 60_000);
            if (!acquired) {
                throw new RuntimeException("LLM concurrency barrier acquire timeout for stale refresh: pageId=" + pageId);
            }
            merged = chatClient.chat(mergePrompt);
        } finally {
            if (acquired) {
                llmBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
            }
        }

        merged = PromptTemplate.stripConversationalFiller(stripMarkdownFences(merged));
        merged = linkWritingService.sanitizeSourceLinks(merged);
        merged = linkWritingService.sanitizeWikiLinks(merged, scopeId);

        storageProvider.write(scopeIdStr, pageStoragePath, merged.getBytes(StandardCharsets.UTF_8));

        page.setContentUpdatedAt(LocalDateTime.now());
        page.setHealthStatus("healthy");
        wikiPageMapper.updateById(page);

        syncPageToIndex(page, scopeId, scopeIdStr, merged);

        lintFindingService.resolvePageFindingsOnIngest(scopeId, pageId);
        wikiFileService.recalcPageHealthStatus(scopeId, pageId);

        log.info("Stale page refreshed: scopeId={}, pageId={}, pagePath={}, sourceId={}", scopeId, pageId, page.getFilePath(), sourceId);
    }

    private String readSourceContent(String scopeIdStr, Long sourceId) {
        SourceDO sourceDO = sourceMapper.selectById(sourceId);
        if (sourceDO == null) return null;

        String parsedPath = "parsed/" + sourceId + ".parsed.md";
        byte[] parsedBytes = storageProvider.read(scopeIdStr, parsedPath);
        if (parsedBytes != null && parsedBytes.length > 0) {
            return new String(parsedBytes, StandardCharsets.UTF_8);
        }

        String rawPath = sourceDO.getFilePath();
        if (rawPath != null && !rawPath.isBlank()) {
            byte[] rawBytes = storageProvider.read(scopeIdStr, rawPath);
            if (rawBytes != null) {
                return new String(rawBytes, StandardCharsets.UTF_8);
            }
        }

        return null;
    }

    private String stripMarkdownFences(String content) {
        if (content == null) return "";
        content = content.trim();
        if (content.startsWith("```markdown")) content = content.substring("```markdown".length());
        else if (content.startsWith("```md")) content = content.substring("```md".length());
        else if (content.startsWith("```")) content = content.substring(3);
        if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
        return content.trim();
    }

    private void syncPageToIndex(WikiPageDO pageDO, Long scopeId, String scopeIdStr, String content) {
        try {
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.warn("syncPageToIndex failed for stale refresh: pageId={}, error={}", pageDO.getId(), e.getMessage());
        }
    }
}
