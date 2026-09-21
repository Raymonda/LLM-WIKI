package org.cn.liuwt.llmwiki.service.lint;

import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestBatchSettledEvent;
import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchQualityGateListenerTest {

    @Mock
    private CompilationQualityScanner compilationQualityScanner;

    @Mock
    private LintFindingService lintFindingService;

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = BatchQualityGateListener.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private BatchQualityGateListener newListener() {
        BatchQualityGateListener listener = new BatchQualityGateListener();
        inject(listener, "compilationQualityScanner", compilationQualityScanner);
        inject(listener, "lintFindingService", lintFindingService);
        return listener;
    }

    private static CompilationQualityScanner.ScanReport report(List<CompilationQualityScanner.PageDefects> defects,
                                                               List<CompilationQualityScanner.SourceCoverage> coverage,
                                                               List<CompilationQualityScanner.DuplicatePair> duplicates,
                                                               List<CompilationQualityScanner.CitationRate> citations) {
        return new CompilationQualityScanner.ScanReport(1L, LocalDateTime.now(), 4,
            defects, coverage, duplicates, citations);
    }

    @Test
    void shouldScanBatchPagesAndContainerizeExceptions() {
        when(compilationQualityScanner.scan(eq(1L), any())).thenThrow(new RuntimeException("scan boom"));
        BatchQualityGateListener listener = newListener();
        IngestBatchSettledEvent event = new IngestBatchSettledEvent(this, 9L, 1L, List.of(11L));

        assertDoesNotThrow(() -> listener.onBatchSettled(event));

        verify(compilationQualityScanner).scan(1L, List.of(11L));
        verifyNoInteractions(lintFindingService);
    }

    @Test
    void shouldPersistEachDefectTypeAsFinding() {
        CompilationQualityScanner.PageDefects defect = new CompilationQualityScanner.PageDefects(
            11L, "pages/a.md", "甲", List.of("去除重复词"), List.of("疑似残留占位符"));
        CompilationQualityScanner.SourceCoverage coverage = new CompilationQualityScanner.SourceCoverage(
            12L, "pages/b.md", "乙", 4, 1, 0.25);
        CompilationQualityScanner.DuplicatePair duplicate = new CompilationQualityScanner.DuplicatePair(
            13L, "pages/c.md", 14L, "pages/d.md", 0.82);
        CompilationQualityScanner.CitationRate citation = new CompilationQualityScanner.CitationRate(
            15L, "pages/e.md", "戊", 60, 1, 0.016);
        List<Long> pageIds = List.of(11L, 12L, 13L, 15L);
        when(compilationQualityScanner.scan(1L, pageIds))
            .thenReturn(report(List.of(defect), List.of(coverage), List.of(duplicate), List.of(citation)));
        BatchQualityGateListener listener = newListener();

        listener.onBatchSettled(new IngestBatchSettledEvent(this, 9L, 1L, pageIds));

        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("compilation_defect"), eq("medium"),
            anyString(), anyString(), eq("pages/a.md"), eq(11L),
            argThat(extra -> Long.valueOf(9L).equals(extra.get("batchId"))));
        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("low_source_coverage"), eq("high"),
            anyString(), contains("4"), eq("pages/b.md"), eq(12L), anyMap());
        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("duplicate_content"), eq("medium"),
            anyString(), anyString(), eq("pages/c.md"), eq(13L),
            argThat(extra -> Long.valueOf(14L).equals(extra.get("pageBId"))
                && "pages/d.md".equals(extra.get("pageBPath"))));
        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("low_citation"), eq("high"),
            anyString(), contains("60"), eq("pages/e.md"), eq(15L), anyMap());
    }

    @Test
    void shouldSkipWhenPageIdsEmpty() {
        BatchQualityGateListener listener = newListener();

        listener.onBatchSettled(new IngestBatchSettledEvent(this, 9L, 1L, List.of()));

        verifyNoInteractions(compilationQualityScanner, lintFindingService);
    }

    @Test
    void shouldContinueRemainingFindingsWhenSingleCreateFails() {
        CompilationQualityScanner.PageDefects d1 = new CompilationQualityScanner.PageDefects(
            11L, "pages/a.md", "甲", List.of("f1"), List.of());
        CompilationQualityScanner.PageDefects d2 = new CompilationQualityScanner.PageDefects(
            12L, "pages/b.md", "乙", List.of("f2"), List.of());
        List<Long> pageIds = List.of(11L, 12L);
        when(compilationQualityScanner.scan(1L, pageIds))
            .thenReturn(report(List.of(d1, d2), List.of(), List.of(), List.of()));
        when(lintFindingService.createFinding(eq(1L), isNull(), eq("compilation_defect"), eq("medium"),
            anyString(), anyString(), eq("pages/a.md"), eq(11L), anyMap()))
            .thenThrow(new RuntimeException("db boom"));
        BatchQualityGateListener listener = newListener();

        assertDoesNotThrow(() -> listener.onBatchSettled(new IngestBatchSettledEvent(this, 9L, 1L, pageIds)));

        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("compilation_defect"), eq("medium"),
            anyString(), anyString(), eq("pages/a.md"), eq(11L), anyMap());
        verify(lintFindingService).createFinding(eq(1L), isNull(), eq("compilation_defect"), eq("medium"),
            anyString(), anyString(), eq("pages/b.md"), eq(12L), anyMap());
    }
}
