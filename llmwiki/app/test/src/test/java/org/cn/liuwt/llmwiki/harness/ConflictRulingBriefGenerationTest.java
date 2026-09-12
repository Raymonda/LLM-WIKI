package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictResolutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictRoutingService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictRulingBriefGenerationTest {

    @Mock
    private LintFindingService lintFindingService;

    @Mock
    private ConflictRoutingService conflictRoutingService;

    @Mock
    private ConflictReviewService conflictReviewService;

    @Mock
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Mock
    private SchemaInjector schemaInjector;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private WikiPageMapper wikiPageMapper;

    private ConflictDomainService newService() {
        ConflictDomainService service = new ConflictDomainService();
        inject(service, "lintFindingService", lintFindingService);
        inject(service, "conflictRoutingService", conflictRoutingService);
        inject(service, "conflictReviewService", conflictReviewService);
        inject(service, "llmConcurrencyBarrier", llmConcurrencyBarrier);
        inject(service, "schemaInjector", schemaInjector);
        inject(service, "storageProvider", storageProvider);
        inject(service, "wikiPageMapper", wikiPageMapper);
        return service;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = ConflictDomainService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            fail("Failed to inject " + field + ": " + e.getMessage());
        }
    }

    private static LintFindingDO conflictFinding() {
        LintFindingDO finding = new LintFindingDO();
        finding.setId(31L);
        finding.setScopeId(1L);
        finding.setFindingType("conflict");
        finding.setStatus("open");
        finding.setAssetId(11L);
        finding.setPagePath("entities/trump.md");
        finding.setHandlingMethod("ruling_brief");
        finding.setTitle("税率矛盾");
        finding.setDetail("页面A: entities/trump.md\n声明A: 税率10%\n页面B: entities/biden.md\n声明B: 税率15%");
        finding.setExtra("{\"relatedPageId\":42,\"relatedPagePath\":\"entities/biden.md\","
            + "\"conflictType\":\"fact_conflict\",\"claimA\":\"税率10%\",\"claimB\":\"税率15%\"}");
        return finding;
    }

    private static WikiPageDO page(Long id, String path, String title) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle(title);
        return p;
    }

    private static ConflictRoutingService.ConflictRoute route(ConflictResolutionStrategy strategy, String reason) {
        return new ConflictRoutingService.ConflictRoute(strategy, strategy.getDefaultAutoLevel(), reason);
    }

    @Test
    void shouldReturnNotFoundWhenFindingMissing() {
        when(lintFindingService.getFinding(31L)).thenReturn(null);

        ConflictDomainService.RulingResult result =
            newService().generateRulingBriefForFinding(1L, null, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.NOT_FOUND, result.outcome());
        verify(conflictRoutingService, never()).route(any(), any(), any());
    }

    @Test
    void shouldReturnAlreadyGeneratedWhenBriefExists() {
        LintFindingDO finding = conflictFinding();
        finding.setRulingBriefJson("{\"action\":\"merge\"}");
        when(lintFindingService.getFinding(31L)).thenReturn(finding);

        ConflictDomainService.RulingResult result =
            newService().generateRulingBriefForFinding(1L, null, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.ALREADY_GENERATED, result.outcome());
        verify(conflictRoutingService, never()).route(any(), any(), any());
    }

    @Test
    void shouldDeferWhenRouteSaysDefer() {
        when(lintFindingService.getFinding(31L)).thenReturn(conflictFinding());
        when(conflictRoutingService.route(any(), any(), any()))
            .thenReturn(route(ConflictResolutionStrategy.ADJUDICATE, "需人工裁决，暂缓"));

        ConflictDomainService.RulingResult result =
            newService().generateRulingBriefForFinding(1L, null, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.DEFERRED, result.outcome());
        assertEquals("需人工裁决，暂缓", result.reason());
        verify(lintFindingService).annotateDeferred(31L);
    }

    @Test
    void shouldReturnAiUnavailableWhenClientMissing() {
        when(lintFindingService.getFinding(31L)).thenReturn(conflictFinding());
        when(conflictRoutingService.route(any(), any(), any()))
            .thenReturn(route(ConflictResolutionStrategy.SOURCE_PRIORITY, "高优先级来源胜出"));

        ConflictDomainService.RulingResult result =
            newService().generateRulingBriefForFinding(1L, null, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.AI_UNAVAILABLE, result.outcome());
    }

    @Test
    void shouldReturnBusyWhenBarrierTimeout() {
        when(lintFindingService.getFinding(31L)).thenReturn(conflictFinding());
        when(conflictRoutingService.route(any(), any(), any()))
            .thenReturn(route(ConflictResolutionStrategy.SOURCE_PRIORITY, "高优先级来源胜出"));
        LlmClient chatClient = mock(LlmClient.class);
        when(chatClient.isAvailable()).thenReturn(true);
        when(llmConcurrencyBarrier.tryAcquire(eq(LlmConcurrencyBarrier.Bucket.LINT), anyLong()))
            .thenReturn(false);
        ConflictDomainService service = newService();
        inject(service, "chatClient", chatClient);

        ConflictDomainService.RulingResult result =
            service.generateRulingBriefForFinding(1L, null, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.BUSY, result.outcome());
        verify(llmConcurrencyBarrier, never()).release(any());
    }

    @Test
    void shouldGenerateBriefAndCreateReviewOnSuccess() {
        when(lintFindingService.getFinding(31L)).thenReturn(conflictFinding());
        WikiPageDO fromPage = page(11L, "entities/trump.md", "特朗普");
        fromPage.setCategory("entities");
        when(wikiPageMapper.selectOne(any())).thenReturn(fromPage);
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictRoutingService.route(eq("entities"), any(), any()))
            .thenReturn(route(ConflictResolutionStrategy.SOURCE_PRIORITY, "高优先级来源胜出"));
        LlmClient chatClient = mock(LlmClient.class);
        when(chatClient.isAvailable()).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn("{\"action\":\"merge\",\"brief\":\"合并双方主张\"}");
        when(llmConcurrencyBarrier.tryAcquire(eq(LlmConcurrencyBarrier.Bucket.LINT), anyLong()))
            .thenReturn(true);
        when(storageProvider.read(eq("1"), eq("wiki/entities/trump.md")))
            .thenReturn("# 特朗普".getBytes(StandardCharsets.UTF_8));
        when(storageProvider.read(eq("1"), eq("wiki/entities/biden.md")))
            .thenReturn("# 拜登".getBytes(StandardCharsets.UTF_8));
        when(schemaInjector.prependForLint(eq(1L), anyString())).thenReturn("PROMPT");
        ConflictDomainService service = newService();
        inject(service, "chatClient", chatClient);

        ConflictDomainService.RulingResult result =
            service.generateRulingBriefForFinding(1L, 200L, 31L, null);

        assertEquals(ConflictDomainService.RulingBriefOutcome.GENERATED, result.outcome());
        verify(lintFindingService).setRulingBrief(31L, "{\"action\":\"merge\",\"brief\":\"合并双方主张\"}");
        verify(lintFindingService).updateStatus(31L, "awaiting_approval");
        verify(conflictReviewService).createReview(eq(1L), eq(200L), eq("LINT"),
            any(WikiPageDO.class), any(WikiPageDO.class), any(), any(), any(), any());
        verify(llmConcurrencyBarrier).release(LlmConcurrencyBarrier.Bucket.LINT);
    }
}
