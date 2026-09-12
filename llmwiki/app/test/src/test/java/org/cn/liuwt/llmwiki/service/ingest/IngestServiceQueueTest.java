package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestServiceQueueTest {

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private ExecutionMapper executionMapper;

    @InjectMocks
    private IngestService ingestService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
    }

    @Test
    void shouldPersistGuidanceWhenCreatePendingIngestExecution() {
        ExecutionModel created = new ExecutionModel();
        created.setId(21L);
        created.setStatus("pending");
        when(executionTracker.createExecution("ingest", 5L, 55L, null)).thenReturn(created);
        when(executionTracker.getExecution(21L)).thenReturn(created);
        ArgumentCaptor<ExecutionDO> patchCaptor = ArgumentCaptor.forClass(ExecutionDO.class);

        ExecutionModel result = ingestService.createPendingIngestExecution(5L, 55L, "重点关注架构");

        assertEquals(21L, result.getId());
        verify(executionMapper).updateById(patchCaptor.capture());
        assertEquals("重点关注架构", patchCaptor.getValue().getGuidance());
    }

    @Test
    void shouldMarkConfirmedAndStoreGuidanceWhenQueueExecute() {
        when(executionMapper.update(any(), any())).thenReturn(1);
        ArgumentCaptor<LambdaUpdateWrapper<ExecutionDO>> captor = captor();

        boolean queued = ingestService.queueExecute(11L, "重点关注架构");

        assertTrue(queued);
        verify(executionMapper).update(any(), captor.capture());
        LambdaUpdateWrapper<ExecutionDO> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue("confirmed"));
        assertTrue(params.containsValue("awaiting_confirmation"));
        assertTrue(params.containsValue("awaiting_review"));
        assertTrue(params.containsValue("重点关注架构"));
    }

    @Test
    void shouldResetToPendingWhenQueueResume() {
        when(executionMapper.update(any(), any())).thenReturn(1);
        ArgumentCaptor<LambdaUpdateWrapper<ExecutionDO>> captor = captor();

        boolean queued = ingestService.queueResume(11L, null);

        assertTrue(queued);
        verify(executionMapper).update(any(), captor.capture());
        LambdaUpdateWrapper<ExecutionDO> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue("pending"));
        assertTrue(params.containsValue("failed"));
        assertTrue(params.containsValue("paused"));
    }

    @Test
    void shouldResetAnalyzeStepWhenQueueReanalyze() {
        ExecutionModel execution = executionWithStatus("awaiting_confirmation");
        ExecutionStepModel upload = step(4L, "UPLOAD", "completed");
        ExecutionStepModel analyze = step(5L, "ANALYZE", "completed");
        when(executionTracker.getExecution(11L)).thenReturn(execution);
        when(executionTracker.listSteps(11L)).thenReturn(List.of(upload, analyze));
        when(executionMapper.update(any(), any())).thenReturn(1);

        boolean queued = ingestService.queueReanalyze(11L, null);

        assertTrue(queued);
        verify(executionTracker).resetStepForRetry(5L);
    }

    @Test
    void shouldReturnFalseWhenQueueReanalyzeFindsNoPhase1Step() {
        ExecutionModel execution = executionWithStatus("awaiting_confirmation");
        ExecutionStepModel upload = step(4L, "UPLOAD", "completed");
        when(executionTracker.getExecution(11L)).thenReturn(execution);
        when(executionTracker.listSteps(11L)).thenReturn(List.of(upload));

        boolean queued = ingestService.queueReanalyze(11L, null);

        assertFalse(queued);
        verify(executionMapper, never()).update(any(), any());
        verify(executionTracker, never()).resetStepForRetry(anyLong());
    }

    @Test
    void shouldReturnFalseWhenQueueExecuteCasLoses() {
        when(executionMapper.update(any(), any())).thenReturn(0);

        boolean queued = ingestService.queueExecute(11L, null);

        assertFalse(queued);
    }

    @Test
    void shouldReturnFalseWhenQueueResumeCasLoses() {
        when(executionMapper.update(any(), any())).thenReturn(0);

        boolean queued = ingestService.queueResume(11L, null);

        assertFalse(queued);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaUpdateWrapper<ExecutionDO>> captor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private ExecutionModel executionWithStatus(String status) {
        ExecutionModel execution = new ExecutionModel();
        execution.setId(11L);
        execution.setStatus(status);
        return execution;
    }

    private ExecutionStepModel step(Long id, String name, String status) {
        ExecutionStepModel step = new ExecutionStepModel();
        step.setId(id);
        step.setStepName(name);
        step.setStatus(status);
        return step;
    }
}
