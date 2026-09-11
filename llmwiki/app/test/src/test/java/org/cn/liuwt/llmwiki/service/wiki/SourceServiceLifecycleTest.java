package org.cn.liuwt.llmwiki.service.wiki;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.system.AuditLogService;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SourceServiceLifecycleTest {

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private SourceMapper sourceMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private LintFindingService lintFindingService;

    @InjectMocks
    private SourceService sourceService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SourceDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
    }

    private SourceDO activeSource() {
        SourceDO source = new SourceDO();
        source.setId(5L);
        source.setScopeId(10L);
        source.setName("doc.pdf");
        source.setStatus("processed");
        source.setLifecycleStatus("ACTIVE");
        return source;
    }

    @Test
    void shouldMarkSourceDeprecatedWhenCategoryValid() {
        when(sourceMapper.selectOne(any())).thenReturn(activeSource());
        when(executionMapper.selectCount(any())).thenReturn(0L);

        SourceModel model = sourceService.deprecateSource(5L, 10L, 7L, "OUTDATED", "内容过时");

        assertEquals("DEPRECATED", model.getLifecycleStatus());
        assertEquals("OUTDATED", model.getDeprecatedCategory());
        assertEquals("内容过时", model.getDeprecatedReason());
        verify(sourceMapper).update(any(), any());
        verify(auditLogService).log(any());
    }

    @Test
    void shouldRejectDeprecateWhenSourceAlreadyDeprecated() {
        SourceDO deprecated = activeSource();
        deprecated.setLifecycleStatus("DEPRECATED");
        when(sourceMapper.selectOne(any())).thenReturn(deprecated);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> sourceService.deprecateSource(5L, 10L, 7L, "OUTDATED", null));

        assertEquals(ErrorCode.SOURCE_ALREADY_DEPRECATED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectDeprecateWhenIngestExecutionInFlight() {
        when(sourceMapper.selectOne(any())).thenReturn(activeSource());
        when(executionMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
            () -> sourceService.deprecateSource(5L, 10L, 7L, "OUTDATED", null));

        assertEquals(ErrorCode.SOURCE_DEPRECATE_WHILE_PROCESSING.getCode(), ex.getCode());
        verify(sourceMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectDeprecateWhenCategoryOtherWithoutReason() {
        when(sourceMapper.selectOne(any())).thenReturn(activeSource());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> sourceService.deprecateSource(5L, 10L, 7L, "OTHER", " "));

        assertEquals(ErrorCode.INVALID_PARAM.getCode(), ex.getCode());
    }

    @Test
    void shouldRestoreSourceAndResolveFindingsWhenUndeprecated() {
        SourceDO deprecated = activeSource();
        deprecated.setLifecycleStatus("DEPRECATED");
        deprecated.setDeprecatedCategory("OUTDATED");
        when(sourceMapper.selectOne(any())).thenReturn(deprecated);

        SourceModel model = sourceService.undeprecateSource(5L, 10L, 7L);

        assertEquals("ACTIVE", model.getLifecycleStatus());
        verify(lintFindingService).resolveSourceFindingsOnUndeprecate(10L, 5L);
        verify(auditLogService).log(any());
    }

    @Test
    void shouldRejectUndeprecateWhenSourceIsActive() {
        when(sourceMapper.selectOne(any())).thenReturn(activeSource());

        BusinessException ex = assertThrows(BusinessException.class,
            () -> sourceService.undeprecateSource(5L, 10L, 7L));

        assertEquals(ErrorCode.SOURCE_NOT_DEPRECATED.getCode(), ex.getCode());
    }

    @Test
    void shouldRejectPhysicalDeleteAlways() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> sourceService.deleteSource(5L, 10L));

        assertEquals(ErrorCode.SOURCE_DELETE_FORBIDDEN.getCode(), ex.getCode());
        verify(sourceMapper, never()).deleteById(anyLong());
    }
}
