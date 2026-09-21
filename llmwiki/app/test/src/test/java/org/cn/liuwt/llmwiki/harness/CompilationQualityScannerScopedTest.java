package org.cn.liuwt.llmwiki.harness;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.cn.liuwt.llmwiki.domain.service.harness.quality.CompilationQualityScanner;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompilationQualityScannerScopedTest {

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private ContentDuplicateDetector contentDuplicateDetector;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WikiPageDO.class);
    }

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldScanOnlyGivenPages() {
        WikiPageDO a = page(11L, "pages/a.md", "甲", "entity");
        ArgumentCaptor<LambdaQueryWrapper<WikiPageDO>> captor =
            ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        when(wikiPageMapper.selectList(captor.capture())).thenReturn(List.of(a));
        stubContent("pages/a.md", "- 条目一（来源：年报）");
        when(contentDuplicateDetector.detectForPages(1L, Set.of(11L))).thenReturn(List.of());
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L, List.of(11L));

        assertEquals(1, report.pageCount());
        verify(storageProvider, times(1)).read("1", "wiki/pages/a.md");
        verify(contentDuplicateDetector).detectForPages(1L, Set.of(11L));
        verify(contentDuplicateDetector, never()).detectAll(anyLong());
        LambdaQueryWrapper<WikiPageDO> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L), "wrapper must filter by scopeId");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(11L), "wrapper must filter by given pageIds");
        assertTrue(sql.contains("id IN"), "wrapper must use id IN clause");
    }

    @Test
    void shouldKeepFullScopeBehaviorWhenPageIdsNull() {
        WikiPageDO a = page(11L, "pages/a.md", "甲", "entity");
        when(wikiPageMapper.selectList(any())).thenReturn(List.of(a));
        stubContent("pages/a.md", "- 条目一（来源：年报）");
        when(contentDuplicateDetector.detectAll(1L)).thenReturn(List.of());
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L, null);

        assertEquals(1, report.pageCount());
        verify(contentDuplicateDetector).detectAll(1L);
        verify(contentDuplicateDetector, never()).detectForPages(anyLong(), any());
    }

    @Test
    void shouldReturnEmptyReportWhenPageIdsEmpty() {
        CompilationQualityScanner scanner = newScanner();

        CompilationQualityScanner.ScanReport report = scanner.scan(1L, List.of());

        assertEquals(0, report.pageCount());
        assertTrue(report.defectivePages().isEmpty());
        assertTrue(report.duplicatePairs().isEmpty());
        verifyNoInteractions(wikiPageMapper, storageProvider, contentDuplicateDetector);
    }
}
