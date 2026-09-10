package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchSchedulerNotificationTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationService notificationService;
    @Mock private IngestDispatcher dispatcher;
    @Mock private IngestService ingestService;
    @Mock private ExecutionTracker executionTracker;
    @InjectMocks private IngestBatchScheduler scheduler;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, NotificationDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
    }

    @Test
    void shouldNotifyOnceWhenAllAnalysisSettled() {
        IngestBatchDO batch = newBatch(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting = item(1L, "awaiting_confirmation", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting, failed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(failed);

        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_analyzed"), any(), contains("1 份待审阅"), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldCompleteBatchAndNotifyWhenAllItemsTerminal() {
        IngestBatchDO batch = newBatch(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO completed = item(1L, "completed", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(completed, failed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(failed);

        ArgumentCaptor<IngestBatchDO> batchCaptor = ArgumentCaptor.forClass(IngestBatchDO.class);
        verify(batchMapper).updateById(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getStatus()).isEqualTo("completed");
        assertThat(batchCaptor.getValue().getCompletedAt()).isNotNull();
        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_completed"), any(), any(), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldSkipNotificationWhenAlreadyNotified() {
        IngestBatchDO batch = newBatch(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting = item(1L, "awaiting_confirmation", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting, failed));
        when(notificationMapper.selectCount(any())).thenReturn(1L);

        scheduler.handleBatchSettlement(failed);

        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotifyAnalyzedWithoutAwaitingWhenSingleItemBatch() {
        IngestBatchDO batch = newBatch(1);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting = item(1L, "awaiting_confirmation", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(awaiting);

        verify(notificationService, never()).createNotification(any(), eq("ingest_batch_awaiting"), any(), any(), any(), any(), any(), any());
        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_analyzed"), any(), eq("分析完成，待审阅"), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldNotNotifyWhenPausedItemExists() {
        IngestBatchDO batch = newBatch(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO completed = item(1L, "completed", 9L);
        ExecutionDO paused = item(2L, "paused", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(completed, paused));

        scheduler.handleBatchSettlement(paused);

        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSettleActiveBatchOnRecoverScanWhenAllItemsTerminal() {
        IngestBatchDO batch = newBatch(1);
        when(batchMapper.selectList(any())).thenReturn(List.of(batch));
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO failed = item(1L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(failed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.recoverScan();

        verify(batchMapper).updateById(any(IngestBatchDO.class));
        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_completed"), any(), any(), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldNotSettleWhenBatchHasNoItems() {
        IngestBatchDO batch = newBatch(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of());

        scheduler.handleBatchSettlement(failed);

        verify(batchMapper, never()).updateById(any(IngestBatchDO.class));
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReportAwaitingAndFailedCountsIndependently() {
        IngestBatchDO batch = newBatch(3);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting1 = item(1L, "awaiting_confirmation", 9L);
        ExecutionDO awaiting2 = item(2L, "awaiting_confirmation", 9L);
        ExecutionDO completed = item(3L, "completed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting1, awaiting2, completed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(completed);

        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_awaiting"), any(), eq("本批已有 2 份分析完成，可前往审阅收件箱集中确认"), eq(10L), isNull(), isNull(), eq(9L));
        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_analyzed"), any(), eq("本批 2 份待审阅、0 份失败"), eq(10L), isNull(), isNull(), eq(9L));
    }

    private IngestBatchDO newBatch(int totalCount) {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("active");
        batch.setTotalCount(totalCount);
        return batch;
    }

    private ExecutionDO item(Long id, String status, Long batchId) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setType("ingest");
        execution.setScopeId(10L);
        execution.setStatus(status);
        execution.setBatchId(batchId);
        return execution;
    }
}
