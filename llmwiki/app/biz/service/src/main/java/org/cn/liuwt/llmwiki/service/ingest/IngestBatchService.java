package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateResponse;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchDetailInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchInfo;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchItemInfo;
import org.cn.liuwt.llmwiki.service.harness.mq.ControlMessage;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class IngestBatchService {

    private static final Logger log = LoggerFactory.getLogger(IngestBatchService.class);
    private static final Set<String> ACTIVE_EXECUTION_STATUSES =
        Set.of("pending", "running", "awaiting_confirmation", "confirmed", "paused");
    private static final Set<String> CANCEL_CLEANUP_STATUSES =
        Set.of("pending", "confirmed", "awaiting_confirmation", "awaiting_review", "failed");
    private static final Set<String> CANCEL_INTERRUPT_STATUSES = Set.of("running", "paused");
    private static final int INBOX_RETENTION_DAYS = 7;

    @Autowired private ExecutionMapper executionMapper;
    @Autowired private ExecutionStepMapper executionStepMapper;
    @Autowired private IngestBatchMapper batchMapper;
    @Autowired private SourceMapper sourceMapper;
    @Autowired private ExecutionTracker executionTracker;
    @Autowired private IngestService ingestService;
    @Autowired private IngestDispatcher ingestDispatcher;
    @Autowired private ExecutionNodeRegistry registry;
    @Autowired private MqHealthService mqHealthService;

    @Value("${llmwiki.ingest.batch.max-size:50}")
    private int maxBatchSize;

    @Transactional
    public IngestBatchCreateResponse createBatch(Long scopeId, Long userId, List<Long> sourceIds, String guidance) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_EMPTY);
        }
        List<Long> distinct = sourceIds.stream().distinct().toList();
        if (distinct.size() > maxBatchSize) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_TOO_LARGE, String.valueOf(maxBatchSize));
        }
        Map<Long, SourceDO> sourceMap = sourceMapper.selectBatchIds(distinct).stream()
            .collect(Collectors.toMap(SourceDO::getId, s -> s));
        List<ExecutionDO> inFlight = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getType, "ingest")
            .eq(ExecutionDO::getScopeId, scopeId)
            .in(ExecutionDO::getStatus, ACTIVE_EXECUTION_STATUSES)
            .in(ExecutionDO::getSourceId, distinct));
        Set<Long> busySourceIds = inFlight.stream().map(ExecutionDO::getSourceId).collect(Collectors.toSet());

        List<String> warnings = new ArrayList<>();
        List<Long> accepted = new ArrayList<>();
        for (Long sourceId : distinct) {
            SourceDO source = sourceMap.get(sourceId);
            if (source == null || !scopeId.equals(source.getScopeId())) {
                warnings.add("来源不存在或不属于当前知识库: " + sourceId);
                continue;
            }
            if (busySourceIds.contains(sourceId)) {
                warnings.add("已有进行中的处理任务，已跳过: " + source.getName());
                continue;
            }
            accepted.add(sourceId);
        }
        if (accepted.isEmpty()) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_EMPTY);
        }

        IngestBatchDO batch = new IngestBatchDO();
        batch.setScopeId(scopeId);
        batch.setUserId(userId);
        batch.setStatus("active");
        batch.setTotalCount(accepted.size());
        batch.setGuidance(guidance);
        batchMapper.insert(batch);

        List<Long> executionIds = new ArrayList<>();
        for (Long sourceId : accepted) {
            ExecutionModel execution = executionTracker.createExecution("ingest", scopeId, sourceId, null);
            ExecutionDO patch = new ExecutionDO();
            patch.setId(execution.getId());
            patch.setBatchId(batch.getId());
            patch.setGuidance(guidance);
            executionMapper.updateById(patch);
            executionIds.add(execution.getId());
        }

        IngestBatchCreateResponse response = new IngestBatchCreateResponse();
        response.setBatchId(batch.getId());
        response.setExecutionIds(executionIds);
        response.setWarnings(warnings);
        return response;
    }

    public int confirmItems(Long batchId, List<Long> executionIds) {
        if (getBatch(batchId) == null) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_NOT_FOUND);
        }
        List<Long> targets = executionIds;
        if (targets == null || targets.isEmpty()) {
            targets = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
                    .eq(ExecutionDO::getBatchId, batchId)
                    .in(ExecutionDO::getStatus, "awaiting_confirmation", "awaiting_review"))
                .stream()
                .map(ExecutionDO::getId)
                .toList();
        }
        int confirmed = 0;
        for (Long executionId : targets) {
            ExecutionDO patch = new ExecutionDO();
            patch.setStatus("confirmed");
            confirmed += executionMapper.update(patch, new LambdaUpdateWrapper<ExecutionDO>()
                .eq(ExecutionDO::getId, executionId)
                .eq(ExecutionDO::getBatchId, batchId)
                .in(ExecutionDO::getStatus, "awaiting_confirmation", "awaiting_review"));
        }
        return confirmed;
    }

    public boolean pauseBatch(Long batchId) {
        IngestBatchDO patch = new IngestBatchDO();
        patch.setStatus("paused");
        return batchMapper.update(patch, new LambdaUpdateWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getId, batchId)
            .eq(IngestBatchDO::getStatus, "active")) == 1;
    }

    public boolean resumeBatch(Long batchId) {
        IngestBatchDO patch = new IngestBatchDO();
        patch.setStatus("active");
        return batchMapper.update(patch, new LambdaUpdateWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getId, batchId)
            .eq(IngestBatchDO::getStatus, "paused")) == 1;
    }

    public boolean cancelBatch(Long batchId) {
        IngestBatchDO batchPatch = new IngestBatchDO();
        batchPatch.setStatus("cancelled");
        batchPatch.setCompletedAt(LocalDateTime.now());
        int rows = batchMapper.update(batchPatch, new LambdaUpdateWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getId, batchId)
            .in(IngestBatchDO::getStatus, "active", "paused"));
        if (rows != 1) {
            return false;
        }
        IngestBatchDO batch = batchMapper.selectById(batchId);
        Long scopeId = batch != null ? batch.getScopeId() : null;
        List<ExecutionDO> items = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getBatchId, batchId));
        int processed = 0;
        for (ExecutionDO item : items) {
            String status = item.getStatus();
            boolean needsInterrupt = CANCEL_INTERRUPT_STATUSES.contains(status);
            if (!needsInterrupt && !CANCEL_CLEANUP_STATUSES.contains(status)) {
                continue;
            }
            try {
                ingestService.markExecutionCancelled(item.getId());
                if (needsInterrupt) {
                    registry.cancelAndRemoveFuture(item.getId());
                    if (ingestDispatcher.isMqAvailable() && mqHealthService.shouldAttempt()) {
                        try {
                            ingestDispatcher.sendControl(item.getId(), ControlMessage.ACTION_CANCEL, "批次取消");
                        } catch (Exception e) {
                            log.warn("Failed to broadcast cancel control for executionId={}", item.getId(), e);
                        }
                    }
                }
                if (scopeId != null) {
                    ingestService.cleanupCancelledExecution(item.getId(), scopeId);
                }
                processed++;
            } catch (Exception e) {
                log.warn("Failed to cancel batch item executionId={}", item.getId(), e);
            }
        }
        log.info("Batch {} cancelled, {} items processed", batchId, processed);
        return true;
    }

    public List<IngestBatchInfo> listInbox(Long scopeId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(INBOX_RETENTION_DAYS);
        List<IngestBatchDO> batches = batchMapper.selectList(new LambdaQueryWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getScopeId, scopeId)
            .and(w -> w.in(IngestBatchDO::getStatus, "active", "paused")
                .or(o -> o.eq(IngestBatchDO::getStatus, "completed").gt(IngestBatchDO::getCompletedAt, cutoff))
                .or(o -> o.eq(IngestBatchDO::getStatus, "cancelled").gt(IngestBatchDO::getCompletedAt, cutoff)))
            .orderByDesc(IngestBatchDO::getCreatedAt));
        if (batches.isEmpty()) {
            return List.of();
        }
        List<Long> batchIds = batches.stream().map(IngestBatchDO::getId).toList();
        Map<Long, List<ExecutionDO>> itemsByBatch = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
                .in(ExecutionDO::getBatchId, batchIds))
            .stream()
            .collect(Collectors.groupingBy(ExecutionDO::getBatchId));
        List<IngestBatchInfo> result = new ArrayList<>();
        for (IngestBatchDO batch : batches) {
            result.add(toBatchInfo(batch, itemsByBatch.getOrDefault(batch.getId(), List.of())));
        }
        return result;
    }

    public IngestBatchDetailInfo getBatchDetail(Long batchId, int page, int size) {
        IngestBatchDO batch = getBatch(batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_NOT_FOUND);
        }
        List<ExecutionDO> items = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getBatchId, batchId)
            .orderByAsc(ExecutionDO::getCreatedAt)
            .orderByAsc(ExecutionDO::getId));
        List<Long> executionIds = items.stream().map(ExecutionDO::getId).toList();
        List<Long> sourceIds = items.stream().map(ExecutionDO::getSourceId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SourceDO> sourceMap = sourceIds.isEmpty() ? Map.of()
            : sourceMapper.selectBatchIds(sourceIds).stream().collect(Collectors.toMap(SourceDO::getId, s -> s));
        Map<Long, String> analyzeOutputs = loadAnalyzeOutputs(executionIds);

        int safeSize = size > 0 ? size : 20;
        int safePage = page > 0 ? page : 1;
        int total = items.size();
        int from = (int) Math.min((long) (safePage - 1) * safeSize, total);
        int to = (int) Math.min((long) from + safeSize, total);
        List<IngestBatchItemInfo> pageItems = new ArrayList<>();
        for (ExecutionDO item : items.subList(from, to)) {
            pageItems.add(toItemInfo(item, sourceMap.get(item.getSourceId()), analyzeOutputs.get(item.getId())));
        }

        IngestBatchDetailInfo detail = new IngestBatchDetailInfo();
        detail.setBatchId(batch.getId());
        detail.setStatus(batch.getStatus());
        detail.setTotalCount(batch.getTotalCount());
        detail.setGuidance(batch.getGuidance());
        detail.setCreatedAt(batch.getCreatedAt());
        detail.setCompletedAt(batch.getCompletedAt());
        detail.setItems(pageItems);
        detail.setTotal(total);
        detail.setPage(safePage);
        detail.setSize(safeSize);
        return detail;
    }

    public IngestBatchDO getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }

    private Map<Long, String> loadAnalyzeOutputs(List<Long> executionIds) {
        Map<Long, String> outputs = new HashMap<>();
        if (executionIds.isEmpty()) {
            return outputs;
        }
        List<ExecutionStepDO> steps = executionStepMapper.selectList(new LambdaQueryWrapper<ExecutionStepDO>()
            .in(ExecutionStepDO::getExecutionId, executionIds)
            .orderByAsc(ExecutionStepDO::getStepOrder));
        for (ExecutionStepDO step : steps) {
            if (IngestStep.ANALYZE.name().equals(IngestStep.normalizeStepName(step.getStepName()))
                && step.getOutputData() != null) {
                outputs.put(step.getExecutionId(), step.getOutputData());
            }
        }
        return outputs;
    }

    private IngestBatchInfo toBatchInfo(IngestBatchDO batch, List<ExecutionDO> items) {
        IngestBatchInfo info = new IngestBatchInfo();
        info.setBatchId(batch.getId());
        info.setStatus(batch.getStatus());
        info.setTotalCount(batch.getTotalCount());
        info.setGuidance(batch.getGuidance());
        info.setCreatedAt(batch.getCreatedAt());
        info.setCompletedAt(batch.getCompletedAt());
        for (ExecutionDO item : items) {
            String status = item.getStatus() == null ? "" : item.getStatus();
            switch (status) {
                case "awaiting_confirmation", "awaiting_review" -> info.setAwaitingCount(info.getAwaitingCount() + 1);
                case "pending", "paused" -> info.setPendingCount(info.getPendingCount() + 1);
                case "running" -> info.setRunningCount(info.getRunningCount() + 1);
                case "confirmed" -> info.setConfirmedCount(info.getConfirmedCount() + 1);
                case "completed" -> info.setCompletedCount(info.getCompletedCount() + 1);
                case "failed", "budget_exhausted" -> info.setFailedCount(info.getFailedCount() + 1);
                case "cancelled" -> info.setCancelledCount(info.getCancelledCount() + 1);
                default -> { }
            }
        }
        return info;
    }

    private IngestBatchItemInfo toItemInfo(ExecutionDO execution, SourceDO source, String analyzeOutput) {
        IngestBatchItemInfo info = new IngestBatchItemInfo();
        info.setExecutionId(execution.getId());
        info.setSourceId(execution.getSourceId());
        if (source != null) {
            info.setSourceName(source.getName());
            info.setSourceFormat(source.getFormat());
        }
        info.setStatus(execution.getStatus());
        info.setTotalTokens(execution.getTotalTokens());
        info.setErrorMessage(execution.getErrorMessage());
        info.setAnalyzeOutput(analyzeOutput);
        info.setGuidance(execution.getGuidance());
        info.setStartedAt(execution.getStartedAt());
        info.setCompletedAt(execution.getCompletedAt());
        return info;
    }
}
