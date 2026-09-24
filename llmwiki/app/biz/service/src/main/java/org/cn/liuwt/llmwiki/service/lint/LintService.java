package org.cn.liuwt.llmwiki.service.lint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LintOrphanService;
import org.cn.liuwt.llmwiki.domain.service.harness.StaleRefreshService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.LintScheduleTaskMessage;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
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
    private ConflictDomainService conflictDomainService;

    @Autowired
    private SchemaSection6Parser schemaSection6Parser;

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

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired(required = false)
    private org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    @Autowired
    private MqHealthService mqHealthService;

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
            throw new BusinessException(ErrorCode.LINT_ALREADY_RUNNING);
        }
        return harnessEngine.executeLint(scopeId, fullScan);
    }

    public ExecutionDO getActiveLintExecution(Long scopeId) {
        return executionHistoryService.findActiveExecution(scopeId, "lint");
    }

    public ExecutionModel createLintExecution(Long scopeId) {
        if (isLintRunning(scopeId)) {
            throw new BusinessException(ErrorCode.LINT_ALREADY_RUNNING);
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

        if (isMqAvailable() && mqHealthService.shouldAttempt()) {
            dispatchViaRocketMQ(prioritized, scopeType);
        } else {
            runLintBatchLocal(prioritized, scopeType);
        }
    }

    private void dispatchViaRocketMQ(List<ScopeDO> scopes, String scopeType) {
        int sent = 0;
        List<ScopeDO> failedScopes = new java.util.ArrayList<>();
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
                mqHealthService.markSendSuccess();
            } catch (Exception e) {
                mqHealthService.markSendFailed();
                failedScopes.add(scope);
                log.warn("Failed to send lint schedule message for scopeId={}: {}", scope.getId(), e.getMessage());
            }
        }
        log.info("Scheduled lint ({}) dispatched {} scope tasks to RocketMQ (Clustering)", scopeType, sent);
        if (!failedScopes.isEmpty()) {
            log.warn("Scheduled lint ({}) {} scope tasks failed MQ dispatch, running locally", scopeType, failedScopes.size());
            runLintBatchLocal(failedScopes, scopeType);
        }
    }

    private void runLintBatchLocal(List<ScopeDO> scopes, String scopeType) {
        log.info("Scheduled lint ({}) eligible={}, concurrency={}",
            scopeType, scopes.size(), scheduledConcurrency);

        for (ScopeDO scope : scopes) {
            lintSchedulerPool.submit(() -> {
                try {
                    if (!scopeConcurrencySemaphore.tryAcquire(5_000)) {
                        log.warn("Scheduled lint scope concurrency timeout: scopeId={}", scope.getId());
                        return;
                    }
                    try {
                        if (isLintRunning(scope.getId())) {
                            log.info("Scheduled lint skipped: scopeId={}, lint already running", scope.getId());
                            return;
                        }
                        ExecutionModel execution = harnessEngine.executeLint(scope.getId(), false);
                        log.info("Scheduled lint completed: scopeId={}, executionId={}, status={}",
                            scope.getId(), execution.getId(), execution.getStatus());
                    } finally {
                        scopeConcurrencySemaphore.release();
                    }
                } catch (Exception e) {
                    log.warn("Scheduled lint failed for scopeId={}: {}", scope.getId(), e.getMessage());
                }
            });
        }

        log.info("Scheduled lint ({}) batch dispatched: scopes={}, concurrency={}",
            scopeType, scopes.size(), scheduledConcurrency);
    }

    public Map<String, Object> triggerStaleRepair(Long scopeId, Long findingId) {
        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        if (!"stale".equals(finding.getFindingType()) || !"open".equals(finding.getStatus())) {
            throw new RuntimeException("Only stale/open findings can trigger repair: id=" + findingId + " type=" + finding.getFindingType() + " status=" + finding.getStatus());
        }

        Long pageId = lintFindingService.resolvePageIdForFinding(scopeId, finding);
        List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, pageId);
        if (sourceIds.isEmpty()) {
            throw new RuntimeException("No source found for stale page: pageId=" + pageId + ". The page may be an orphan without source tracking.");
        }

        Long sourceId = sourceIds.get(0);
        lintFindingService.markAsRepairing(findingId, null);

        try {
            staleRefreshService.refreshPage(scopeId, pageId, sourceId);
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

            Long pageId = lintFindingService.resolvePageIdForFinding(scopeId, f);
            if (pageId != null && processedPageIds.contains(pageId)) {
                skipped++;
                continue;
            }

            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(scopeId, pageId);
            if (sourceIds.isEmpty()) {
                lintFindingService.autoResolve(f.getId(), "manual_edit");
                log.info("Stale finding id={} has no source → auto_resolved as manual_edit (orphan page)", f.getId());
                continue;
            }

            lintFindingService.generatePlanForApproval(f.getId(), "auto_refresh",
                "AI 将基于最新源文件刷新该页面内容，请审批后执行");
            if (pageId != null) {
                processedPageIds.add(pageId);
            }
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

        Long effectiveScopeId = finding.getScopeId() != null ? finding.getScopeId() : scopeId;
        if ("conflict".equals(finding.getFindingType())) {
            return approveConflictFinding(effectiveScopeId, findingId, finding);
        }

        if ("stale".equals(finding.getFindingType()) && "auto_refresh".equals(finding.getHandlingMethod())) {
            Long pageId = lintFindingService.resolvePageIdForFinding(effectiveScopeId, finding);
            List<Long> sourceIds = lintFindingService.findSourceIdsForPage(effectiveScopeId, pageId);
            if (sourceIds.isEmpty()) {
                lintFindingService.updateStatus(findingId, "open");
                throw new RuntimeException("No source found for stale page: pageId=" + pageId + ". Cannot trigger repair for orphan page.");
            }

            Long sourceId = sourceIds.get(0);
            lintFindingService.markAsRepairing(findingId, null);

            try {
                staleRefreshService.refreshPage(scopeId, pageId, sourceId);
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
        String action = resolveBriefAction(finding);
        Long reviewId = resolveOrCreateReviewId(scopeId, finding);
        return executeReviewForFinding(findingId, reviewId, action, "用户从Lint体检页面批准执行");
    }

    private String resolveBriefAction(LintFindingDO finding) {
        if (finding.getRulingBriefJson() == null || finding.getRulingBriefJson().isBlank()) {
            return "coexist";
        }
        try {
            Map<String, Object> brief = objectMapper.readValue(finding.getRulingBriefJson(), Map.class);
            Object briefAction = brief.get("action");
            if (briefAction != null && isValidReviewAction(briefAction.toString())) {
                return briefAction.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse ruling brief for finding {}: {}", finding.getId(), e.getMessage());
        }
        return "coexist";
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
        Long reviewId = resolveOrCreateReviewId(scopeId, finding);
        return executeReviewForFinding(findingId, reviewId, action, "用户从Lint体检页面裁决");
    }

    public ConflictReviewDO resolveOrCreateReview(Long scopeId, Long findingId) {
        LintFindingDO finding = lintFindingService.getFinding(findingId);
        if (finding == null) {
            throw new RuntimeException("Finding not found: id=" + findingId);
        }
        return conflictReviewService.getReview(resolveOrCreateReviewId(scopeId, finding));
    }

    private Long resolveOrCreateReviewId(Long scopeId, LintFindingDO finding) {
        Map<String, Object> extra = parseExtraMap(finding.getExtra());
        WikiPageDO fromPage = resolveConflictPage(scopeId,
            asLong(extra.get("fromPageId")),
            firstNonBlank(stringValue(extra.get("fromPagePath")), finding.getPagePath()),
            finding.getAssetId());
        WikiPageDO toPage = resolveConflictPage(scopeId,
            asLong(extra.get("relatedPageId")),
            firstNonBlank(stringValue(extra.get("relatedPagePath")), stringValue(extra.get("pagePathB"))),
            null);
        if (fromPage == null || toPage == null) {
            throw new BusinessException(ErrorCode.CONFLICT_PAGE_NOT_FOUND,
                "无法解析冲突页对: findingId=" + finding.getId());
        }
        repairConflictExtra(finding, extra, fromPage, toPage);
        ConflictReviewDO pending = conflictReviewService.findPendingByPagePair(scopeId, fromPage.getId(), toPage.getId());
        if (pending != null) {
            return pending.getId();
        }
        String conflictType = firstNonBlank(stringValue(extra.get("conflictType")), "fact_conflict");
        return conflictReviewService.createReview(scopeId, null, "LINT", fromPage, toPage,
            conflictType, "manual", "手动裁决", "用户从Lint页直接执行裁决（无 AI 简报）");
    }

    private WikiPageDO resolveConflictPage(Long scopeId, Long pageId, String filePath, Long fallbackId) {
        Long effectiveId = pageId != null ? pageId : fallbackId;
        if (effectiveId != null) {
            WikiPageDO page = wikiPageMapper.selectById(effectiveId);
            if (page != null) {
                return page;
            }
        }
        if (filePath != null && !filePath.isBlank()) {
            WikiPageDO page = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, filePath)
                    .last("LIMIT 1"));
            if (page != null) {
                return page;
            }
            return wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getTitle, filePath)
                    .last("LIMIT 1"));
        }
        return null;
    }

    private void repairConflictExtra(LintFindingDO finding, Map<String, Object> extra,
                                     WikiPageDO fromPage, WikiPageDO toPage) {
        boolean needsRepair = extra.containsKey("pagePathB")
            || asLong(extra.get("fromPageId")) == null
            || asLong(extra.get("relatedPageId")) == null;
        if (!needsRepair) {
            return;
        }
        Map<String, Object> repaired = new LinkedHashMap<>();
        repaired.put("fromPageId", fromPage.getId());
        repaired.put("fromPagePath", fromPage.getFilePath());
        repaired.put("fromPageTitle", fromPage.getTitle());
        repaired.put("relatedPageId", toPage.getId());
        repaired.put("relatedPagePath", toPage.getFilePath());
        repaired.put("relatedPageTitle", toPage.getTitle());
        repaired.put("conflictType", firstNonBlank(stringValue(extra.get("conflictType")), "fact_conflict"));
        repaired.put("claimA", firstNonBlank(stringValue(extra.get("claimA")), ""));
        repaired.put("claimB", firstNonBlank(stringValue(extra.get("claimB")), ""));
        repaired.put("source", firstNonBlank(stringValue(extra.get("source")), "legacy_repair"));
        try {
            lintFindingService.updateExtraAndAsset(finding.getId(),
                objectMapper.writeValueAsString(repaired), fromPage.getId());
        } catch (Exception e) {
            log.warn("Failed to repair conflict extra for finding {}: {}", finding.getId(), e.getMessage());
        }
    }

    private Map<String, Object> executeReviewForFinding(Long findingId, Long reviewId,
                                                        String action, String rulingDetail) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", findingId);
        result.put("reviewId", reviewId);
        try {
            ConflictReviewDO review = conflictReviewService.executeRuling(reviewId, null, action, rulingDetail);
            if ("failed".equals(review.getStatus())) {
                lintFindingService.markAsFailed(findingId, review.getExecutionError());
                result.put("status", "failed");
                result.put("error", review.getExecutionError());
            } else {
                lintFindingService.autoResolve(findingId, "ruling_brief");
                result.put("status", "auto_resolved");
            }
        } catch (Exception e) {
            lintFindingService.markAsFailed(findingId, e.getMessage());
            result.put("status", "failed");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseExtraMap(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) return java.util.Collections.emptyMap();
        try {
            return objectMapper.readValue(extraJson, Map.class);
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : null;
    }

    private String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    public Map<String, Object> generateRulingBrief(Long scopeId, Long findingId) {
        LintRulesConfig rulesConfig = schemaSection6Parser.parse(scopeId);
        ConflictDomainService.RulingResult result =
            conflictDomainService.generateRulingBriefForFinding(scopeId, null, findingId, rulesConfig);
        Map<String, Object> response = new LinkedHashMap<>();
        switch (result.outcome()) {
            case NOT_FOUND -> throw new BusinessException(ErrorCode.LINT_FINDING_NOT_FOUND, findingId);
            case AI_UNAVAILABLE -> throw new BusinessException(ErrorCode.CONFLICT_AI_UNAVAILABLE);
            case BUSY -> throw new BusinessException(ErrorCode.CONFLICT_AI_CONCURRENCY);
            case JSON_EXTRACT_FAILED, GENERATION_FAILED -> throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            case DEFERRED -> {
                response.put("status", "deferred");
                response.put("reason", result.reason());
            }
            case ALREADY_GENERATED -> response.put("status", "already_generated");
            case GENERATED -> response.put("status", "generated");
        }
        return response;
    }

    public List<LintFindingDO> listPageConflicts(Long scopeId, Long pageId) {
        WikiPageDO page = wikiPageMapper.selectById(pageId);
        String filePath = page != null ? page.getFilePath() : null;
        return lintFindingService.listFindings(scopeId, "conflict", null, null).stream()
            .filter(finding -> isConflictPageInvolved(finding, pageId, filePath))
            .toList();
    }

    private boolean isConflictPageInvolved(LintFindingDO finding, Long pageId, String filePath) {
        if (pageId.equals(finding.getAssetId())) {
            return true;
        }
        Map<String, Object> extra = parseExtraMap(finding.getExtra());
        if (pageId.equals(asLong(extra.get("relatedPageId"))) || pageId.equals(asLong(extra.get("fromPageId")))) {
            return true;
        }
        if (filePath != null) {
            return filePath.equals(finding.getPagePath())
                || filePath.equals(stringValue(extra.get("fromPagePath")))
                || filePath.equals(stringValue(extra.get("relatedPagePath")))
                || filePath.equals(stringValue(extra.get("pagePathB")));
        }
        return false;
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
