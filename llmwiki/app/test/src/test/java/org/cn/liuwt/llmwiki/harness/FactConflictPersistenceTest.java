package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.ConsistencyReconciler;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.WriterOrchestrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FactConflictPersistenceTest {

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private LintFindingService lintFindingService;

    private WriterOrchestrator newOrchestrator() {
        WriterOrchestrator orchestrator = new WriterOrchestrator();
        inject(orchestrator, "wikiPageMapper", wikiPageMapper);
        inject(orchestrator, "lintFindingService", lintFindingService);
        return orchestrator;
    }

    private static void inject(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = WriterOrchestrator.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            fail("Failed to inject " + fieldName + ": " + e.getMessage());
        }
    }

    private static void invokePersist(WriterOrchestrator orchestrator, IngestContext context,
                                      ConsistencyReconciler.ConsistencyReport report) {
        try {
            java.lang.reflect.Method method = WriterOrchestrator.class.getDeclaredMethod(
                "persistFactConflicts", IngestContext.class, ConsistencyReconciler.ConsistencyReport.class);
            method.setAccessible(true);
            method.invoke(orchestrator, context, report);
        } catch (Exception e) {
            fail("persistFactConflicts failed: "
                + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
    }

    private static WikiPageDO page(long id, String title, String path) {
        WikiPageDO page = new WikiPageDO();
        page.setId(id);
        page.setTitle(title);
        page.setFilePath(path);
        return page;
    }

    private static ConsistencyReconciler.ConsistencyReport conflictReport() {
        return new ConsistencyReconciler.ConsistencyReport(
            List.of(),
            List.of(new ConsistencyReconciler.FactConflict(
                "Revenue mismatch",
                "entities/trump.md",
                "entities/biden.md",
                "税率是10%",
                "税率是15%"
            )),
            0,
            1,
            Set.of()
        );
    }

    @Test
    void shouldPersistCanonicalConflictCard() {
        when(wikiPageMapper.selectOne(any())).thenReturn(
            page(11L, "特朗普", "entities/trump.md"),
            page(42L, "拜登", "entities/biden.md"));
        when(lintFindingService.upsertConflictFinding(any(), any(), any())).thenReturn(7L);

        invokePersist(newOrchestrator(), new IngestContext(1L, 1L, 100L, null), conflictReport());

        ArgumentCaptor<LintFindingService.ConflictCard> captor =
            ArgumentCaptor.forClass(LintFindingService.ConflictCard.class);
        verify(lintFindingService).upsertConflictFinding(eq(1L), eq(100L), captor.capture());
        LintFindingService.ConflictCard card = captor.getValue();
        assertEquals("medium", card.priority());
        assertEquals("entities/trump.md", card.fromPagePath());
        assertEquals(11L, card.fromPageId());
        assertEquals("特朗普", card.fromPageTitle());
        assertEquals("entities/biden.md", card.relatedPagePath());
        assertEquals(42L, card.relatedPageId());
        assertEquals("拜登", card.relatedPageTitle());
        assertEquals("fact_conflict", card.conflictType());
        assertEquals("税率是10%", card.claimA());
        assertEquals("税率是15%", card.claimB());
        assertEquals("ingest_fact_conflict", card.source());
        assertTrue(card.detail().contains("页面A: entities/trump.md"));
        assertTrue(card.detail().contains("声明B: 税率是15%"));
    }

    @Test
    void shouldDegradeWhenRelatedPageMissing() {
        when(wikiPageMapper.selectOne(any())).thenReturn(
            page(11L, "特朗普", "entities/trump.md"), null);
        when(lintFindingService.upsertConflictFinding(any(), any(), any())).thenReturn(7L);

        invokePersist(newOrchestrator(), new IngestContext(1L, 1L, 100L, null), conflictReport());

        ArgumentCaptor<LintFindingService.ConflictCard> captor =
            ArgumentCaptor.forClass(LintFindingService.ConflictCard.class);
        verify(lintFindingService).upsertConflictFinding(any(), any(), captor.capture());
        LintFindingService.ConflictCard card = captor.getValue();
        assertEquals(11L, card.fromPageId());
        assertNull(card.relatedPageId());
        assertNull(card.relatedPageTitle());
        assertEquals("entities/biden.md", card.relatedPagePath());
    }

    @Test
    void shouldNotUpsertWhenNoFactConflicts() {
        ConsistencyReconciler.ConsistencyReport report = new ConsistencyReconciler.ConsistencyReport(
            List.of(), List.of(), 0, 0, Set.of());

        invokePersist(newOrchestrator(), new IngestContext(1L, 1L, 100L, null), report);

        verifyNoInteractions(lintFindingService);
    }
}
