package org.cn.liuwt.llmwiki.service.harness.mq;

import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.service.harness.task.BackgroundTaskExecutor;
import org.cn.liuwt.llmwiki.service.harness.task.BackgroundTaskRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineTaskConsumerDispatchTest {

    @Mock
    private BackgroundTaskRegistry backgroundTaskRegistry;

    @Mock
    private BackgroundTaskExecutor backgroundTaskExecutor;

    @Mock
    private HarnessEngine harnessEngine;

    @InjectMocks
    private PipelineTaskConsumer consumer;

    @Test
    void shouldRouteToBackgroundExecutorWhenHandlerRegistered() {
        when(backgroundTaskRegistry.hasHandler("query_save")).thenReturn(true);
        PipelineTaskMessage msg = taskMessage("query_save", 100L, 1L);

        ReflectionTestUtils.invokeMethod(consumer, "executeTask", msg);

        verify(backgroundTaskExecutor).execute(100L, "query_save");
    }

    @Test
    void shouldRouteToLegacySwitchWhenHandlerNotRegistered() {
        when(backgroundTaskRegistry.hasHandler("LINT_START")).thenReturn(false);
        PipelineTaskMessage msg = taskMessage("LINT_START", 5L, 10L);

        ReflectionTestUtils.invokeMethod(consumer, "executeTask", msg);

        verify(harnessEngine).executeLintWithExecution(5L, 10L, false);
    }

    @Test
    void shouldThrowWhenNeitherRegistryNorLegacySwitchMatches() {
        when(backgroundTaskRegistry.hasHandler("MYSTERY")).thenReturn(false);
        PipelineTaskMessage msg = taskMessage("MYSTERY", 6L, 10L);

        assertThrows(IllegalStateException.class,
            () -> ReflectionTestUtils.invokeMethod(consumer, "executeTask", msg));
    }

    private static PipelineTaskMessage taskMessage(String taskType, Long executionId, Long scopeId) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setTaskType(taskType);
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        return msg;
    }
}
