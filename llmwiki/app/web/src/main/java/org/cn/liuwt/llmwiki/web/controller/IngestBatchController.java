package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchConfirmRequest;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateResponse;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchDetailInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchRequest;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchScheduler;
import org.cn.liuwt.llmwiki.service.ingest.IngestBatchService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ingest/batch")
public class IngestBatchController {

    @Autowired
    private IngestBatchService ingestBatchService;

    @Autowired
    private IngestBatchScheduler ingestBatchScheduler;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ScopeService scopeService;

    @PostMapping
    public Result<IngestBatchCreateResponse> createBatch(@RequestBody IngestBatchRequest request) {
        Long scopeId = resolveScopeId(request.getScopeId());
        Long userId = jwtTokenProvider.getCurrentUserId();
        IngestBatchCreateResponse response = ingestBatchService.createBatch(
            scopeId, userId, request.getSourceIds(), request.getGuidance());
        ingestBatchScheduler.kick(scopeId);
        return Result.success(response);
    }

    @GetMapping("/inbox")
    public Result<List<IngestBatchInfo>> listInbox(@RequestParam Long scopeId) {
        return Result.success(ingestBatchService.listInbox(resolveScopeId(scopeId)));
    }

    @GetMapping("/{id}")
    public Result<IngestBatchDetailInfo> getBatchDetail(@PathVariable Long id,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "50") int size) {
        assertBatchReadable(ingestBatchService.getBatch(id));
        return Result.success(ingestBatchService.getBatchDetail(id, page, size));
    }

    @PostMapping("/{id}/confirm")
    public Result<Integer> confirmItems(@PathVariable Long id, @RequestBody(required = false) IngestBatchConfirmRequest request) {
        IngestBatchDO batch = ingestBatchService.getBatch(id);
        assertBatchReadable(batch);
        int confirmed = ingestBatchService.confirmItems(id, request != null ? request.getExecutionIds() : null);
        ingestBatchScheduler.kick(batch.getScopeId());
        return Result.success(confirmed);
    }

    @PostMapping("/{id}/pause")
    public Result<Void> pauseBatch(@PathVariable Long id) {
        assertBatchReadable(ingestBatchService.getBatch(id));
        if (!ingestBatchService.pauseBatch(id)) {
            return Result.failed(ErrorCode.INGEST_BATCH_INVALID_STATUS);
        }
        return Result.success();
    }

    @PostMapping("/{id}/resume")
    public Result<Void> resumeBatch(@PathVariable Long id) {
        IngestBatchDO batch = ingestBatchService.getBatch(id);
        assertBatchReadable(batch);
        if (!ingestBatchService.resumeBatch(id)) {
            return Result.failed(ErrorCode.INGEST_BATCH_INVALID_STATUS);
        }
        ingestBatchScheduler.kick(batch.getScopeId());
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancelBatch(@PathVariable Long id) {
        assertBatchReadable(ingestBatchService.getBatch(id));
        if (!ingestBatchService.cancelBatch(id)) {
            return Result.failed(ErrorCode.INGEST_BATCH_INVALID_STATUS);
        }
        return Result.success();
    }

    private void assertBatchReadable(IngestBatchDO batch) {
        if (batch == null) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_NOT_FOUND);
        }
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (userId == null || !scopeService.canView(batch.getScopeId(), userId)) {
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    private Long resolveScopeId(Long requestedScopeId) {
        Long authScopeId = jwtTokenProvider.getCurrentScopeId();
        if (requestedScopeId == null || requestedScopeId.equals(authScopeId)) {
            return authScopeId;
        }
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (userId != null && scopeService.canView(requestedScopeId, userId)) {
            return requestedScopeId;
        }
        throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
    }
}
