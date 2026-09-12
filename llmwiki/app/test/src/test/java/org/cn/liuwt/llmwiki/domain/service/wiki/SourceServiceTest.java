package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SourceServiceTest {

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private SourceMapper sourceMapper;

    @InjectMocks
    private SourceService sourceService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SourceDO.class);
    }

    private void stubEmptyTempDir() {
        when(storageProvider.list(anyString(), eq("raw/.tmp"))).thenReturn(List.of());
    }

    @Test
    void shouldWriteToTempThenMoveToCasPathWhenUploadTextSourceCalled() throws Exception {
        stubEmptyTempDir();
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.exists(anyString(), anyString())).thenReturn(false);
        when(storageProvider.getLastModifiedTime(anyString(), anyString())).thenReturn(null);

        String content = "# 标题\n正文";
        SourceModel model = sourceService.uploadTextSource("会议纪要", content, 100L, 7L);

        assertEquals("会议纪要.md", model.getName());
        assertEquals(64, model.getContentHash().length());

        ArgumentCaptor<SourceDO> captor = ArgumentCaptor.forClass(SourceDO.class);
        verify(sourceMapper).insert(captor.capture());
        assertTrue(captor.getValue().getFilePath().matches("raw/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}"));
        assertEquals("ACTIVE", captor.getValue().getLifecycleStatus());

        verify(storageProvider).write(eq("100"), startsWith("raw/.tmp/"), any(InputStream.class),
            eq((long) content.getBytes(StandardCharsets.UTF_8).length));
        ArgumentCaptor<String> fromCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageProvider).move(eq("100"), fromCaptor.capture(), toCaptor.capture());
        assertTrue(fromCaptor.getValue().startsWith("raw/.tmp/"));
        assertTrue(toCaptor.getValue().matches("raw/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}"));
    }

    @Test
    void shouldReuseExistingCasObjectAndDeleteTempWhenHashExists() throws Exception {
        stubEmptyTempDir();
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.exists(eq("100"), anyString())).thenReturn(true);
        when(storageProvider.getLastModifiedTime(anyString(), anyString())).thenReturn(null);

        SourceModel model = sourceService.uploadTextSource("dedup", "同内容", 100L, 7L);

        assertNotNull(model.getContentHash());
        verify(storageProvider).delete(eq("100"), startsWith("raw/.tmp/"));
        verify(storageProvider, never()).move(anyString(), anyString(), anyString());
    }

    @Test
    void shouldDeleteTempFileWhenMoveFails() {
        when(storageProvider.exists(eq("100"), anyString())).thenReturn(false);
        doThrow(new RuntimeException("disk full")).when(storageProvider)
            .move(eq("100"), anyString(), anyString());

        assertThrows(RuntimeException.class,
            () -> sourceService.uploadTextSource("fail", "内容", 100L, 7L));

        verify(storageProvider).delete(eq("100"), startsWith("raw/.tmp/"));
        verify(sourceMapper, never()).insert(any(SourceDO.class));
    }

    @Test
    void shouldTreatUploadAsSuccessWhenCasPathAppearsAfterMoveFailure() throws Exception {
        stubEmptyTempDir();
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.exists(eq("100"), anyString())).thenReturn(false, true);
        doThrow(new RuntimeException("concurrent race")).when(storageProvider)
            .move(eq("100"), anyString(), anyString());

        SourceModel model = sourceService.uploadTextSource("race", "并发内容", 100L, 7L);

        assertNotNull(model.getContentHash());
        verify(storageProvider).delete(eq("100"), startsWith("raw/.tmp/"));
        ArgumentCaptor<SourceDO> captor = ArgumentCaptor.forClass(SourceDO.class);
        verify(sourceMapper).insert(captor.capture());
        assertTrue(captor.getValue().getFilePath().matches("raw/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}"));
    }

    @Test
    void shouldCleanupStaleTempFilesBeforeUpload() throws Exception {
        when(storageProvider.list("100", "raw/.tmp"))
            .thenReturn(List.of("raw/.tmp/stale", "raw/.tmp/fresh"));
        when(storageProvider.getLastModifiedTime("100", "raw/.tmp/stale"))
            .thenReturn(LocalDateTime.now().minusHours(25));
        when(storageProvider.getLastModifiedTime("100", "raw/.tmp/fresh"))
            .thenReturn(LocalDateTime.now().minusHours(1));
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.exists(anyString(), anyString())).thenReturn(false);

        sourceService.uploadTextSource("cleanup", "内容", 100L, 7L);

        verify(storageProvider).delete("100", "raw/.tmp/stale");
        verify(storageProvider, never()).delete("100", "raw/.tmp/fresh");
    }

    @Test
    void shouldAppendMdExtensionWhenTitleHasNone() throws Exception {
        stubEmptyTempDir();
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.exists(anyString(), anyString())).thenReturn(false);
        when(storageProvider.getLastModifiedTime(anyString(), anyString())).thenReturn(null);

        SourceModel model = sourceService.uploadTextSource("readme", "内容", 100L, 7L);

        assertEquals("readme.md", model.getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldPassPaginationParamsAndScopeFilterWhenListSourcesPagedCalled() {
        when(sourceMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        sourceService.listSourcesPaged(100L, 2, 20);

        ArgumentCaptor<Page<SourceDO>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<SourceDO>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sourceMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(2, pageCaptor.getValue().getCurrent());
        assertEquals(20, pageCaptor.getValue().getSize());
        LambdaQueryWrapper<SourceDO> wrapper = (LambdaQueryWrapper<SourceDO>) wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("SCOPE_ID"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(100L));
    }

    @Test
    void shouldMapRecordsAndTotalWhenListSourcesPagedCalled() {
        SourceDO first = new SourceDO();
        first.setId(11L);
        first.setName("first.md");
        first.setStatus("processed");
        SourceDO second = new SourceDO();
        second.setId(12L);
        second.setName("second.md");
        second.setStatus("uploaded");
        Page<SourceDO> stubPage = new Page<>(2, 5, 10);
        stubPage.setRecords(List.of(first, second));
        doReturn(stubPage).when(sourceMapper).selectPage(any(), any());

        IPage<SourceModel> paged = sourceService.listSourcesPaged(100L, 2, 5);

        assertEquals(10, paged.getTotal());
        assertEquals(2, paged.getCurrent());
        assertEquals(5, paged.getSize());
        assertEquals(2, paged.getPages());
        assertEquals(2, paged.getRecords().size());
        assertEquals(11L, paged.getRecords().get(0).getId());
        assertEquals("first.md", paged.getRecords().get(0).getName());
        assertEquals("second.md", paged.getRecords().get(1).getName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldNormalizeInvalidPageBoundsWhenListSourcesPagedCalled() {
        when(sourceMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        sourceService.listSourcesPaged(100L, 0, -5);

        ArgumentCaptor<Page<SourceDO>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(sourceMapper).selectPage(pageCaptor.capture(), any());
        assertEquals(1, pageCaptor.getValue().getCurrent());
        assertEquals(1, pageCaptor.getValue().getSize());
    }
}
