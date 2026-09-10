package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateResponse;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchRequest;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchScheduler;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchControllerTest {

    @Mock
    private IngestBatchService ingestBatchService;

    @Mock
    private IngestBatchScheduler ingestBatchScheduler;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private ScopeService scopeService;

    @InjectMocks
    private IngestBatchController controller;

    @Test
    void shouldCreateBatchAndKickScheduler() {
        IngestBatchRequest request = new IngestBatchRequest();
        request.setSourceIds(List.of(1L, 2L));
        when(jwtTokenProvider.getCurrentScopeId()).thenReturn(100L);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        IngestBatchCreateResponse response = new IngestBatchCreateResponse();
        response.setBatchId(9L);
        response.setExecutionIds(List.of(11L, 12L));
        response.setWarnings(List.of());
        when(ingestBatchService.createBatch(100L, 7L, List.of(1L, 2L), null)).thenReturn(response);

        Result<IngestBatchCreateResponse> result = controller.createBatch(request);

        assertTrue(result.isSuccess());
        assertEquals(9L, result.getData().getBatchId());
        verify(ingestBatchScheduler).kick(100L);
    }

    @Test
    void shouldConfirmBatchAndKickScheduler() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(100L);
        when(ingestBatchService.getBatch(9L)).thenReturn(batch);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(100L, 7L)).thenReturn(true);
        when(ingestBatchService.confirmItems(9L, null)).thenReturn(2);

        Result<Integer> result = controller.confirmItems(9L, null);

        assertEquals(2, result.getData().intValue());
        verify(ingestBatchScheduler).kick(100L);
    }

    @Test
    void shouldRejectBatchWhenCallerNotMember() {
        IngestBatchDO batch = new IngestBatchDO();
        batch.setId(9L);
        batch.setScopeId(200L);
        when(ingestBatchService.getBatch(9L)).thenReturn(batch);
        when(jwtTokenProvider.getCurrentUserId()).thenReturn(7L);
        when(scopeService.canView(200L, 7L)).thenReturn(false);

        assertThrows(BusinessException.class, () -> controller.getBatchDetail(9L, 1, 20));
    }
}
