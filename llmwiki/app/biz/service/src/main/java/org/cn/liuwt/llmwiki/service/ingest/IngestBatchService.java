package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.harness.baseline.ExecutionBaselineService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestStep;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateInfo;
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
        Set.of("pending", "confirmed", "awaiting_confirmation", "awaiting_review");
    private static final Set<String> CANCEL_INTERRUPT_STATUSES = Set.of("running", "paused");
    private static final int INBOX_RETENTION_DAYS = 7;
    private static final int INBOX_OPEN_LIMIT = 100;
    private static final int INBOX_CLOSED_LIMIT = 20;
    private static final Set<String> CANCELLABLE_ITEM_STATUSES =
        Set.of("awaiting_confirmation", "awaiting_review");
    private static final ObjectMapper STEP_OUTPUT_MAPPER = new ObjectMapper();

    @Autowired private ExecutionMapper executionMapper;
    @Autowired private ExecutionStepMapper executionStepMapper;
    @Autowired private IngestBatchMapper batchMapper;
    @Autowired private SourceMapper sourceMapper;
    @Autowired private ScopeMapper scopeMapper;
    @Autowired private ExecutionTracker executionTracker;
    @Autowired private IngestService ingestService;
    @Autowired private IngestDispatcher ingestDispatcher;
    @Autowired private ExecutionNodeRegistry registry;
    @Autowired private MqHealthService mqHealthService;
    @Autowired private SourceService sourceService;
    @Autowired private ExecutionEventLogService executionEventLogService;
    @Autowired private ExecutionBaselineService executionBaselineService;
    @Autowired private WikiPageSourceMapper wikiPageSourceMapper;
    @Autowired private WikiPageMapper wikiPageMapper;
    @Autowired private WikiFileServiceImpl wikiFileService;
    @Autowired private NotificationService notificationService;

    @Value("${llmwiki.ingest.batch.max-size:200}")
    private int maxBatchSize;

    @Value("${llmwiki.ingest.batch.analyze-concurrency:2}")
    private int analyzeConcurrency;

    @Transactional
    public IngestBatchCreateInfo createBatch(Long scopeId, Long userId, List<Long> sourceIds,
                                             String guidance, String mode, Boolean forceReingest) {
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
        List<IngestBatchCreateInfo.SkippedItem> skipped = new ArrayList<>();
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
            if (!Boolean.TRUE.equals(forceReingest) && source.getContentHash() != null) {
                SourceModel dup = sourceService.findDuplicateSource(scopeId, source.getContentHash());
                if (dup != null && !dup.getId().equals(source.getId())) {
                    skipped.add(new IngestBatchCreateInfo.SkippedItem(
                        source.getId(), source.getName(), dup.getId(), "duplicate_of_processed"));
                    warnings.add("内容与已处理来源重复，已跳过: " + source.getName() + "（同 " + dup.getName() + "）");
                    continue;
                }
            }
            if (busySourceIds.contains(sourceId)) {
                warnings.add("已有进行中的处理任务，已跳过: " + source.getName());
                continue;
            }
            accepted.add(sourceId);
        }
        if (accepted.isEmpty()) {
            if (!skipped.isEmpty()) {
                log.info("Batch creation short-circuited: all {} source(s) duplicate processed ones, scopeId={}",
                    skipped.size(), scopeId);
                return new IngestBatchCreateInfo(null, 0, skipped.size(), skipped, warnings);
            }
            throw new BusinessException(ErrorCode.INGEST_BATCH_EMPTY, String.join("; ", warnings));
        }

        IngestBatchDO batch = new IngestBatchDO();
        batch.setScopeId(scopeId);
        batch.setUserId(userId);
        batch.setStatus("active");
        batch.setMode(resolveBatchMode(scopeId, mode));
        batch.setTotalCount(accepted.size());
        batch.setGuidance(guidance);
        batchMapper.insert(batch);

        for (Long sourceId : accepted) {
            ExecutionModel execution = executionTracker.createExecution("ingest", scopeId, sourceId, null);
            ExecutionDO patch = new ExecutionDO();
            patch.setId(execution.getId());
            patch.setBatchId(batch.getId());
            patch.setGuidance(guidance);
            executionMapper.updateById(patch);
        }

        return new IngestBatchCreateInfo(batch.getId(), accepted.size(), skipped.size(), skipped, warnings);
    }

    private String resolveBatchMode(Long scopeId, String requestedMode) {
        if ("auto".equals(requestedMode) || "review".equals(requestedMode)) {
            return requestedMode;
        }
        if (requestedMode != null) {
            return "review";
        }
        ScopeDO scope = scopeMapper.selectById(scopeId);
        if (scope != null && "auto".equals(scope.getIngestMode())) {
            return "auto";
        }
        return "review";
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
        if (confirmed > 0) {
            int revived = batchMapper.update(null, new LambdaUpdateWrapper<IngestBatchDO>()
                .eq(IngestBatchDO::getId, batchId)
                .eq(IngestBatchDO::getStatus, "completed")
                .set(IngestBatchDO::getStatus, "active")
                .set(IngestBatchDO::getCompletedAt, null));
            if (revived == 1) {
                log.info("Revived completed batch {} after confirming {} items", batchId, confirmed);
            }
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
        List<ExecutionStepDO> steps = loadSteps(executionIds);
        Map<Long, String> analyzeOutputs = extractAnalyzeOutputs(steps);
        Map<Long, Boolean> phase1Flags = computePhase1Flags(executionIds, steps);
        Map<Long, int[]> qualityCounts = extractQualityCounts(steps);
        Map<Long, Map<String, Object>> turnEndPayloads =
            executionEventLogService.loadLatestTurnEndPayloads(executionIds);

        long autoCompleted = 0;
        long manualPending = 0;
        long failedCount = 0;
        long cancelledCount = 0;
        long remaining = 0;
        long totalTokensSum = 0;
        for (ExecutionDO item : items) {
            String status = item.getStatus();
            Map<String, Object> payload = turnEndPayloads.get(item.getId());
            if (payload != null && payload.get("totalTokens") instanceof Number number) {
                totalTokensSum += number.longValue();
            }
            switch (status == null ? "" : status) {
                case "completed" -> {
                    if (isAutoApproved(payload)) {
                        autoCompleted++;
                    }
                }
                case "awaiting_confirmation", "awaiting_review" -> manualPending++;
                case "failed", "budget_exhausted" -> failedCount++;
                case "cancelled" -> cancelledCount++;
                case "pending", "running", "confirmed" -> remaining++;
                default -> { }
            }
        }

        int safeSize = size > 0 ? size : 50;
        int safePage = page > 0 ? page : 1;
        int total = items.size();
        int from = (int) Math.min((long) (safePage - 1) * safeSize, total);
        int to = (int) Math.min((long) from + safeSize, total);
        List<IngestBatchItemInfo> pageItems = new ArrayList<>();
        for (ExecutionDO item : items.subList(from, to)) {
            pageItems.add(toItemInfo(item, sourceMap.get(item.getSourceId()), analyzeOutputs.get(item.getId()),
                phase1Flags.get(item.getId()), turnEndPayloads.get(item.getId()), qualityCounts.get(item.getId())));
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
        detail.setMode(batch.getMode());
        detail.setAutoCompleted(autoCompleted);
        detail.setManualPending(manualPending);
        detail.setFailedCount(failedCount);
        detail.setCancelledCount(cancelledCount);
        detail.setTotalTokensSum(totalTokensSum);
        detail.setEtaSeconds(computeEtaSeconds(batch.getScopeId(), remaining));
        return detail;
    }

    public IngestBatchDO getBatch(Long batchId) {
        return batchMapper.selectById(batchId);
    }

    private List<ExecutionStepDO> loadSteps(List<Long> executionIds) {
        if (executionIds.isEmpty()) {
            return List.of();
        }
        return executionStepMapper.selectList(new LambdaQueryWrapper<ExecutionStepDO>()
            .in(ExecutionStepDO::getExecutionId, executionIds)
            .orderByAsc(ExecutionStepDO::getStepOrder));
    }

    private Map<Long, String> extractAnalyzeOutputs(List<ExecutionStepDO> steps) {
        Map<Long, String> outputs = new HashMap<>();
        for (ExecutionStepDO step : steps) {
            if (IngestStep.ANALYZE.name().equals(IngestStep.normalizeStepName(step.getStepName()))
                && step.getOutputData() != null) {
                outputs.put(step.getExecutionId(), step.getOutputData());
            }
        }
        return outputs;
    }

    private Map<Long, Boolean> computePhase1Flags(List<Long> executionIds, List<ExecutionStepDO> steps) {
        Map<Long, Boolean> flags = new HashMap<>();
        Map<Long, List<ExecutionStepModel>> stepsByExecution = steps.stream()
            .collect(Collectors.groupingBy(ExecutionStepDO::getExecutionId,
                Collectors.mapping(this::toStepModel, Collectors.toList())));
        for (Long executionId : executionIds) {
            flags.put(executionId, IngestStep.isPhase1Completed(stepsByExecution.getOrDefault(executionId, List.of())));
        }
        return flags;
    }

    private Map<Long, int[]> extractQualityCounts(List<ExecutionStepDO> steps) {
        Map<Long, int[]> counts = new HashMap<>();
        for (ExecutionStepDO step : steps) {
            if (!IngestStep.COMPLETE.name().equals(IngestStep.normalizeStepName(step.getStepName()))
                || step.getOutputData() == null) {
                continue;
            }
            try {
                JsonNode output = STEP_OUTPUT_MAPPER.readTree(step.getOutputData());
                JsonNode critical = output.get("qualityCritical");
                JsonNode warnings = output.get("qualityWarnings");
                if (critical == null && warnings == null) {
                    continue;
                }
                counts.put(step.getExecutionId(), new int[]{
                    critical != null && critical.isInt() ? critical.intValue() : 0,
                    warnings != null && warnings.isInt() ? warnings.intValue() : 0});
            } catch (Exception e) {
                log.warn("Failed to parse COMPLETE step output for executionId={}: {}",
                    step.getExecutionId(), e.getMessage());
            }
        }
        return counts;
    }

    private boolean isAutoApproved(Map<String, Object> turnEndPayload) {
        if (turnEndPayload == null) {
            return false;
        }
        Object autoDecision = turnEndPayload.get("autoDecision");
        return autoDecision instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("autoApprove"));
    }

    private Long computeEtaSeconds(Long scopeId, long remaining) {
        if (remaining <= 0) {
            return 0L;
        }
        Map<String, Long> profile = executionBaselineService.getProfile(scopeId, "unknown");
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        long sumMs = profile.values().stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        if (sumMs <= 0) {
            return null;
        }
        return remaining * sumMs / Math.max(1, analyzeConcurrency) / 1000;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    public Map<String, Integer> cancelItems(Long batchId, List<Long> executionIds) {
        if (getBatch(batchId) == null) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_NOT_FOUND);
        }
        if (executionIds == null || executionIds.isEmpty()) {
            return Map.of("cancelled", 0, "skipped", 0);
        }
        List<ExecutionDO> items = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .in(ExecutionDO::getId, executionIds)
            .eq(ExecutionDO::getBatchId, batchId));
        int cancelled = 0;
        int skipped = executionIds.size() - items.size();
        for (ExecutionDO item : items) {
            if (!CANCELLABLE_ITEM_STATUSES.contains(item.getStatus())) {
                skipped++;
                continue;
            }
            try {
                if (executionTracker.cancelExecution(item.getId(), "batch_item_rejected")) {
                    cancelled++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Failed to cancel batch item executionId={}: {}", item.getId(), e.getMessage());
                skipped++;
            }
        }
        log.info("Batch {} cancel-items: cancelled={}, skipped={}", batchId, cancelled, skipped);
        return Map.of("cancelled", cancelled, "skipped", skipped);
    }

    public Map<String, Object> deprecateBatchOutputs(Long batchId, Long userId) {
        IngestBatchDO batch = getBatch(batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_NOT_FOUND);
        }
        if (!"completed".equals(batch.getStatus()) && !"cancelled".equals(batch.getStatus())) {
            throw new BusinessException(ErrorCode.INGEST_BATCH_INVALID_STATUS);
        }
        Long scopeId = batch.getScopeId();
        List<ExecutionDO> items = executionMapper.selectList(new LambdaQueryWrapper<ExecutionDO>()
            .eq(ExecutionDO::getBatchId, batchId));
        List<Long> executionIds = items.stream()
            .map(ExecutionDO::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (executionIds.isEmpty()) {
            notifyBatchOutputsDeprecated(userId, batchId, scopeId, 0, 0);
            return Map.<String, Object>of("deprecatedPages", 0, "skippedPages", 0);
        }
        List<WikiPageSourceDO> links = wikiPageSourceMapper.selectList(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .in(WikiPageSourceDO::getExecutionId, executionIds));
        List<Long> pageIds = links.stream()
            .map(WikiPageSourceDO::getPageId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        int deprecated = 0;
        int skipped = 0;
        for (Long pageId : pageIds) {
            WikiPageDO page = wikiPageMapper.selectById(pageId);
            if (page == null || !scopeId.equals(page.getScopeId())
                || "DEPRECATED".equals(page.getLifecycleStatus())
                || "DELETED".equals(page.getLifecycleStatus())) {
                skipped++;
                continue;
            }
            try {
                wikiFileService.deprecatePage(pageId, scopeId, "批次 #" + batchId + " 输出撤回");
                deprecated++;
            } catch (Exception e) {
                log.warn("Failed to deprecate page {} for batch {}: {}", pageId, batchId, e.getMessage());
                skipped++;
            }
        }
        log.info("Batch {} deprecate-outputs: deprecated={}, skipped={}", batchId, deprecated, skipped);
        notifyBatchOutputsDeprecated(userId, batchId, scopeId, deprecated, skipped);
        return Map.<String, Object>of("deprecatedPages", deprecated, "skippedPages", skipped);
    }

    private void notifyBatchOutputsDeprecated(Long userId, Long batchId, Long scopeId, int deprecated, int skipped) {
        try {
            notificationService.createPersonalNotification(userId, "batch_outputs_deprecated",
                "批次输出已撤回",
                "批次 #" + batchId + " 的产出页面已标记废弃 " + deprecated + " 篇，跳过 " + skipped + " 篇。仅标记废弃，原始文件仍保留。",
                scopeId, null, null);
        } catch (Exception e) {
            log.warn("Failed to send deprecate-outputs notification for batch {}: {}", batchId, e.getMessage());
        }
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

    private IngestBatchItemInfo toItemInfo(ExecutionDO execution, SourceDO source, String analyzeOutput,
                                           Boolean phase1Completed, Map<String, Object> turnEndPayload,
                                           int[] qualityCounts) {
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
        info.setErrorSummary(truncate(execution.getErrorMessage(), 200));
        info.setAnalyzeOutput(analyzeOutput);
        info.setPhase1Completed(phase1Completed);
        info.setGuidance(execution.getGuidance());
        info.setStartedAt(execution.getStartedAt());
        info.setCompletedAt(execution.getCompletedAt());
        if (turnEndPayload != null && turnEndPayload.get("autoDecision") instanceof Map<?, ?> autoDecision) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) autoDecision;
            info.setAutoDecision(typed);
        }
        if (qualityCounts != null) {
            info.setQualityCritical(qualityCounts[0]);
            info.setQualityWarnings(qualityCounts[1]);
        }
        return info;
    }
}
