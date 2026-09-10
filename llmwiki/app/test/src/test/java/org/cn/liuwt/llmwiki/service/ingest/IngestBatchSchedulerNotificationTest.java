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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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
    }

    @Test
    void shouldNotifyOnceWhenAllAnalysisSettled() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("active");
        batch.setTotalCount(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting = item(1L, "awaiting_confirmation", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting, failed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(failed);

        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_analyzed"), any(), contains("1"), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldCompleteBatchAndNotifyWhenAllItemsTerminal() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("active");
        batch.setTotalCount(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO completed = item(1L, "completed", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(completed, failed));
        when(notificationMapper.selectCount(any())).thenReturn(0L);

        scheduler.handleBatchSettlement(failed);

        verify(batchMapper).updateById(any(IngestBatchDO.class));
        verify(notificationService).createNotification(eq(7L), eq("ingest_batch_completed"), any(), any(), eq(10L), isNull(), isNull(), eq(9L));
    }

    @Test
    void shouldSkipNotificationWhenAlreadyNotified() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("active");
        batch.setTotalCount(2);
        when(batchMapper.selectById(9L)).thenReturn(batch);
        ExecutionDO awaiting = item(1L, "awaiting_confirmation", 9L);
        ExecutionDO failed = item(2L, "failed", 9L);
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting, failed));
        when(notificationMapper.selectCount(any())).thenReturn(1L);

        scheduler.handleBatchSettlement(failed);

        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any(), any());
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
