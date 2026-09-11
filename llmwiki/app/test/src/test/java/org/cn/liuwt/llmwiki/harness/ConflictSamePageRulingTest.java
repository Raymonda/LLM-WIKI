package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ConflictReviewMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictSamePageRulingTest {

    @Mock
    private ConflictReviewMapper conflictReviewMapper;

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private SearchService searchService;

    @Mock
    private LlmClient chatClient;

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = ConflictReviewService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private ConflictReviewService newService() {
        ConflictReviewService service = new ConflictReviewService();
        inject(service, "conflictReviewMapper", conflictReviewMapper);
        inject(service, "wikiPageMapper", wikiPageMapper);
        inject(service, "searchService", searchService);
        inject(service, "chatClient", chatClient);
        return service;
    }

    private static ConflictReviewDO review(Long id, Long fromPageId, Long toPageId) {
        ConflictReviewDO r = new ConflictReviewDO();
        r.setId(id);
        r.setScopeId(1L);
        r.setFromPageId(fromPageId);
        r.setToPageId(toPageId);
        r.setStatus("pending");
        return r;
    }

    private static WikiPageDO page(Long id, String path, String title) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle(title);
        return p;
    }

    @Test
    void shouldDowngradeMergeToCoexistWhenPagePairIsSamePage() {
        when(conflictReviewMapper.selectById(55L)).thenReturn(review(55L, 77L, 77L));
        when(wikiPageMapper.selectById(77L)).thenReturn(page(77L, "pages/test-entity.md", "测试实体"));
        ConflictReviewService service = newService();

        ConflictReviewDO result = service.executeRuling(55L, null, "merge", "合并");

        assertEquals("executed", result.getStatus());
        assertEquals("coexist", result.getRulingAction(), "same-page merge must be downgraded to coexist");
        assertTrue(result.getRulingDetail().contains("同页冲突"));
        assertNull(result.getExecutionError());
        verify(searchService, never()).removePage(any(), any());
        verify(conflictReviewMapper).updateById(any(ConflictReviewDO.class));
    }

    @Test
    void shouldDowngradeChooseToCoexistWhenPagePairIsSamePage() {
        when(conflictReviewMapper.selectById(55L)).thenReturn(review(55L, 77L, 77L));
        when(wikiPageMapper.selectById(77L)).thenReturn(page(77L, "pages/test-entity.md", "测试实体"));
        ConflictReviewService service = newService();

        ConflictReviewDO result = service.executeRuling(55L, null, "choose_a", "选A");

        assertEquals("executed", result.getStatus());
        assertEquals("coexist", result.getRulingAction());
        verify(searchService, never()).removePage(any(), any());
    }

    @Test
    void shouldKeepMergeExecutionForDifferentPagePair() {
        when(conflictReviewMapper.selectById(55L)).thenReturn(review(55L, 11L, 42L));
        when(wikiPageMapper.selectById(11L)).thenReturn(page(11L, "entities/trump.md", "特朗普"));
        when(wikiPageMapper.selectById(42L)).thenReturn(page(42L, "entities/biden.md", "拜登"));
        when(chatClient.isAvailable()).thenReturn(false);
        ConflictReviewService service = newService();

        assertThrows(BusinessException.class,
            () -> service.executeRuling(55L, null, "merge", "合并"),
            "different-page merge must reach the real merge path, not the guard");

        verify(searchService, never()).removePage(any(), any());
    }
}
