package org.cn.liuwt.llmwiki.service.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class IngestOrchestrationServiceTest {

    private IngestService ingestService;
    private SourceService sourceService;
    private ExecutionNodeRegistry registry;
    private MqHealthService mqHealthService;
    private ExecutionMapper executionMapper;
    private IngestOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        ingestService = mock(IngestService.class);
        sourceService = mock(SourceService.class);
        registry = mock(ExecutionNodeRegistry.class);
        mqHealthService = mock(MqHealthService.class);
        executionMapper = mock(ExecutionMapper.class);
        orchestration = new IngestOrchestrationService(
            ingestService, sourceService, registry, mqHealthService, executionMapper, null);
    }

    @Test
    void shouldRejectWhenSourceMissing() {
        when(sourceService.getSource(55L, 5L)).thenReturn(null);

        assertThatThrownBy(() -> orchestration.startIngest(5L, 55L, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldCreateExecutionAndRunPipelineLocallyWhenMqUnavailable() {
        SourceModel source = new SourceModel();
        source.setId(55L);
        source.setName("doc.md");
        when(sourceService.getSource(55L, 5L)).thenReturn(source);
        ExecutionModel execution = new ExecutionModel();
        execution.setId(99L);
        execution.setStatus("running");
        when(ingestService.createExecution(5L, 55L)).thenReturn(execution);
        when(mqHealthService.shouldAttempt()).thenReturn(false);
        when(registry.submitTask(any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });

        ExecutionModel result = orchestration.startIngest(5L, 55L, "guidance");

        assertThat(result.getId()).isEqualTo(99L);
        verify(ingestService).runIngestPipeline(99L, 5L, 55L, "guidance");
        verify(executionMapper).updateById(any(ExecutionDO.class));
    }

    @Test
    void shouldFailExecutionWhenPipelineThrows() {
        SourceModel source = new SourceModel();
        source.setId(55L);
        when(sourceService.getSource(55L, 5L)).thenReturn(source);
        ExecutionModel execution = new ExecutionModel();
        execution.setId(99L);
        when(ingestService.createExecution(5L, 55L)).thenReturn(execution);
        when(mqHealthService.shouldAttempt()).thenReturn(false);
        when(registry.submitTask(any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
        doThrow(new RuntimeException("boom")).when(ingestService)
            .runIngestPipeline(anyLong(), anyLong(), anyLong(), any());

        orchestration.startIngest(5L, 55L, null);

        verify(ingestService).failExecution(99L);
    }
}
