package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.NotificationDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.NotificationMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.model.system.ScopeModel;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoSuspendCircuitBreakerTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private NotificationService notificationService;
    @Mock private ScopeMapper scopeMapper;
    @Mock private WikiPageSourceMapper wikiPageSourceMapper;
    @Mock private ExecutionEventLogService executionEventLogService;
    @InjectMocks private IngestBatchScheduler scheduler;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
        TableInfoHelper.initTableInfo(assistant, NotificationDO.class);
        TableInfoHelper.initTableInfo(assistant, ScopeDO.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldSuspendWhenFailureRateExceeds() {
        stubSettlement(batch("auto", 10), items(6, 4));
        when(scopeMapper.selectById(10L)).thenReturn(scope(false));
        when(scopeMapper.update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any())).thenReturn(1);

        scheduler.handleBatchSettlement(trigger());

        ArgumentCaptor<LambdaUpdateWrapper<ScopeDO>> captor =
            ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(scopeMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("auto_suspended");
        verify(notificationService).createPersonalNotification(eq(7L), eq("auto_mode_suspended"),
            any(), argThat(c -> c.contains("批次 #9") && c.contains("40%（4/10）")), eq(10L), isNull(), isNull());
    }

    @Test
    void shouldNotSuspendBelowMinBatchSize() {
        stubSettlement(batch("auto", 4), items(1, 3));

        scheduler.handleBatchSettlement(trigger());

        verify(scopeMapper, never()).update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any());
        verify(notificationService, never()).createPersonalNotification(any(), eq("auto_mode_suspended"),
            any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotSuspendBelowMinFailures() {
        stubSettlement(batch("auto", 5), items(3, 2));

        scheduler.handleBatchSettlement(trigger());

        verify(scopeMapper, never()).update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any());
        verify(notificationService, never()).createPersonalNotification(any(), eq("auto_mode_suspended"),
            any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotSuspendBelowRate() {
        stubSettlement(batch("auto", 20), items(15, 5));

        scheduler.handleBatchSettlement(trigger());

        verify(scopeMapper, never()).update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any());
        verify(notificationService, never()).createPersonalNotification(any(), eq("auto_mode_suspended"),
            any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotSuspendForReviewModeBatch() {
        stubSettlement(batch("review", 10), items(6, 4));

        scheduler.handleBatchSettlement(trigger());

        verify(scopeMapper, never()).update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any());
        verify(notificationService, never()).createPersonalNotification(any(), eq("auto_mode_suspended"),
            any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotDuplicateSuspendWhenAlreadySuspended() {
        stubSettlement(batch("auto", 10), items(6, 4));
        when(scopeMapper.selectById(10L)).thenReturn(scope(true));

        scheduler.handleBatchSettlement(trigger());

        verify(scopeMapper).update(isNull(), ArgumentMatchers.<LambdaUpdateWrapper<ScopeDO>>any());
        verify(notificationService, never()).createPersonalNotification(any(), eq("auto_mode_suspended"),
            any(), any(), any(), any(), any());
    }

    @Nested
    class ScopeRecoveryTests {

        @Mock private ScopeMapper scopeMapper;
        @InjectMocks private ScopeServiceImpl scopeService;

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void shouldClearSuspensionWhenIngestModeExplicitlyUpdated() {
            ScopeModel model = new ScopeModel();
            model.setId(10L);
            model.setIngestMode("review");

            scopeService.updateScope(model);

            ArgumentCaptor<LambdaUpdateWrapper<ScopeDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
            verify(scopeMapper).update(isNull(), captor.capture());
            assertThat(captor.getValue().getSqlSet()).contains("auto_suspended");
        }
    }

    private void stubSettlement(IngestBatchDO batch, List<ExecutionDO> items) {
        when(batchMapper.selectById(9L)).thenReturn(batch);
        when(executionMapper.selectList(any())).thenReturn(items);
        when(notificationMapper.selectCount(any())).thenReturn(0L);
        when(executionEventLogService.loadLatestTurnEndPayloads(any())).thenReturn(Map.of());
    }

    private ExecutionDO trigger() {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(999L);
        execution.setType("ingest");
        execution.setScopeId(10L);
        execution.setBatchId(9L);
        execution.setStatus("failed");
        return execution;
    }

    private IngestBatchDO batch(String mode, int totalCount) {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(10L);
        batch.setUserId(7L);
        batch.setStatus("running");
        batch.setMode(mode);
        batch.setTotalCount(totalCount);
        return batch;
    }

    private List<ExecutionDO> items(int completed, int failed) {
        List<ExecutionDO> items = new ArrayList<>();
        long id = 1L;
        for (int i = 0; i < completed; i++) {
            items.add(item(id++, "completed"));
        }
        for (int i = 0; i < failed; i++) {
            items.add(item(id++, "failed"));
        }
        return items;
    }

    private ExecutionDO item(Long id, String status) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setType("ingest");
        execution.setScopeId(10L);
        execution.setBatchId(9L);
        execution.setStatus(status);
        return execution;
    }

    private ScopeDO scope(boolean autoSuspended) {
        ScopeDO scope = new ScopeDO();
        scope.setId(10L);
        scope.setOwnerId(7L);
        scope.setAutoSuspended(autoSuspended);
        return scope;
    }
}
