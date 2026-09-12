package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityScanner;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompilationQualityScannerTest {

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private ContentDuplicateDetector contentDuplicateDetector;

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = CompilationQualityScanner.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private CompilationQualityScanner newScanner() {
        CompilationQualityScanner scanner = new CompilationQualityScanner();
        inject(scanner, "wikiPageMapper", wikiPageMapper);
        inject(scanner, "storageProvider", storageProvider);
        inject(scanner, "contentDuplicateDetector", contentDuplicateDetector);
        return scanner;
    }

    private static WikiPageDO page(Long id, String path, String title, String pageType) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle(title);
        p.setPageType(pageType);
        p.setLifecycleStatus("ACTIVE");
        return p;
    }

    private void stubContent(String path, String content) {
        when(storageProvider.read(eq("1"), eq("wiki/" + path)))
            .thenReturn(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReportDefectivePagesWhenGuardFindsFixes() {
        when(wikiPageMapper.selectList(any())).thenReturn(
            List.of(page(11L, "pages/a.md", "甲", "entity")));
        stubContent("pages/a.md", "托管人为泰康（泰康）。");
        when(contentDuplicateDetector.detectAll(1L)).thenReturn(List.of());
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L);

        assertEquals(1, report.defectivePages().size());
        CompilationQualityScanner.PageDefects defects = report.defectivePages().get(0);
        assertEquals(11L, defects.pageId());
        assertTrue(defects.fixes().stream().anyMatch(f -> f.contains("泰康")), "guard fix must be reported");
    }

    @Test
    void shouldFlagLowCoverageEntityPage() {
        when(wikiPageMapper.selectList(any())).thenReturn(
            List.of(page(11L, "pages/a.md", "甲", "entity")));
        stubContent("pages/a.md", """
            ## 基本信息

            - 总部位于上海（来源：年报）
            - 注册资本 10 亿元
            - 成立于 2001 年
            - 员工 500 人""");
        when(contentDuplicateDetector.detectAll(1L)).thenReturn(List.of());
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L);

        assertEquals(1, report.lowCoveragePages().size());
        CompilationQualityScanner.SourceCoverage coverage = report.lowCoveragePages().get(0);
        assertEquals(4, coverage.bulletLines());
        assertEquals(1, coverage.coveredLines());
        assertEquals(0.25, coverage.ratio(), 0.001);
    }

    @Test
    void shouldFlagLowCitationReferencePage() {
        when(wikiPageMapper.selectList(any())).thenReturn(
            List.of(page(21L, "pages/ref.md", "参考", "reference")));
        stubContent("pages/ref.md", "## 原文内容\n\n" + "这是一段没有引用标记的正文。\n".repeat(60));
        when(contentDuplicateDetector.detectAll(1L)).thenReturn(List.of());
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L);

        assertEquals(1, report.lowCitationPages().size());
        assertEquals(21L, report.lowCitationPages().get(0).pageId());
        assertTrue(report.lowCitationPages().get(0).ratio() < 0.02);
    }

    @Test
    void shouldIncludeDuplicatePairsFromDetector() {
        WikiPageDO a = page(31L, "pages/x.md", "X", "entity");
        WikiPageDO b = page(32L, "pages/y.md", "Y", "entity");
        when(wikiPageMapper.selectList(any())).thenReturn(List.of(a, b));
        stubContent("pages/x.md", "- 条目一（来源：年报）");
        stubContent("pages/y.md", "- 条目二（来源：季报）");
        when(contentDuplicateDetector.detectAll(1L))
            .thenReturn(List.of(new ContentDuplicateDetector.DuplicatePair(a, b, 0.82)));
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L);

        assertEquals(1, report.duplicatePairs().size());
        CompilationQualityScanner.DuplicatePair pair = report.duplicatePairs().get(0);
        assertEquals(31L, pair.pageAId());
        assertEquals(32L, pair.pageBId());
        assertEquals(0.82, pair.similarity(), 0.001);
    }
}
