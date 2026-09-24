package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
    @Mock private ScopeMapper scopeMapper;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks
    private IngestBatchScheduler scheduler;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
    }

    @Test
    void shouldNotDispatchWriteWhenWriteGateBusy() {
        stubQueries(List.of(runningWriteExecution(7L)), List.of(executionWithId(2L, "confirmed", null)));

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldDispatchAnalyzeWhileWriteRunning() {
        stubQueries(List.of(runningWriteExecution(7L)), List.of(executionWithId(1L, "pending", null)));
        when(executionTracker.getExecution(1L)).thenReturn(modelWithoutSteps());
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(1L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
    }

    @Test
    void shouldFillAnalyzeGateUpToConcurrency() {
        stubQueries(List.of(), List.of(
            executionWithId(1L, "pending", null),
            executionWithId(2L, "pending", null),
            executionWithId(3L, "pending", null)));
        when(executionTracker.getExecution(1L)).thenReturn(modelWithoutSteps());
        when(executionTracker.getExecution(2L)).thenReturn(modelWithoutSteps());
        when(executionTracker.getExecution(3L)).thenReturn(modelWithoutSteps());
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(1L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
        verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
        verify(dispatcher, never()).dispatch(eq(3L), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRespectAnalyzeGateWhenSaturated() {
        ReflectionTestUtils.setField(scheduler, "analyzeConcurrency", 1);
        stubQueries(List.of(), List.of(
            executionWithId(1L, "pending", null),
            executionWithId(2L, "pending", null)));
        when(executionTracker.getExecution(1L)).thenReturn(modelWithoutSteps());
        when(executionTracker.getExecution(2L)).thenReturn(modelWithoutSteps());
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(1L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
        verify(dispatcher, never()).dispatch(eq(2L), any(), any(), any(), any(), any());
    }

    @Test
    void shouldPreferConfirmedThenFillAnalyzeGate() {
        stubQueries(List.of(), List.of(
            executionWithId(1L, "pending", null),
            executionWithId(2L, "confirmed", null)));
        when(executionTracker.getExecution(1L)).thenReturn(modelWithoutSteps());
        ExecutionModel confirmed = new ExecutionModel();
        confirmed.setId(2L);
        confirmed.setStatus("confirmed");
        when(executionTracker.getExecution(2L)).thenReturn(confirmed);
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_EXECUTE), any());
        order.verify(dispatcher).dispatch(eq(1L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
    }

    @Test
    void shouldSkipCandidatesOfPausedBatch() {
        stubQueries(List.of(), List.of(executionWithId(3L, "pending", 77L)));
        IngestBatchDO paused = new IngestBatchDO();
        paused.setId(77L);
        paused.setStatus("paused");
        when(batchMapper.selectById(77L)).thenReturn(paused);

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldDispatchConfirmedCandidateOfCompletedBatch() {
        stubQueries(List.of(), List.of(executionWithId(3L, "confirmed", 77L)));
        IngestBatchDO completed = new IngestBatchDO();
        completed.setId(77L);
        completed.setStatus("completed");
        when(batchMapper.selectById(77L)).thenReturn(completed);
        ExecutionModel confirmed = new ExecutionModel();
        confirmed.setId(3L);
        confirmed.setStatus("confirmed");
        when(executionTracker.getExecution(3L)).thenReturn(confirmed);
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(3L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_EXECUTE), any());
    }

    @Test
    void shouldSkipCancelledBatchEvenWithConfirmedCandidate() {
        stubQueries(List.of(), List.of(executionWithId(3L, "confirmed", 77L)));
        IngestBatchDO cancelled = new IngestBatchDO();
        cancelled.setId(77L);
        cancelled.setStatus("cancelled");
        when(batchMapper.selectById(77L)).thenReturn(cancelled);

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRecheckCandidateWhenCasLoses() {
        stubQueries(List.of(), List.of(
            executionWithId(1L, "pending", null),
            executionWithId(2L, "pending", null)));
        when(executionTracker.getExecution(1L)).thenReturn(modelWithoutSteps());
        when(executionTracker.getExecution(2L)).thenReturn(modelWithoutSteps());
        when(executionMapper.update(any(), any())).thenReturn(0, 1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_ANALYZE), any());
    }

    @Test
    void shouldDeferWriteClaimWhenRemoteWritePhaseRunning() {
        ReflectionTestUtils.setField(scheduler, "mqEnabled", true);
        stubQueries(List.of(), List.of(executionWithId(2L, "confirmed", null)));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(scopeMapper.lockScopeRow(10L)).thenReturn(10L);
        ExecutionDO remoteWriter = runningWriteExecution(9L);
        when(executionMapper.selectRunningIngestForUpdate(10L)).thenReturn(List.of(remoteWriter));

        scheduler.kick(10L);

        verify(executionMapper, never()).update(any(), any());
        verify(dispatcher, never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldClaimWritePhaseWhenOnlyAnalyzeRunning() {
        ReflectionTestUtils.setField(scheduler, "mqEnabled", true);
        stubQueries(List.of(executionWithId(9L, "running", null)),
            List.of(executionWithId(2L, "confirmed", null)));
        when(executionTracker.getExecution(9L)).thenReturn(modelWithoutSteps());
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(scopeMapper.lockScopeRow(10L)).thenReturn(10L);
        when(executionMapper.selectRunningIngestForUpdate(10L)).thenReturn(List.of(executionWithId(9L, "running", null)));
        when(executionMapper.update(any(), any())).thenReturn(1);

        scheduler.kick(10L);

        verify(dispatcher).dispatch(eq(2L), eq(10L), any(), any(), eq(PipelineTaskMessage.TYPE_INGEST_EXECUTE), any());
    }

    private void stubQueries(List<ExecutionDO> running, List<ExecutionDO> candidates) {
        when(executionMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ExecutionDO> wrapper = invocation.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("running") ? running : candidates;
        });
    }

    private ExecutionDO runningWriteExecution(Long id) {
        ExecutionModel model = new ExecutionModel();
        model.setId(id);
        model.setStatus("running");
        model.setSteps(List.of(completedStep(IngestStep.UPLOAD), completedStep(IngestStep.ANALYZE)));
        when(executionTracker.getExecution(id)).thenReturn(model);
        return executionWithId(id, "running", null);
    }

    private ExecutionStepModel completedStep(IngestStep step) {
        ExecutionStepModel model = new ExecutionStepModel();
        model.setStepName(step.name());
        model.setStatus("completed");
        return model;
    }

    private ExecutionModel modelWithoutSteps() {
        ExecutionModel model = new ExecutionModel();
        model.setSteps(List.of());
        return model;
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
