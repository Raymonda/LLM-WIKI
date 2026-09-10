package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchSchedulerTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private ExecutionTracker executionTracker;
    @Mock private IngestDispatcher dispatcher;
    @Mock private IngestService ingestService;

    @InjectMocks
    private IngestBatchScheduler scheduler;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
    }

    @Test
    void shouldNotDispatchWhenAnotherIngestRunningInScope() {
        when(executionMapper.selectCount(any())).thenReturn(1L);
        lenient().when(executionMapper.selectList(any())).thenReturn(List.of(executionWithId(1L, "pending", null)));

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldPreferOldestConfirmedOverPending() {
        when(executionMapper.selectCount(any())).thenReturn(0L);
        ExecutionDO pendingOld = executionWithId(1L, "pending", null);
        ExecutionDO confirmedNew = executionWithId(2L, "confirmed", null);
        when(executionMapper.selectList(any())).thenReturn(List.of(pendingOld, confirmedNew));
        when(executionMapper.update(any(), any())).thenReturn(1);
        ExecutionModel model = new ExecutionModel();
        model.setId(2L);
        model.setStatus("confirmed");
        when(executionTracker.getExecution(2L)).thenReturn(model);

        scheduler.kick(10L);

        ArgumentCaptor<Wrapper<ExecutionDO>> cas = ArgumentCaptor.forClass(Wrapper.class);
        verify(executionMapper).update(any(), cas.capture());
        LambdaUpdateWrapper<ExecutionDO> updateWrapper = (LambdaUpdateWrapper<ExecutionDO>) cas.getValue();
        updateWrapper.getSqlSegment();
        assertTrue(updateWrapper.getParamNameValuePairs().containsValue(2L));
        verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_EXECUTE), any());
    }

    @Test
    void shouldSkipCandidatesOfPausedBatch() {
        when(executionMapper.selectCount(any())).thenReturn(0L);
        ExecutionDO itemOfPausedBatch = executionWithId(3L, "pending", 77L);
        when(executionMapper.selectList(any())).thenReturn(List.of(itemOfPausedBatch));
        IngestBatchDO paused = new IngestBatchDO();
        paused.setId(77L);
        paused.setStatus("paused");
        when(batchMapper.selectById(77L)).thenReturn(paused);

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRecheckCandidateWhenCasLoses() {
        when(executionMapper.selectCount(any())).thenReturn(0L);
        ExecutionDO first = executionWithId(1L, "pending", null);
        ExecutionDO second = executionWithId(2L, "pending", null);
        when(executionMapper.selectList(any())).thenReturn(List.of(first, second));
        when(executionMapper.update(any(), any())).thenReturn(0, 1);
        ExecutionModel model = new ExecutionModel();
        model.setId(2L);
        model.setStatus("running");
        when(executionTracker.getExecution(2L)).thenReturn(model);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), any(), any());
    }

    private ExecutionDO executionWithId(Long id, String status, Long batchId) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setType("ingest");
        execution.setScopeId(10L);
        execution.setSourceId(500L + id);
        execution.setStatus(status);
        execution.setBatchId(batchId);
        return execution;
    }
}
