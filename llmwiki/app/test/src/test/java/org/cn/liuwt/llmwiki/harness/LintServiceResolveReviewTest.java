package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.service.lint.LintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintServiceResolveReviewTest {

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
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    @Test
    void shouldResolveExistingPendingReviewForFinding() {
        LintFindingDO finding = new LintFindingDO();
        finding.setId(31L);
        finding.setScopeId(1L);
        finding.setFindingType("conflict");
        finding.setStatus("open");
        finding.setAssetId(11L);
        finding.setPagePath("entities/trump.md");
        finding.setExtra("{\"relatedPageId\":42,\"relatedPagePath\":\"entities/biden.md\",\"conflictType\":\"fact_conflict\"}");
        when(lintFindingService.getFinding(31L)).thenReturn(finding);
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        ConflictReviewDO pending = new ConflictReviewDO();
        pending.setId(77L);
        pending.setStatus("pending");
        when(conflictReviewService.findPendingByPagePair(1L, 11L, 42L)).thenReturn(pending);
        when(conflictReviewService.getReview(77L)).thenReturn(pending);

        ConflictReviewDO result = newService().resolveOrCreateReview(1L, 31L);

        assertEquals(77L, result.getId());
    }

    @Test
    void shouldThrowWhenFindingMissing() {
        when(lintFindingService.getFinding(404L)).thenReturn(null);

        assertThrows(RuntimeException.class, () -> newService().resolveOrCreateReview(1L, 404L));
    }

    private static WikiPageDO page(Long id, String path, String title) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle(title);
        return p;
    }
}
