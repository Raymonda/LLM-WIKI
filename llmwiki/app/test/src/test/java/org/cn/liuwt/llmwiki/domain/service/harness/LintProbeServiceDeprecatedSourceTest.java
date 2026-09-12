package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintProbeService.SafetyNetFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LintProbeServiceDeprecatedSourceTest {

    @Mock
    private WikiPageMapper wikiPageMapper;

    @InjectMocks
    private LintProbeService lintProbeService;

    private Map<String, Object> row(Long pageId, String filePath, String title, String pageType,
                                    Long sourceId, String sourceName, String category, String reason) {
        Map<String, Object> row = new HashMap<>();
        row.put("page_id", pageId);
        row.put("file_path", filePath);
        row.put("title", title);
        row.put("page_type", pageType);
        row.put("source_id", sourceId);
        row.put("source_name", sourceName);
        row.put("deprecated_category", category);
        row.put("deprecated_reason", reason);
        return row;
    }

    @Test
    void shouldEmitHighPriorityFindingWhenReferencePageReferencesDeprecatedSource() {
        when(wikiPageMapper.selectDeprecatedSourcePageRows(10L)).thenReturn(List.of(
            row(1L, "wiki/ref.md", "参考页A", "reference", 5L, "doc-a.pdf", "OUTDATED", "旧版")));

        List<SafetyNetFinding> findings = lintProbeService.detectDeprecatedSourceRefsBySql(10L);

        assertEquals(1, findings.size());
        SafetyNetFinding finding = findings.get(0);
        assertEquals("deprecated_source", finding.type);
        assertEquals("high", finding.priority);
        assertEquals("wiki/ref.md", finding.pagePath);
        assertEquals(1L, finding.assetId.longValue());
        assertTrue(finding.title.contains("参考页A"));
        assertTrue(finding.detail.contains("doc-a.pdf"));
        assertTrue(finding.detail.contains("OUTDATED"));
        assertTrue(finding.detail.contains("旧版"));
    }

    @Test
    void shouldEmitMediumPriorityFindingWhenSummaryPageReferencesDeprecatedSource() {
        when(wikiPageMapper.selectDeprecatedSourcePageRows(10L)).thenReturn(List.of(
            row(2L, "wiki/sum.md", "摘要页B", "summary", 6L, "doc-b.pdf", "SUPERSEDED", null)));

        List<SafetyNetFinding> findings = lintProbeService.detectDeprecatedSourceRefsBySql(10L);

        assertEquals(1, findings.size());
        assertEquals("medium", findings.get(0).priority);
    }

    @Test
    void shouldMergeMultipleDeprecatedSourcesIntoOneFindingPerPage() {
        when(wikiPageMapper.selectDeprecatedSourcePageRows(10L)).thenReturn(List.of(
            row(3L, "wiki/multi.md", "多源页", "entity", 5L, "doc-a.pdf", "OUTDATED", null),
            row(3L, "wiki/multi.md", "多源页", "entity", 6L, "doc-b.pdf", "ERRONEOUS", "数据错误")));

        List<SafetyNetFinding> findings = lintProbeService.detectDeprecatedSourceRefsBySql(10L);

        assertEquals(1, findings.size());
        SafetyNetFinding finding = findings.get(0);
        assertEquals("medium", finding.priority);
        assertTrue(finding.detail.contains("doc-a.pdf"));
        assertTrue(finding.detail.contains("doc-b.pdf"));
        assertTrue(finding.detail.contains("数据错误"));
        assertEquals(2, ((List<?>) finding.extra.get("sourceIds")).size());
    }

    @Test
    void shouldReturnEmptyFindingsWhenNoDeprecatedReferences() {
        when(wikiPageMapper.selectDeprecatedSourcePageRows(10L)).thenReturn(List.of());

        assertTrue(lintProbeService.detectDeprecatedSourceRefsBySql(10L).isEmpty());
    }
}
