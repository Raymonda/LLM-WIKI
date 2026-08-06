package org.cn.liuwt.llmwiki.service.lint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LintOrphanService;
import org.cn.liuwt.llmwiki.domain.service.harness.StaleRefreshService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.LintScheduleTaskMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LintService {

    private static final Logger log = LoggerFactory.getLogger(LintService.class);

    @Value("${llmwiki.lint.skip-window-hours:1}")
    private int skipWindowHours;

    @Value("${llmwiki.lint.scheduled-concurrency:3}")
    private int scheduledConcurrency;

    @Value("${llmwiki.lint.max-scopes-per-run:50}")
    private int maxScopesPerRun;

    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private ScopeMapper scopeMapper;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private LintOrphanService lintOrphanService;

    @Autowired
    private StaleRefreshService staleRefreshService;

    @Autowired
    private ConflictReviewService conflictReviewService;

    @Autowired(required = false)
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    private boolean isMqAvailable() {
        return rocketMQTemplate != null && mqEnabled;
    }

    @Autowired
    private ExecutionNodeRegistry registry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutorService lintSchedulerPool;
    private Semaphore scopeConcurrencySemaphore;

    @PostConstruct
    public void init() {
        lintSchedulerPool = Executors.newFixedThreadPool(scheduledConcurrency, r -> {
            Thread t = new Thread(r, "lint-scheduler-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        scopeConcurrencySemaphore = new Semaphore(scheduledConcurrency);
        log.info("LintService initialized: skipWindowHours={}, scheduledConcurrency={}, maxScopesPerRun={}",
            skipWindowHours, scheduledConcurrency, maxScopesPerRun);
    }

    @PreDestroy
    public void destroy() {
        if (lintSchedulerPool != null) {
            lintSchedulerPool.shutdownNow();
        }
    }

    public ExecutionModel runLint(Long scopeId, boolean fullScan) {
        if (isLintRunning(scopeId)) {
            throw new RuntimeException("该知识库已有 Lint 正在执行，请等待完成后再触发");
        }
        return harnessEngine.executeLint(scopeId, fullScan);
    }

    public ExecutionDO getActiveLintExecution(Long scopeId) {
        return executionHistoryService.findActiveExecution(scopeId, "lint");
    }

    public ExecutionModel createLintExecution(Long scopeId) {
        if (isLintRunning(scopeId)) {
            throw new RuntimeException("该知识库已有 Lint 正在执行，请等待完成后再触发");
        }
        int supersededCount = lintFindingService.autoArchiveSupersededFindings(scopeId, null, null);
        if (supersededCount > 0) {
            log.info("Pre-archived {} superseded findings before new Lint for scopeId={}", supersededCount, scopeId);
        }
        ExecutionModel execution = executionTracker.createExecution("lint", scopeId, null, null);
        executionTracker.updateExecutionStatus(execution.getId(), "running");
        return execution;
    }

    private boolean isLintRunning(Long scopeId) {
        return executionHistoryService.isTypeExecutionRunning(scopeId, "lint");
    }

    private boolean shouldSkipScheduledLint(Long scopeId) {
        Duration skipWindow = Duration.ofHours(skipWindowHours);
        ExecutionDO lastCompleted = executionHistoryService.findMostRecentCompleted(scopeId, "lint");
        if (lastCompleted == null || lastCompleted.getCompletedAt() == null) {
            return false;
        }
        if (Duration.between(lastCompleted.getCompletedAt(), LocalDateTime.now()).compareTo(skipWindow) > 0) {
            return false;
        }
        long newSources = sourceMapper.selectCount(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getScopeId, scopeId)
                .gt(SourceDO::getCreatedAt, lastCompleted.getCompletedAt())
        );
        return newSources == 0;
    }

    private List<ScopeDO> prioritizeScopes(List<ScopeDO> scopes) {
        LocalDateTime now = LocalDateTime.now();
        return scopes.stream()
            .sorted(Comparator.comparingLong((ScopeDO s) -> {
                ExecutionDO last = executionHistoryService.findMostRecentCompleted(s.getId(), "lint");
                if (last == null || last.getCompletedAt() == null) {
                    return Long.MAX_VALUE;
                }
                return Duration.between(last.getCompletedAt(), now).toHours();
            }).reversed()
            .thenComparingLong((ScopeDO s) -> {
                long newSources = sourceMapper.selectCount(
                    new LambdaQueryWrapper<SourceDO>()
                        .eq(SourceDO::getScopeId, s.getId())
                );
                return -newSources;
            }))
            .limit(maxScopesPerRun)
            .toList();
    }

    @Scheduled(cron = "${llmwiki.lint.personal-cron:0 0 3 ? * MON}")
    public void scheduledLintPersonalScopes() {
        List<ScopeDO> personalScopes = scopeMapper.selectList(
            new LambdaQueryWrapper<ScopeDO>().eq(ScopeDO::getType, "personal")
        );
        log.info("Scheduled lint (personal) starting, totalScopeCount={}", personalScopes.size());
        dispatchScheduledLint(personalScopes, "personal");
    }

    @Scheduled(cron = "${llmwiki.lint.team-cron:0 0 4 * * *}")
    public void scheduledLintTeamScopes() {
        List<ScopeDO> teamScopes = scopeMapper.selectList(
            new LambdaQueryWrapper<ScopeDO>().eq(ScopeDO::getType, "team")
        );
        log.info("Scheduled lint (team) starting, totalScopeCount={}", teamScopes.size());
        dispatchScheduledLint(teamScopes, "team");
    }

    private void dispatchScheduledLint(List<ScopeDO> scopes, String scopeType) {
        List<ScopeDO> eligible = scopes.stream()
            .filter(s -> !isLintRunning(s.getId()) && !shouldSkipScheduledLint(s.getId()))
            .toList();
        if (eligible.isEmpty()) {
            log.info("Scheduled lint ({}) no eligible scopes, skipping", scopeType);
            return;
        }

        List<ScopeDO> prioritized = prioritizeScopes(new java.util.ArrayList<>(eligible));

        if (isMqAvailable()) {
            dispatchViaRocketMQ(prioritized, scopeType);
        } else {
            runLintBatchLocal(prioritized, scopeType);
        }
    }

    private void dispatchViaRocketMQ(List<ScopeDO> scopes, String scopeType) {
        int sent = 0;
        for (ScopeDO scope : scopes) {
            try {
                LintScheduleTaskMessage msg = new LintScheduleTaskMessage();
                msg.setScopeId(scope.getId());
                msg.setScopeType(scopeType);
                msg.setFullScan(false);
                msg.setTriggeredByNodeId(registry.getNodeId());
                msg.setSubmittedAt(System.currentTimeMillis());
                rocketMQTemplate.convertAndSend(LintScheduleTaskMessage.TOPIC, msg);
                sent++;
            } catch (Exception e) {
                log.warn("Failed to send lint schedule message for scopeId={}: {}", scope.getId(), e.getMessage());
            }
        }
        log.info("Scheduled lint ({}) dispatched {} scope tasks to RocketMQ (Clustering)", scopeType, sent);
    }

    private void runLintBatchLocal(List<ScopeDO> scopes, String scopeType) {
        log.info("Scheduled lint ({}) eligible={}, concurrency={}",
            scopeType, scopes.size(), scheduledConcurrency);

        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        for (ScopeDO scope : scopes) {
            lintSchedulerPool.submit(() -> {
                try {
                    if (!scopeConcurrencySemaphore.tryAcquire(5_000)) {
                        log.warn("Scheduled lint scope concurrency timeout: scopeId={}", scope.getId());
                        skipped.incrementAndGet();
                        return;
                    }
                    try {
                        if (isLintRunning(scope.getId())) {
                            log.info("Scheduled lint skipped: scopeId={}, lint already running", scope.getId());
                            skipped.incrementAndGet();
                            return;
                        }
                        ExecutionModel execution = harnessEngine.executeLint(scope.getId(), false);
                        completed.incrementAndGet();
                        log.info("Scheduled lint completed: scopeId={}, executionId={}, status={}",
                            scope.getId(), execution.getId(), execution.getStatus());
                    } finally {
                        scopeConcurrencySemaphore.release();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.warn("Scheduled lint failed for scopeId={}: {}", scope.getId(), e.getMessage());
                }
            });
        }

        log.info("Scheduled lint ({}) batch dispatched: scopes={}, completed={}, failed={}, skipped={}",
            scopeType, scopes.size(), completed.get(), failed.get(), skipped.get());
    }

    public Map<String, Object> triggerStaleRepair(Long scopeId, Long findingId) {
        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        if (!"stale".equals(finding.getFindingType()) || !"open".equals(finding.getStatus())) {
            throw new RuntimeException("Only stale/open findings can trigger repair: id=" + findingId + " type=" + finding.getFindingType() + " status=" + finding.getStatus());
        }

        List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, finding.getAssetId());
        if (sourceIds.isEmpty()) {
            throw new RuntimeException("No source found for stale page: pageId=" + finding.getAssetId() + ". The page may be an orphan without source tracking.");
        }

        Long sourceId = sourceIds.get(0);
        lintFindingService.markAsRepairing(findingId, null);

        try {
            staleRefreshService.refreshPage(scopeId, finding.getAssetId(), sourceId);
        } catch (Exception e) {
            lintFindingService.markRepairFailed(findingId);
            throw new RuntimeException("Stale refresh failed: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", findingId);
        result.put("sourceId", sourceId);
        result.put("status", "refreshed");
        log.info("Stale lightweight refresh completed: scopeId={}, findingId={}, sourceId={}", scopeId, findingId, sourceId);
        return result;
    }

    public Map<String, Object> batchRepairStale(Long scopeId) {
        List<LintFindingDO> staleFindings = lintFindingService.listFindings(scopeId, "stale", "open", null);

        int plansGenerated = 0;
        int skipped = 0;
        java.util.Set<Long> processedPageIds = new java.util.HashSet<>();

        for (LintFindingDO f : staleFindings) {
            if (!"auto_refresh".equals(f.getHandlingMethod())) {
                skipped++;
                continue;
            }

            if (processedPageIds.contains(f.getAssetId())) {
                plansGenerated++;
                continue;
            }

            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, f.getAssetId());
            if (sourceIds.isEmpty()) {
                lintFindingService.autoResolve(f.getId(), "manual_edit");
                log.info("Stale finding id={} has no source → auto_resolved as manual_edit (orphan page)", f.getId());
                continue;
            }

            lintFindingService.generatePlanForApproval(f.getId(), "auto_refresh",
                "AI 将基于最新源文件刷新该页面内容，请审批后执行");
            processedPageIds.add(f.getAssetId());
            plansGenerated++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalStaleFindings", staleFindings.size());
        result.put("plansGenerated", plansGenerated);
        result.put("skipped", skipped);
        log.info("Batch stale plan generation completed: scopeId={}, result={}", scopeId, result);
        return result;
    }

    public Map<String, Object> retryOrphanFix(Long scopeId, Long findingId) {
        return lintOrphanService.retryOrphanFix(scopeId, findingId);
    }

    public Map<String, Object> enrichThinPage(Long scopeId, Long findingId, String userSupplement) {
        return lintOrphanService.enrichPage(scopeId, findingId, userSupplement);
    }

    public Map<String, Object> approveFinding(Long scopeId, Long findingId) {
        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        if (!"awaiting_approval".equals(finding.getStatus())) {
            throw new RuntimeException("Only awaiting_approval findings can be approved: id=" + findingId + " status=" + finding.getStatus());
        }

        if ("conflict".equals(finding.getFindingType())) {
            return approveConflictFinding(scopeId, findingId, finding);
        }

        if ("stale".equals(finding.getFindingType()) && "auto_refresh".equals(finding.getHandlingMethod())) {
            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, finding.getAssetId());
            if (sourceIds.isEmpty()) {
                lintFindingService.updateStatus(findingId, "open");
                throw new RuntimeException("No source found for stale page: pageId=" + finding.getAssetId() + ". Cannot trigger repair for orphan page.");
            }

            Long sourceId = sourceIds.get(0);
            lintFindingService.markAsRepairing(findingId, null);

            try {
                staleRefreshService.refreshPage(scopeId, finding.getAssetId(), sourceId);
            } catch (Exception e) {
                lintFindingService.markRepairFailed(findingId);
                throw new RuntimeException("Stale refresh failed after approval: " + e.getMessage(), e);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("findingId", findingId);
            result.put("sourceId", sourceId);
            result.put("status", "refreshed");
            log.info("Approved stale finding id={}, lightweight refresh completed", findingId);
            return result;
        }

        lintFindingService.updateStatus(findingId, "open");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", findingId);
        result.put("status", "open");
        log.info("Approved non-stale finding id={}, reverted to open for next Lint cycle", findingId);
        return result;
    }

    private Map<String, Object> approveConflictFinding(Long scopeId, Long findingId, LintFindingDO finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", findingId);

        String action = null;
        if (finding.getRulingBriefJson() != null && !finding.getRulingBriefJson().isBlank()) {
            try {
                var brief = objectMapper.readValue(finding.getRulingBriefJson(), Map.class);
                action = brief.get("action") != null ? brief.get("action").toString() : null;
            } catch (Exception e) {
                log.warn("Failed to parse rulingBriefJson for findingId={}", findingId, e);
            }
        }

        ConflictReviewDO review = null;
        if (finding.getAssetId() != null) {
            Long relatedPageId = extractRelatedPageId(finding.getExtra());
            if (relatedPageId != null) {
                review = conflictReviewService.findPendingByPagePair(scopeId, finding.getAssetId(), relatedPageId);
            }
        }

        if (review != null) {
            String reviewAction = action;
            if (reviewAction == null || reviewAction.isBlank()) {
                reviewAction = "coexist";
            }
            if (!isValidReviewAction(reviewAction)) {
                reviewAction = "coexist";
            }
            try {
                ConflictReviewDO executed = conflictReviewService.executeRuling(
                    review.getId(), null, reviewAction, "用户从Lint体检页面批准执行");
                String status = "failed".equals(executed.getStatus()) ? "failed" : "auto_resolved";
                if ("failed".equals(status)) {
                    lintFindingService.markAsFailed(findingId, executed.getExecutionError());
                } else {
                    lintFindingService.autoResolve(findingId, "ruling_brief");
                }
                result.put("status", status);
                result.put("reviewId", review.getId());
                log.info("Approved conflict finding id={} via ConflictReview id={}, action={}", findingId, review.getId(), reviewAction);
            } catch (Exception e) {
                log.error("Failed to execute ConflictReview for finding id={}", findingId, e);
                lintFindingService.markAsFailed(findingId, e.getMessage());
                result.put("status", "failed");
                result.put("error", e.getMessage());
            }
        } else {
            lintFindingService.autoResolve(findingId, "manual_approve");
            result.put("status", "auto_resolved");
            log.info("Approved conflict finding id={} (no ConflictReview found), marked resolved", findingId);
        }

        return result;
    }

    private Long extractRelatedPageId(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return null;
        try {
            var extra = objectMapper.readValue(extraJson, Map.class);
            Object id = extra.get("relatedPageId");
            if (id instanceof Number num) return num.longValue();
        } catch (Exception ignore) {}
        return null;
    }

    private boolean isValidReviewAction(String action) {
        return action != null && java.util.Set.of("merge", "coexist", "choose_a", "choose_b").contains(action);
    }

    public Map<String, Object> executeConflictRuling(Long scopeId, Long findingId, String action) {
        if (!isValidReviewAction(action)) {
            throw new RuntimeException("Invalid conflict ruling action: " + action);
        }

        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", findingId);

        if (finding.getAssetId() != null) {
            Long relatedPageId = extractRelatedPageId(finding.getExtra());
            if (relatedPageId != null) {
                ConflictReviewDO review = conflictReviewService.findPendingByPagePair(scopeId, finding.getAssetId(), relatedPageId);
                if (review == null) {
                    review = conflictReviewService.findPendingByPagePair(scopeId, relatedPageId, finding.getAssetId());
                }
                if (review != null) {
                    try {
                        ConflictReviewDO executed = conflictReviewService.executeRuling(
                            review.getId(), null, action, "用户从Lint体检页面裁决");
                        String status = "failed".equals(executed.getStatus()) ? "failed" : "auto_resolved";
                        if ("failed".equals(status)) {
                            lintFindingService.markAsFailed(findingId, executed.getExecutionError());
                        } else {
                            lintFindingService.autoResolve(findingId, "ruling_brief");
                        }
                        result.put("status", status);
                        result.put("reviewId", review.getId());
                        log.info("Executed conflict ruling: findingId={}, reviewId={}, action={}", findingId, review.getId(), action);
                        return result;
                    } catch (Exception e) {
                        log.error("Failed to execute ConflictReview for finding id={}", findingId, e);
                        lintFindingService.markAsFailed(findingId, e.getMessage());
                        result.put("status", "failed");
                        result.put("error", e.getMessage());
                        return result;
                    }
                }
            }
        }

        lintFindingService.autoResolve(findingId, "manual_approve");
        result.put("status", "auto_resolved");
        log.info("Executed conflict ruling: findingId={} (no ConflictReview found), action={}, marked resolved", findingId, action);
        return result;
    }

    public void rejectFinding(Long findingId) {
        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) return;

        lintFindingService.updateStatus(findingId, "dismissed");

        if ("conflict".equals(finding.getFindingType()) && finding.getAssetId() != null) {
            Long relatedPageId = extractRelatedPageId(finding.getExtra());
            if (relatedPageId != null) {
                try {
                    conflictReviewService.findAndCancelByPagePair(
                        finding.getScopeId(), finding.getAssetId(), relatedPageId);
                } catch (Exception e) {
                    log.warn("Failed to cancel ConflictReview for rejected finding id={}", findingId, e);
                }
            }
        }
    }
}
