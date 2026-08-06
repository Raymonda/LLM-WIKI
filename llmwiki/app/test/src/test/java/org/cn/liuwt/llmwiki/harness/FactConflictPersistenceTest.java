package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FactConflictPersistenceTest {

    @Mock
    private LintFindingMapper lintFindingMapper;

    @Test
    void persistFactConflicts_insertsFindings() {
        WriterOrchestrator orchestrator = new WriterOrchestrator();
        try {
            java.lang.reflect.Field mapperField = WriterOrchestrator.class.getDeclaredField("lintFindingMapper");
            mapperField.setAccessible(true);
            mapperField.set(orchestrator, lintFindingMapper);
        } catch (Exception e) {
            fail("Failed to inject lintFindingMapper: " + e.getMessage());
            return;
        }

        when(lintFindingMapper.countOpenConflictByPageAndDetailPrefix(anyLong(), anyString(), anyString()))
            .thenReturn(0);

        ConsistencyReconciler.ConsistencyReport report = new ConsistencyReconciler.ConsistencyReport(
            List.of(),
            List.of(new ConsistencyReconciler.FactConflict(
                "Revenue mismatch",
                "wiki/pages/finance.md",
                "wiki/pages/report.md",
                "Revenue is 100M",
                "Revenue is 120M"
            )),
            0,
            1,
            Set.of()
        );

        IngestContext context = new IngestContext(1L, 1L, 100L, null);

        try {
            java.lang.reflect.Method method = WriterOrchestrator.class.getDeclaredMethod(
                "persistFactConflicts", IngestContext.class, ConsistencyReconciler.ConsistencyReport.class);
            method.setAccessible(true);
            method.invoke(orchestrator, context, report);
        } catch (Exception e) {
            fail("persistFactConflicts failed: " + e.getCause().getMessage());
        }

        ArgumentCaptor<LintFindingDO> captor = ArgumentCaptor.forClass(LintFindingDO.class);
        verify(lintFindingMapper).insert(captor.capture());
        LintFindingDO inserted = captor.getValue();
        assertEquals(1L, inserted.getScopeId());
        assertEquals("conflict", inserted.getFindingType());
        assertEquals("open", inserted.getStatus());
        assertEquals("wiki/pages/finance.md", inserted.getPagePath());
        assertEquals(100L, inserted.getExecutionId());
        assertNotNull(inserted.getDetail());
        assertNotNull(inserted.getExtra());
        assertTrue(inserted.getExtra().contains("wiki/pages/report.md"));
    }

    @Test
    void persistFactConflicts_deduplicates() {
        WriterOrchestrator orchestrator = new WriterOrchestrator();
        try {
            java.lang.reflect.Field mapperField = WriterOrchestrator.class.getDeclaredField("lintFindingMapper");
            mapperField.setAccessible(true);
            mapperField.set(orchestrator, lintFindingMapper);
        } catch (Exception e) {
            fail("Failed to inject lintFindingMapper: " + e.getMessage());
            return;
        }

        when(lintFindingMapper.countOpenConflictByPageAndDetailPrefix(anyLong(), anyString(), anyString()))
            .thenReturn(1);

        ConsistencyReconciler.ConsistencyReport report = new ConsistencyReconciler.ConsistencyReport(
            List.of(),
            List.of(new ConsistencyReconciler.FactConflict(
                "Duplicate conflict",
                "wiki/pages/a.md",
                "wiki/pages/b.md",
                "claim A",
                "claim B"
            )),
            0,
            1,
            Set.of()
        );

        IngestContext context = new IngestContext(1L, 1L, 100L, null);

        try {
            java.lang.reflect.Method method = WriterOrchestrator.class.getDeclaredMethod(
                "persistFactConflicts", IngestContext.class, ConsistencyReconciler.ConsistencyReport.class);
            method.setAccessible(true);
            method.invoke(orchestrator, context, report);
        } catch (Exception e) {
            fail("persistFactConflicts failed: " + e.getCause().getMessage());
        }

        verify(lintFindingMapper).countOpenConflictByPageAndDetailPrefix(anyLong(), anyString(), anyString());
        verifyNoMoreInteractions(lintFindingMapper);
    }

    @Test
    void persistFactConflicts_emptyList_noInsert() {
        WriterOrchestrator orchestrator = new WriterOrchestrator();
        try {
            java.lang.reflect.Field mapperField = WriterOrchestrator.class.getDeclaredField("lintFindingMapper");
            mapperField.setAccessible(true);
            mapperField.set(orchestrator, lintFindingMapper);
        } catch (Exception e) {
            fail("Failed to inject lintFindingMapper: " + e.getMessage());
            return;
        }

        ConsistencyReconciler.ConsistencyReport report = new ConsistencyReconciler.ConsistencyReport(
            List.of(), List.of(), 0, 0, Set.of());

        IngestContext context = new IngestContext(1L, 1L, 100L, null);

        try {
            java.lang.reflect.Method method = WriterOrchestrator.class.getDeclaredMethod(
                "persistFactConflicts", IngestContext.class, ConsistencyReconciler.ConsistencyReport.class);
            method.setAccessible(true);
            method.invoke(orchestrator, context, report);
        } catch (Exception e) {
            fail("persistFactConflicts failed: " + e.getCause().getMessage());
        }

        verifyNoInteractions(lintFindingMapper);
    }
}
