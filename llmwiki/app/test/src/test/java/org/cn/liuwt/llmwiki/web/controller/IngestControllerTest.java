package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestRequest;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchScheduler;
import org.cn.liuwt.llmwiki.service.ingest.IngestOrchestrationService;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestControllerTest {

    @Mock
    private SourceService sourceService;

    @Mock
    private SourceMapper sourceMapper;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private IngestOrchestrationService ingestOrchestrationService;

    @Mock
    private ScopeService scopeService;

    @Mock
    private IngestService ingestService;

    @Mock
    private IngestBatchScheduler ingestBatchScheduler;

    @InjectMocks
    private IngestController controller;

    @Test
    void shouldFallBackToAuthScopeWhenRequestOmitsScopeId() {
        IngestRequest request = new IngestRequest();
        request.setSourceId(42L); // 不设 scopeId —— API key 客户端的调用形态
        SourceModel source = new SourceModel();
        source.setId(42L);
        source.setContentHash("abc");
        when(jwtTokenProvider.getCurrentScopeId()).thenReturn(100L);
        when(sourceService.getSource(42L, 100L)).thenReturn(source);
        ExecutionModel execution = new ExecutionModel();
        execution.setId(7L);
        execution.setScopeId(100L);
        execution.setStatus("pending");
        execution.setSourceId(42L);
        when(ingestOrchestrationService.startIngest(100L, 42L, null)).thenReturn(execution);

        Result<ExecutionInfo> result = controller.startIngest(request);

        assertTrue(result.isSuccess());
        verify(ingestOrchestrationService).startIngest(100L, 42L, null);
    }

    @Test
    void shouldHonorExplicitScopeIdWhenMatchesAuthScope() {
        IngestRequest request = new IngestRequest();
        request.setScopeId(200L);
        request.setSourceId(42L);
        SourceModel source = new SourceModel();
        source.setId(42L);
        when(jwtTokenProvider.getCurrentScopeId()).thenReturn(200L);
        when(sourceService.getSource(42L, 200L)).thenReturn(source);
        ExecutionModel execution = new ExecutionModel();
        execution.setId(8L);
        execution.setScopeId(200L);
        execution.setStatus("pending");
        execution.setSourceId(42L);
        when(ingestOrchestrationService.startIngest(200L, 42L, null)).thenReturn(execution);

        Result<ExecutionInfo> result = controller.startIngest(request);

        assertTrue(result.isSuccess());
        verify(ingestOrchestrationService).startIngest(200L, 42L, null);
    }

    @Test
    void shouldHonorExplicitScopeIdWhenCallerIsMember() {
        IngestRequest request = new IngestRequest();
        request.setScopeId(200L);
        request.setSourceId(42L);
        SourceModel source = new SourceModel();
        source.setId(42L);
        when(jwtTokenProvider.getCurrentScopeId()).thenReturn(100L);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(200L, 7L)).thenReturn(true);
        when(sourceService.getSource(42L, 200L)).thenReturn(source);
        ExecutionModel execution = new ExecutionModel();
        execution.setId(9L);
        execution.setScopeId(200L);
        execution.setStatus("pending");
        execution.setSourceId(42L);
        when(ingestOrchestrationService.startIngest(200L, 42L, null)).thenReturn(execution);

        Result<ExecutionInfo> result = controller.startIngest(request);

        assertTrue(result.isSuccess());
        verify(ingestOrchestrationService).startIngest(200L, 42L, null);
    }

    @Test
    void shouldRejectForgedScopeIdWhenCallerNotMember() {
        IngestRequest request = new IngestRequest();
        request.setScopeId(999L);
        request.setSourceId(42L);
        when(jwtTokenProvider.getCurrentScopeId()).thenReturn(100L);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(999L, 7L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.startIngest(request));
        assertThrows(BusinessException.class, () -> controller.startAnalysis(request));
    }

    @Test
    void shouldQueueExecuteAndKickSchedulerWhenQueueCasSucceeds() {
        ExecutionModel execution = new ExecutionModel();
        execution.setId(7L);
        execution.setScopeId(100L);
        execution.setStatus("awaiting_confirmation");
        when(ingestService.getProgress(7L)).thenReturn(execution);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(100L, 7L)).thenReturn(true);
        when(ingestService.queueExecute(7L, null)).thenReturn(true);

        Result<Void> result = controller.executeIngest(7L, null);

        assertTrue(result.isSuccess());
        verify(ingestBatchScheduler).kick(100L);
    }

    @Test
    void shouldFailExecuteWhenQueueCasLoses() {
        ExecutionModel execution = new ExecutionModel();
        execution.setId(7L);
        execution.setScopeId(100L);
        execution.setStatus("awaiting_confirmation");
        when(ingestService.getProgress(7L)).thenReturn(execution);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(100L, 7L)).thenReturn(true);
        when(ingestService.queueExecute(7L, null)).thenReturn(false);

        Result<Void> result = controller.executeIngest(7L, null);

        assertFalse(result.isSuccess());
        verify(ingestBatchScheduler, never()).kick(any());
    }
}
