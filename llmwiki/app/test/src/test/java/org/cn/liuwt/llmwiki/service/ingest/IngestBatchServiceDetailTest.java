package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchDetailInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchItemInfo;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestBatchServiceDetailTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private ExecutionStepMapper executionStepMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private SourceMapper sourceMapper;
    @Mock private ExecutionTracker executionTracker;
    @Mock private IngestService ingestService;
    @Mock private IngestDispatcher ingestDispatcher;
    @Mock private ExecutionNodeRegistry registry;
    @Mock private MqHealthService mqHealthService;
    @Mock private SourceService sourceService;
    @Mock private ExecutionEventLogService executionEventLogService;
    @Mock private ExecutionBaselineService executionBaselineService;

    @InjectMocks
    private IngestBatchService ingestBatchService;

    private static final Long SCOPE_ID = 100L;
    private static final Long BATCH_ID = 50L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SourceDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionStepDO.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestBatchService, "maxBatchSize", 200);
        ReflectionTestUtils.setField(ingestBatchService, "analyzeConcurrency", 2);
    }

    private IngestBatchDO batch(long id, String mode, int totalCount) {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(id);
        batch.setScopeId(SCOPE_ID);
        batch.setUserId(7L);
        batch.setStatus("active");
        batch.setMode(mode);
        batch.setTotalCount(totalCount);
        return batch;
    }

    private ExecutionDO item(long id, String status) {
        ExecutionDO execution = new ExecutionDO();
        execution.setId(id);
        execution.setType("ingest");
        execution.setScopeId(SCOPE_ID);
        execution.setBatchId(BATCH_ID);
        execution.setSourceId(id * 10);
        execution.setStatus(status);
        return execution;
    }

    private SourceDO source(long id) {
        SourceDO source = new SourceDO();
        source.setId(id);
        source.setScopeId(SCOPE_ID);
        source.setName("doc-" + id + ".pdf");
        source.setFormat("pdf");
        return source;
    }

    private ExecutionStepDO step(long executionId, String stepName, String outputData) {
        ExecutionStepDO step = new ExecutionStepDO();
        step.setExecutionId(executionId);
        step.setStepName(stepName);
        step.setStatus("completed");
        step.setOutputData(outputData);
        return step;
    }

    private void stubDetailCommon(IngestBatchDO batch, List<ExecutionDO> items,
                                  List<ExecutionStepDO> steps, Map<Long, Map<String, Object>> turnEndPayloads) {
        when(batchMapper.selectById(batch.getId())).thenReturn(batch);
        when(executionMapper.selectList(any())).thenReturn(items);
        when(sourceMapper.selectBatchIds(anyList()))
            .thenReturn(items.stream().map(i -> source(i.getSourceId())).toList());
        when(executionStepMapper.selectList(any())).thenReturn(steps);
        when(executionEventLogService.loadLatestTurnEndPayloads(anyCollection())).thenReturn(turnEndPayloads);
    }

    @Test
    void shouldPopulateAutoDecisionFromTurnEndEvent() {
        ExecutionDO item = item(501L, "completed");
        stubDetailCommon(batch(BATCH_ID, "auto", 1), List.of(item), List.of(),
            Map.of(501L, Map.<String, Object>of(
                "status", "completed",
                "totalTokens", 120,
                "autoDecision", Map.of("autoApprove", true, "hardBlocked", false, "softScore", 0, "reasons", List.of()))));

        IngestBatchDetailInfo detail = ingestBatchService.getBatchDetail(BATCH_ID, 1, 50);

        assertEquals("auto", detail.getMode());
        assertEquals(1, detail.getAutoCompleted());
        assertEquals(120L, detail.getTotalTokensSum());
        assertEquals(0L, detail.getEtaSeconds());
        IngestBatchItemInfo info = detail.getItems().get(0);
        assertNotNull(info.getAutoDecision());
        assertEquals(Boolean.TRUE, info.getAutoDecision().get("autoApprove"));
    }

    @Test
    void shouldPopulateQualityCountsFromCompleteStepOutput() {
        ExecutionDO item = item(502L, "completed");
        stubDetailCommon(batch(BATCH_ID, "review", 1), List.of(item),
            List.of(step(502L, "COMPLETE", "{\"status\":\"completed\",\"qualityCritical\":2,\"qualityWarnings\":3}")),
            Map.of(502L, Map.<String, Object>of("status", "completed", "totalTokens", 50)));

        IngestBatchDetailInfo detail = ingestBatchService.getBatchDetail(BATCH_ID, 1, 50);

        IngestBatchItemInfo info = detail.getItems().get(0);
        assertEquals(2, info.getQualityCritical());
        assertEquals(3, info.getQualityWarnings());
        assertEquals(50L, detail.getTotalTokensSum());
    }

    @Test
    void shouldComputeEtaFromBaselineProfile() {
        List<ExecutionDO> items = List.of(
            item(511L, "pending"), item(512L, "running"),
            item(513L, "confirmed"), item(514L, "pending"));
        stubDetailCommon(batch(BATCH_ID, "auto", 4), items, List.of(), Map.of());
        when(executionBaselineService.getProfile(SCOPE_ID, "unknown"))
            .thenReturn(Map.of("UPLOAD", 1000L, "ANALYZE", 3000L));

        IngestBatchDetailInfo detail = ingestBatchService.getBatchDetail(BATCH_ID, 1, 50);

        assertEquals(8L, detail.getEtaSeconds());
    }

    @Test
    void shouldReturnNullEtaWhenBaselineMissing() {
        stubDetailCommon(batch(BATCH_ID, "auto", 1), List.of(item(515L, "pending")), List.of(), Map.of());
        when(executionBaselineService.getProfile(SCOPE_ID, "unknown")).thenReturn(Map.of());

        IngestBatchDetailInfo detail = ingestBatchService.getBatchDetail(BATCH_ID, 1, 50);

        assertNull(detail.getEtaSeconds());
    }

    @Test
    void shouldComputeGroupCounts() {
        ExecutionDO autoCompleted = item(521L, "completed");
        ExecutionDO manualCompleted = item(522L, "completed");
        ExecutionDO awaitingConfirm = item(523L, "awaiting_confirmation");
        ExecutionDO awaitingReview = item(524L, "awaiting_review");
        ExecutionDO failed = item(525L, "failed");
        failed.setErrorMessage("boom");
        ExecutionDO cancelled = item(526L, "cancelled");
        List<ExecutionDO> items = List.of(
            autoCompleted, manualCompleted, awaitingConfirm, awaitingReview, failed, cancelled);
        stubDetailCommon(batch(BATCH_ID, "auto", 6), items, List.of(),
            Map.of(
                521L, Map.<String, Object>of("status", "completed", "totalTokens", 100,
                    "autoDecision", Map.of("autoApprove", true, "hardBlocked", false, "softScore", 0, "reasons", List.of())),
                522L, Map.<String, Object>of("status", "completed", "totalTokens", 200)));

        IngestBatchDetailInfo detail = ingestBatchService.getBatchDetail(BATCH_ID, 1, 50);

        assertEquals("auto", detail.getMode());
        assertEquals(1, detail.getAutoCompleted());
        assertEquals(2, detail.getManualPending());
        assertEquals(1, detail.getFailedCount());
        assertEquals(1, detail.getCancelledCount());
        assertEquals(300L, detail.getTotalTokensSum());
        assertEquals(0L, detail.getEtaSeconds());
        IngestBatchItemInfo failedInfo = detail.getItems().stream()
            .filter(i -> i.getExecutionId().equals(525L)).findFirst().orElseThrow();
        assertEquals("boom", failedInfo.getErrorSummary());
    }

    @Test
    void shouldCancelOnlyAwaitingItemsInCancelItems() {
        when(batchMapper.selectById(60L)).thenReturn(batch(60L, "review", 4));
        ExecutionDO awaitingConfirm = item(531L, "awaiting_confirmation");
        ExecutionDO awaitingReview = item(532L, "awaiting_review");
        ExecutionDO running = item(533L, "running");
        ExecutionDO completed = item(534L, "completed");
        when(executionMapper.selectList(any()))
            .thenReturn(List.of(awaitingConfirm, awaitingReview, running, completed));
        when(executionTracker.cancelExecution(531L, "batch_item_rejected")).thenReturn(true);
        when(executionTracker.cancelExecution(532L, "batch_item_rejected")).thenReturn(true);

        Map<String, Integer> result = ingestBatchService.cancelItems(60L, List.of(531L, 532L, 533L, 534L));

        assertEquals(2, result.get("cancelled"));
        assertEquals(2, result.get("skipped"));
        verify(executionTracker).cancelExecution(531L, "batch_item_rejected");
        verify(executionTracker).cancelExecution(532L, "batch_item_rejected");
        verify(executionTracker, never()).cancelExecution(eq(533L), anyString());
        verify(executionTracker, never()).cancelExecution(eq(534L), anyString());
    }
}
