package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompletionAgentQualityFindingTest {

    private static final Long SCOPE_ID = 10L;
    private static final Long EXECUTION_ID = 501L;

    @Mock
    private LintFindingService lintFindingService;

    @Mock
    private WikiFileServiceImpl wikiFileService;

    @Mock
    private WikiPageMapper wikiPageMapper;

    private CompletionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new CompletionAgent();
        ReflectionTestUtils.setField(agent, "lintFindingService", lintFindingService);
        ReflectionTestUtils.setField(agent, "wikiFileService", wikiFileService);
        ReflectionTestUtils.setField(agent, "wikiPageMapper", wikiPageMapper);
    }

    private IngestContext newContext() {
        return new IngestContext(SCOPE_ID, 77L, EXECUTION_ID, null);
    }

    private WriterQualityVerifier.QualityIssue issue(WriterQualityVerifier.Severity severity,
                                                     String category, String pagePath,
                                                     String entityName, String description) {
        return new WriterQualityVerifier.QualityIssue(severity, category, pagePath, entityName, description);
    }

    @Test
    void shouldCreateFindingForEachCriticalIssue() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(
                issue(WriterQualityVerifier.Severity.CRITICAL, "coverage", "pages/a.md", "Alpha", "missing claim"),
                issue(WriterQualityVerifier.Severity.CRITICAL, "fidelity", "pages/b.md", "Beta", "fabricated number")),
            2, 0, 0);

        agent.persistQualityFindings(newContext(), report);

        verify(lintFindingService, times(2)).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            anyString(), anyString(), any(), isNull(), anyMap());
        verify(lintFindingService).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            eq("写入质检 [coverage]Alpha"), eq("missing claim"), eq("pages/a.md"), isNull(),
            argThat(extra -> "coverage".equals(extra.get("category")) && "Alpha".equals(extra.get("entityName"))));
        verify(lintFindingService).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            eq("写入质检 [fidelity]Beta"), eq("fabricated number"), eq("pages/b.md"), isNull(),
            argThat(extra -> "fidelity".equals(extra.get("category")) && "Beta".equals(extra.get("entityName"))));
    }

    @Test
    void shouldNotCreateFindingWhenNoCritical() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(
                issue(WriterQualityVerifier.Severity.WARNING, "style", "pages/c.md", "Gamma", "minor style"),
                issue(WriterQualityVerifier.Severity.INFO, "format", null, null, "note")),
            0, 1, 1);

        agent.persistQualityFindings(newContext(), report);

        verifyNoInteractions(lintFindingService, wikiPageMapper, wikiFileService);
    }

    @Test
    void shouldContinueWhenSingleFindingFails() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(
                issue(WriterQualityVerifier.Severity.CRITICAL, "coverage", "pages/a.md", "Alpha", "first"),
                issue(WriterQualityVerifier.Severity.CRITICAL, "fidelity", "pages/b.md", "Beta", "second")),
            2, 0, 0);
        when(lintFindingService.createFinding(any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyMap()))
            .thenThrow(new RuntimeException("db down"))
            .thenReturn(99L);

        assertDoesNotThrow(() -> agent.persistQualityFindings(newContext(), report));

        verify(lintFindingService, times(2)).createFinding(any(), any(), anyString(), anyString(),
            anyString(), anyString(), any(), any(), anyMap());
    }

    @Test
    void shouldRecalcHealthForAffectedPages() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(
                issue(WriterQualityVerifier.Severity.CRITICAL, "coverage", "pages/a.md", "Alpha", "issue-a"),
                issue(WriterQualityVerifier.Severity.CRITICAL, "fidelity", "pages/b.md", "Beta", "issue-b")),
            2, 0, 0);
        WikiPageDO pageA = new WikiPageDO();
        pageA.setId(1001L);
        pageA.setFilePath("pages/a.md");
        WikiPageDO pageB = new WikiPageDO();
        pageB.setId(1002L);
        pageB.setFilePath("pages/b.md");
        when(wikiPageMapper.selectOne(any())).thenReturn(pageA, pageB);

        agent.persistQualityFindings(newContext(), report);

        ArgumentCaptor<Long> pageIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(wikiFileService, times(2)).recalcPageHealthStatus(eq(SCOPE_ID), pageIdCaptor.capture());
        assertTrue(pageIdCaptor.getAllValues().containsAll(List.of(1001L, 1002L)));
        verify(lintFindingService).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            anyString(), anyString(), eq("pages/a.md"), eq(1001L), anyMap());
        verify(lintFindingService).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            anyString(), anyString(), eq("pages/b.md"), eq(1002L), anyMap());
    }

    @Test
    void shouldUseDerivedEntityPathWhenPageMissing() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(issue(WriterQualityVerifier.Severity.CRITICAL, "MISSING_ENTITY_PAGE", null, "Gamma",
                "实体「Gamma」有 5 处出现但未生成页面")),
            1, 0, 0);

        agent.persistQualityFindings(newContext(), report);

        verify(lintFindingService).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            eq("写入质检 [MISSING_ENTITY_PAGE]Gamma"), eq("实体「Gamma」有 5 处出现但未生成页面"),
            eq("pages/Gamma.md"), isNull(), anyMap());
        verifyNoInteractions(wikiPageMapper, wikiFileService);
    }

    @Test
    void shouldRecalcOncePerPageWhenMultipleIssuesSharePage() {
        WriterQualityVerifier.VerificationReport report = new WriterQualityVerifier.VerificationReport(
            List.of(
                issue(WriterQualityVerifier.Severity.CRITICAL, "coverage", "pages/a.md", "Alpha", "issue-1"),
                issue(WriterQualityVerifier.Severity.CRITICAL, "fidelity", "pages/a.md", "Beta", "issue-2")),
            2, 0, 0);
        WikiPageDO pageA = new WikiPageDO();
        pageA.setId(1001L);
        pageA.setFilePath("pages/a.md");
        when(wikiPageMapper.selectOne(any())).thenReturn(pageA);

        agent.persistQualityFindings(newContext(), report);

        verify(lintFindingService, times(2)).createFinding(
            eq(SCOPE_ID), eq(EXECUTION_ID), eq("ingest_quality"), eq("high"),
            anyString(), anyString(), eq("pages/a.md"), eq(1001L), anyMap());
        verify(wikiFileService, times(1)).recalcPageHealthStatus(SCOPE_ID, 1001L);
    }
}
