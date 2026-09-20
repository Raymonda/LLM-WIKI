package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestOrchestratorAutoConfirmTest {

    @Mock
    private ExecutionTracker executionTracker;

    @Mock
    private ExecutionEventLogService executionEventLog;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SchemaComplianceChecker schemaComplianceChecker;

    @Mock
    private ScopeMapper scopeMapper;

    @Mock
    private IngestBatchMapper ingestBatchMapper;

    @Mock
    private AutoConfirmPolicy autoConfirmPolicy;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private IngestOrchestrator orchestrator;

    private String resolveMode(Long executionId, Long scopeId) throws Exception {
        Method method = IngestOrchestrator.class.getDeclaredMethod("resolveIngestMode", Long.class, Long.class);
        method.setAccessible(true);
        return (String) method.invoke(orchestrator, executionId, scopeId);
    }

    private void invokeAwaitReview(Long executionId, Long scopeId, IngestContext context) throws Exception {
        Method method = IngestOrchestrator.class.getDeclaredMethod("awaitReview",
            Long.class, Long.class, String.class, int.class, boolean.class, IngestContext.class);
        method.setAccessible(true);
        method.invoke(orchestrator, executionId, scopeId, "doc.md", 0, true, context);
    }

    private void setAutoConfirmEnabled(boolean enabled) throws Exception {
        Field field = IngestOrchestrator.class.getDeclaredField("autoConfirmEnabled");
        field.setAccessible(true);
        field.setBoolean(orchestrator, enabled);
    }

    private static ScopeDO scope(Boolean autoSuspended, String ingestMode) {
        ScopeDO scope = new ScopeDO();
        scope.setAutoSuspended(autoSuspended);
        scope.setIngestMode(ingestMode);
        return scope;
    }

    private static IngestContext context() {
        IngestContext context = new IngestContext(1L, 2L, 1L, null);
        context.setWritingPlanJson("{\"affectedPagePlans\":{},\"entityPlans\":{\"e1\":{}},\"chapterPlans\":{}}");
        context.setMetadataJson("{}");
        return context;
    }

    @Test
    void shouldForceReviewWhenAutoSuspended() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(true, "auto"));

        assertEquals("review", resolveMode(1L, 1L));
    }

    @Test
    void shouldForceReviewWhenAutoSuspendedEvenWithAutoBatch() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(true, "auto"));

        assertEquals("review", resolveMode(1L, 1L));

        verify(ingestBatchMapper, never()).selectById(any());
    }

    @Test
    void shouldUseBatchReviewModeOverScopeAutoDefault() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        ExecutionModel execution = new ExecutionModel();
        execution.setBatchId(5L);
        when(executionTracker.getExecution(1L)).thenReturn(execution);
        IngestBatchDO batch = new IngestBatchDO();
        batch.setMode("review");
        when(ingestBatchMapper.selectById(5L)).thenReturn(batch);

        assertEquals("review", resolveMode(1L, 1L));
    }

    @Test
    void shouldUseBatchModeOverScopeDefault() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "review"));
        ExecutionModel execution = new ExecutionModel();
        execution.setBatchId(5L);
        when(executionTracker.getExecution(1L)).thenReturn(execution);
        IngestBatchDO batch = new IngestBatchDO();
        batch.setMode("auto");
        when(ingestBatchMapper.selectById(5L)).thenReturn(batch);

        assertEquals("auto", resolveMode(1L, 1L));
    }

    @Test
    void shouldFallBackToScopeDefaultWhenNoBatch() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());

        assertEquals("auto", resolveMode(1L, 1L));
    }

    @Test
    void shouldDefaultToReviewWhenNothingConfigured() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, null));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());

        assertEquals("review", resolveMode(1L, 1L));
    }

    @Test
    void shouldReturnReviewWhenAutoConfirmDisabled() throws Exception {
        setAutoConfirmEnabled(false);

        assertEquals("review", resolveMode(1L, 1L));
    }

    @Test
    void shouldConfirmAndPublishEventWhenAutoApproves() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(autoConfirmPolicy.decide(any())).thenReturn(new AutoConfirmPolicy.AutoDecision(true, false, 0, List.of()));

        invokeAwaitReview(1L, 1L, context());

        verify(executionTracker).updateExecutionStatus(1L, "confirmed");
        verify(eventPublisher).publishEvent(any(IngestAutoConfirmedEvent.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ?>> captor = ArgumentCaptor.forClass(Map.class);
        verify(executionEventLog).append(eq("1"), eq(ExecutionEventTypes.TURN_END), captor.capture());
        assertTrue(captor.getValue().containsKey("autoDecision"));
    }

    @Test
    void shouldMapSignalsFromRealWritingPlanStructure() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(autoConfirmPolicy.decide(any())).thenReturn(new AutoConfirmPolicy.AutoDecision(true, false, 0, List.of()));

        IngestContext context = context();
        context.setWritingPlanJson("{\"affectedPagePlans\":{\"p1\":{},\"p2\":{},\"p3\":{}},\"entityPlans\":{\"e1\":{}},\"chapterPlans\":{}}");
        context.setCompletenessScore(0.8);

        invokeAwaitReview(1L, 1L, context);

        ArgumentCaptor<AutoConfirmPolicy.Signals> captor = ArgumentCaptor.forClass(AutoConfirmPolicy.Signals.class);
        verify(autoConfirmPolicy).decide(captor.capture());
        AutoConfirmPolicy.Signals signals = captor.getValue();
        assertEquals(0.75, signals.updateRatio(), 0.0001);
        assertEquals(0.8, signals.completenessScore(), 0.0001);
        assertEquals(0, signals.conflictCount());
        assertFalse(signals.schemaPrecheckViolation());
        assertFalse(signals.hasSchemaGapHints());
        assertFalse(signals.parseDegraded());
    }

    @Test
    void shouldDeriveUpdateRatioFromMetadataActions() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(autoConfirmPolicy.decide(any())).thenReturn(new AutoConfirmPolicy.AutoDecision(true, false, 0, List.of()));

        IngestContext context = context();
        context.setMetadataJson("{\"affectedPages\":[{\"path\":\"a.md\",\"action\":\"更新\"},{\"path\":\"b.md\",\"action\":\"补充\"}],"
            + "\"entities\":[{\"name\":\"E1\",\"action\":\"新建\"},{\"name\":\"E2\",\"action\":\"补充\"}]}");

        invokeAwaitReview(1L, 1L, context);

        ArgumentCaptor<AutoConfirmPolicy.Signals> captor = ArgumentCaptor.forClass(AutoConfirmPolicy.Signals.class);
        verify(autoConfirmPolicy).decide(captor.capture());
        assertEquals(0.75, captor.getValue().updateRatio(), 0.0001);
    }

    @Test
    void shouldAwaitReviewWhenDecisionManual() throws Exception {
        setAutoConfirmEnabled(true);
        when(scopeMapper.selectById(1L)).thenReturn(scope(false, "auto"));
        when(executionTracker.getExecution(1L)).thenReturn(new ExecutionModel());
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(autoConfirmPolicy.decide(any())).thenReturn(
            new AutoConfirmPolicy.AutoDecision(false, false, 2, List.of("low_completeness:0.3")));

        invokeAwaitReview(1L, 1L, context());

        verify(executionTracker).updateExecutionStatus(1L, "awaiting_review");
        verify(eventPublisher, never()).publishEvent(any());
    }
}
