package org.cn.liuwt.llmwiki.domain.service.wiki;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
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

    @Test
    void shouldCreateMdSourceWithHashWhenUploadTextSourceCalled() throws Exception {
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.getLastModifiedTime(anyString(), anyString())).thenReturn(null);

        String content = "# 标题\n正文";
        SourceModel model = sourceService.uploadTextSource("会议纪要", content, 100L, 7L);

        assertEquals("会议纪要.md", model.getName());
        assertEquals("md", model.getFormat());
        assertEquals(100L, model.getScopeId());
        assertEquals((long) content.getBytes(StandardCharsets.UTF_8).length, model.getSize());
        assertNotNull(model.getContentHash());
        assertEquals(64, model.getContentHash().length()); // SHA-256 hex

        ArgumentCaptor<SourceDO> captor = ArgumentCaptor.forClass(SourceDO.class);
        verify(sourceMapper).insert(captor.capture());
        assertTrue(captor.getValue().getFilePath().startsWith("raw/"));
        verify(storageProvider).write(eq("100"), startsWith("raw/"),
                any(InputStream.class), eq((long) content.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void shouldAppendMdExtensionWhenTitleHasNone() throws Exception {
        when(sourceMapper.selectOne(any())).thenReturn(null);
        when(storageProvider.getLastModifiedTime(anyString(), anyString())).thenReturn(null);

        SourceModel model = sourceService.uploadTextSource("readme", "内容", 100L, 7L);

        assertEquals("readme.md", model.getName());
    }
}
