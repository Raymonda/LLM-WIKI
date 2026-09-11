package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.service.lint.LintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictRulingExecutionTest {

    @Mock
    private LintFindingService lintFindingService;

    @Mock
    private ConflictReviewService conflictReviewService;

    @Mock
    private WikiPageMapper wikiPageMapper;

    private LintService newService() {
        LintService service = new LintService();
        inject(service, "lintFindingService", lintFindingService);
        inject(service, "conflictReviewService", conflictReviewService);
        inject(service, "wikiPageMapper", wikiPageMapper);
        return service;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = LintService.class.getDeclaredField(field);
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

    private static ConflictReviewDO review(Long id, String status) {
        ConflictReviewDO r = new ConflictReviewDO();
        r.setId(id);
        r.setStatus(status);
        return r;
    }

    @Test
    void shouldCreateReviewAndExecuteWhenNoPendingReviewExists() {
        LintFindingDO finding = conflictFinding();
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(null);
        when(conflictReviewService.createReview(eq(1L), isNull(), eq("LINT"),
            any(WikiPageDO.class), any(WikiPageDO.class), eq("fact_conflict"),
            eq("manual"), eq("手动裁决"), anyString())).thenReturn(55L);
        when(conflictReviewService.executeRuling(eq(55L), isNull(), eq("merge"), anyString()))
            .thenReturn(review(55L, "executed"));

        Map<String, Object> result = newService().executeConflictRuling(1L, 31L, "merge");

        assertEquals("auto_resolved", result.get("status"));
        assertEquals(55L, result.get("reviewId"));
        verify(conflictReviewService).createReview(eq(1L), isNull(), eq("LINT"),
            any(WikiPageDO.class), any(WikiPageDO.class), eq("fact_conflict"),
            eq("manual"), eq("手动裁决"), anyString());
        verify(conflictReviewService).executeRuling(eq(55L), isNull(), eq("merge"), anyString());
        verify(lintFindingService).autoResolve(31L, "ruling_brief");
    }

    @Test
    void shouldThrowWhenPagePairUnresolvable() {
        LintFindingDO finding = conflictFinding();
        finding.setAssetId(null);
        finding.setExtra("{}");
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> newService().executeConflictRuling(1L, 31L, "merge"));

        assertEquals("CONFLICT_004", ex.getCode());
        verify(lintFindingService, never()).autoResolve(anyLong(), anyString());
        verify(conflictReviewService, never()).createReview(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldExecuteExistingPendingReviewWithoutCreating() {
        LintFindingDO finding = conflictFinding();
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(review(77L, "pending"));
        when(conflictReviewService.executeRuling(eq(77L), isNull(), eq("choose_a"), anyString()))
            .thenReturn(review(77L, "executed"));

        Map<String, Object> result = newService().executeConflictRuling(1L, 31L, "choose_a");

        assertEquals("auto_resolved", result.get("status"));
        assertEquals(77L, result.get("reviewId"));
        verify(conflictReviewService, never()).createReview(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(lintFindingService).autoResolve(31L, "ruling_brief");
    }

    @Test
    void shouldMarkFailedWhenRulingExecutionFails() {
        LintFindingDO finding = conflictFinding();
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(review(77L, "pending"));
        ConflictReviewDO failed = review(77L, "failed");
        failed.setExecutionError("LLM timeout");
        when(conflictReviewService.executeRuling(eq(77L), isNull(), eq("merge"), anyString())).thenReturn(failed);

        Map<String, Object> result = newService().executeConflictRuling(1L, 31L, "merge");

        assertEquals("failed", result.get("status"));
        assertEquals("LLM timeout", result.get("error"));
        verify(lintFindingService).markAsFailed(31L, "LLM timeout");
        verify(lintFindingService, never()).autoResolve(anyLong(), anyString());
    }

    @Test
    void shouldResolvePagePairByPathWhenAssetIdMissing() {
        LintFindingDO finding = conflictFinding();
        finding.setAssetId(null);
        finding.setExtra("{\"pagePathB\":\"entities/biden.md\"}");
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectOne(any()))
            .thenReturn(page(11L, "entities/trump.md", "特朗普"))
            .thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(review(77L, "pending"));
        when(conflictReviewService.executeRuling(eq(77L), isNull(), eq("coexist"), anyString()))
            .thenReturn(review(77L, "executed"));

        Map<String, Object> result = newService().executeConflictRuling(1L, 31L, "coexist");

        assertEquals("auto_resolved", result.get("status"));
        verify(lintFindingService).updateExtraAndAsset(eq(31L), anyString(), eq(11L));
        verify(lintFindingService).autoResolve(31L, "ruling_brief");
    }

    @Test
    void shouldApproveConflictUsingBriefAction() {
        LintFindingDO finding = conflictFinding();
        finding.setStatus("awaiting_approval");
        finding.setRulingBriefJson("{\"brief\":\"...\",\"action\":\"merge\"}");
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(review(77L, "pending"));
        when(conflictReviewService.executeRuling(eq(77L), isNull(), eq("merge"), anyString()))
            .thenReturn(review(77L, "executed"));

        Map<String, Object> result = newService().approveFinding(1L, 31L);

        assertEquals("auto_resolved", result.get("status"));
        verify(conflictReviewService).executeRuling(eq(77L), isNull(), eq("merge"), anyString());
        verify(lintFindingService).autoResolve(31L, "ruling_brief");
    }
}
