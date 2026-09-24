package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchServiceTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private ScopeMapper scopeMapper;
    @Mock private SourceMapper sourceMapper;
    @Mock private ExecutionTracker executionTracker;
    @Mock private SourceService sourceService;

    @InjectMocks
    private IngestBatchService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
    }

    @Test
    void shouldRejectBatchWhenExceedingMaxSize() {
        ReflectionTestUtils.setField(service, "maxBatchSize", 2);
        List<Long> sourceIds = List.of(1L, 2L, 3L);

        assertThrows(BusinessException.class, () -> service.createBatch(10L, 7L, sourceIds, null, null, null));
    }

    @Test
    void shouldSkipSourcesAndReportWarningsWhenSourceHasActiveExecution() {
        ReflectionTestUtils.setField(service, "maxBatchSize", 50);
        SourceDO ok = new SourceDO();
        ok.setId(1L);
        ok.setScopeId(10L);
        ok.setName("a.md");
        SourceDO busy = new SourceDO();
        busy.setId(2L);
        busy.setScopeId(10L);
        busy.setName("b.pdf");
        when(sourceMapper.selectBatchIds(any())).thenReturn(List.of(ok, busy));
        ExecutionDO inFlight = new ExecutionDO();
        inFlight.setId(100L);
        inFlight.setType("ingest");
        inFlight.setStatus("awaiting_confirmation");
        inFlight.setSourceId(2L);
        inFlight.setScopeId(10L);
        when(executionMapper.selectList(any())).thenReturn(List.of(inFlight));
        ExecutionModel created = new ExecutionModel();
        created.setId(500L);
        when(executionTracker.createExecution(eq("ingest"), eq(10L), eq(1L), isNull())).thenReturn(created);

        IngestBatchCreateInfo info = service.createBatch(10L, 7L, List.of(1L, 2L), "指引领", null, null);

        assertEquals(1, info.acceptedCount());
        assertEquals(1, info.warnings().size());
    }

    @Test
    void shouldSkipDeprecatedSourceWithWarningWhenCreatingBatch() {
        ReflectionTestUtils.setField(service, "maxBatchSize", 50);
        SourceDO active = new SourceDO();
        active.setId(1L);
        active.setScopeId(10L);
        active.setName("a.md");
        active.setLifecycleStatus("ACTIVE");
        SourceDO deprecated = new SourceDO();
        deprecated.setId(2L);
        deprecated.setScopeId(10L);
        deprecated.setName("old.pdf");
        deprecated.setLifecycleStatus("DEPRECATED");
        when(sourceMapper.selectBatchIds(any())).thenReturn(List.of(active, deprecated));
        when(executionMapper.selectList(any())).thenReturn(List.of());
        ExecutionModel created = new ExecutionModel();
        created.setId(500L);
        when(executionTracker.createExecution(eq("ingest"), eq(10L), eq(1L), isNull())).thenReturn(created);

        IngestBatchCreateInfo info = service.createBatch(10L, 7L, List.of(1L, 2L), null, null, null);

        assertEquals(1, info.acceptedCount());
        assertEquals(1, info.warnings().size());
    }

    @Test
    void shouldReviveCompletedBatchWhenConfirmItems() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(77L);
        batch.setStatus("completed");
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(executionMapper.update(any(), any())).thenReturn(1);
        when(batchMapper.update(isNull(), any())).thenReturn(1);
        ArgumentCaptor<LambdaUpdateWrapper<IngestBatchDO>> captor = batchCaptor();

        int confirmed = service.confirmItems(77L, List.of(100L));

        assertEquals(1, confirmed);
        verify(batchMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<IngestBatchDO> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue("completed"));
        assertTrue(params.containsValue("active"));
    }

    @Test
    void shouldNotReviveBatchWhenNoItemConfirmed() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(77L);
        when(batchMapper.selectById(77L)).thenReturn(batch);
        when(executionMapper.update(any(), any())).thenReturn(0);

        int confirmed = service.confirmItems(77L, List.of(100L));

        assertEquals(0, confirmed);
        verify(batchMapper, never()).update(any(), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<IngestBatchDO>> batchCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }
}
