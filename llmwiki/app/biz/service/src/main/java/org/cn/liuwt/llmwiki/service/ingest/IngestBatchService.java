package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
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
import java.util.Comparator;
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
    private static final int INBOX_OPEN_LIMIT = 100;
    private static final int INBOX_CLOSED_LIMIT = 20;

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
            if ("DEPRECATED".equals(source.getLifecycleStatus())) {
                warnings.add("来源已废弃，请先恢复: " + source.getName());
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
        List<IngestBatchDO> openBatches = batchMapper.selectList(new LambdaQueryWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getScopeId, scopeId)
            .in(IngestBatchDO::getStatus, "active", "paused")
            .orderByDesc(IngestBatchDO::getCreatedAt)
            .last("LIMIT " + INBOX_OPEN_LIMIT));
        List<IngestBatchDO> closedBatches = batchMapper.selectList(new LambdaQueryWrapper<IngestBatchDO>()
            .eq(IngestBatchDO::getScopeId, scopeId)
            .in(IngestBatchDO::getStatus, "completed", "cancelled")
            .gt(IngestBatchDO::getCompletedAt, cutoff)
            .orderByDesc(IngestBatchDO::getCompletedAt)
            .last("LIMIT " + INBOX_CLOSED_LIMIT));
        if (openBatches.isEmpty() && closedBatches.isEmpty()) {
            return List.of();
        }
        List<IngestBatchDO> batches = new ArrayList<>(openBatches);
        batches.addAll(closedBatches);
        batches.sort(Comparator.comparing(IngestBatchDO::getCreatedAt,
            Comparator.nullsLast(Comparator.<LocalDateTime>reverseOrder())));
        List<Long> batchIds = batches.stream().map(IngestBatchDO::getId).toList();
        Map<Long, Map<String, Integer>> countsByBatch = loadStatusCounts(batchIds);
        List<IngestBatchInfo> result = new ArrayList<>();
        for (IngestBatchDO batch : batches) {
            result.add(toBatchInfo(batch, countsByBatch.getOrDefault(batch.getId(), Map.of())));
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
        Map<Long, Boolean> phase1Flags = loadPhase1Completed(executionIds);

        int safeSize = size > 0 ? size : 50;
        int safePage = page > 0 ? page : 1;
        int total = items.size();
        int from = (int) Math.min((long) (safePage - 1) * safeSize, total);
        int to = (int) Math.min((long) from + safeSize, total);
        List<IngestBatchItemInfo> pageItems = new ArrayList<>();
        for (ExecutionDO item : items.subList(from, to)) {
            pageItems.add(toItemInfo(item, sourceMap.get(item.getSourceId()), analyzeOutputs.get(item.getId()),
                phase1Flags.get(item.getId())));
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

    private Map<Long, Boolean> loadPhase1Completed(List<Long> executionIds) {
        Map<Long, Boolean> flags = new HashMap<>();
        if (executionIds.isEmpty()) {
            return flags;
        }
        List<ExecutionStepDO> steps = executionStepMapper.selectList(new LambdaQueryWrapper<ExecutionStepDO>()
            .in(ExecutionStepDO::getExecutionId, executionIds)
            .orderByAsc(ExecutionStepDO::getStepOrder));
        Map<Long, List<ExecutionStepModel>> stepsByExecution = steps.stream()
            .collect(Collectors.groupingBy(ExecutionStepDO::getExecutionId,
                Collectors.mapping(this::toStepModel, Collectors.toList())));
        for (Long executionId : executionIds) {
            flags.put(executionId, IngestStep.isPhase1Completed(stepsByExecution.getOrDefault(executionId, List.of())));
        }
        return flags;
    }

    private ExecutionStepModel toStepModel(ExecutionStepDO step) {
        ExecutionStepModel model = new ExecutionStepModel();
        model.setStepName(step.getStepName());
        model.setStatus(step.getStatus());
        return model;
    }

    private IngestBatchInfo toBatchInfo(IngestBatchDO batch, Map<String, Integer> statusCounts) {
        IngestBatchInfo info = new IngestBatchInfo();
        info.setBatchId(batch.getId());
        info.setStatus(batch.getStatus());
        info.setTotalCount(batch.getTotalCount());
        info.setGuidance(batch.getGuidance());
        info.setCreatedAt(batch.getCreatedAt());
        info.setCompletedAt(batch.getCompletedAt());
        for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
            String status = entry.getKey() == null ? "" : entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            switch (status) {
                case "awaiting_confirmation", "awaiting_review" -> info.setAwaitingCount(info.getAwaitingCount() + count);
                case "pending", "paused" -> info.setPendingCount(info.getPendingCount() + count);
                case "running" -> info.setRunningCount(info.getRunningCount() + count);
                case "confirmed" -> info.setConfirmedCount(info.getConfirmedCount() + count);
                case "completed" -> info.setCompletedCount(info.getCompletedCount() + count);
                case "failed", "budget_exhausted" -> info.setFailedCount(info.getFailedCount() + count);
                case "cancelled" -> info.setCancelledCount(info.getCancelledCount() + count);
                default -> { }
            }
        }
        return info;
    }

    private Map<Long, Map<String, Integer>> loadStatusCounts(List<Long> batchIds) {
        List<Map<String, Object>> rows = executionMapper.selectMaps(new QueryWrapper<ExecutionDO>()
            .in("batch_id", batchIds)
            .select("batch_id", "status", "COUNT(*) AS cnt")
            .groupBy("batch_id", "status"));
        Map<Long, Map<String, Integer>> countsByBatch = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object batchIdValue = row.get("batch_id");
            Object countValue = row.get("cnt");
            if (batchIdValue == null || countValue == null) {
                continue;
            }
            Long batchId = ((Number) batchIdValue).longValue();
            String status = row.get("status") == null ? "" : String.valueOf(row.get("status"));
            countsByBatch.computeIfAbsent(batchId, k -> new HashMap<>())
                .merge(status, ((Number) countValue).intValue(), Integer::sum);
        }
        return countsByBatch;
    }

    private IngestBatchItemInfo toItemInfo(ExecutionDO execution, SourceDO source, String analyzeOutput, Boolean phase1Completed) {
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
        info.setPhase1Completed(phase1Completed);
        info.setGuidance(execution.getGuidance());
        info.setStartedAt(execution.getStartedAt());
        info.setCompletedAt(execution.getCompletedAt());
        return info;
    }
}
