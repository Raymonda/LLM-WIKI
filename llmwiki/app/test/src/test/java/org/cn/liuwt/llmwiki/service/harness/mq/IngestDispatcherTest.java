package org.cn.liuwt.llmwiki.service.harness.mq;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestDispatcherTest {

    @Mock
    private ExecutionNodeRegistry registry;

    @Mock
    private MqHealthService mqHealthService;

    @InjectMocks
    private IngestDispatcher dispatcher;

    @Test
    void shouldRunLocalFallbackWhenMqDisabled() {
        Runnable fallback = mock(Runnable.class);

        dispatcher.dispatch(1L, 2L, 3L, null, PipelineTaskMessage.TYPE_INGEST_ANALYZE, fallback);

        verify(fallback, times(1)).run();
        verify(mqHealthService, never()).markSendSuccess();
    }

    @Test
    void shouldRunLocalFallbackWhenMqSendThrows() {
        ReflectionTestUtils.setField(dispatcher, "mqEnabled", true);
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        ReflectionTestUtils.setField(dispatcher, "rocketMQTemplate", template);
        when(mqHealthService.shouldAttempt()).thenReturn(true);
        doThrow(new RuntimeException("down")).when(template).convertAndSend(any(String.class), any(Object.class));
        Runnable fallback = mock(Runnable.class);

        dispatcher.dispatch(1L, 2L, 3L, null, PipelineTaskMessage.TYPE_INGEST_ANALYZE, fallback);

        verify(fallback, times(1)).run();
        verify(mqHealthService, times(1)).markSendFailed();
    }
}
