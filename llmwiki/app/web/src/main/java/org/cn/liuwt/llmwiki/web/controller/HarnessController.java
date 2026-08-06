package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.HarnessEngine;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.ApprovalService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.BootstrapAdvisor;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageMonitor;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageDailyService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.SchemaBootstrapService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaPatchService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SystemConfigService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictReviewService;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ConflictReviewDO;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.ParadigmCatalog;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo;
import org.cn.liuwt.llmwiki.facade.model.ExecutionInfo.ExecutionStepInfo;
import org.cn.liuwt.llmwiki.facade.model.PageResult;
import org.cn.liuwt.llmwiki.facade.model.TokenUsageInfo;
import org.cn.liuwt.llmwiki.facade.model.TokenUsageTrendItem;
import org.cn.liuwt.llmwiki.facade.model.SchemaInfo;
import org.cn.liuwt.llmwiki.facade.model.SchemaGovernanceHealth;
import org.cn.liuwt.llmwiki.facade.model.SchemaMigrationReport;
import org.cn.liuwt.llmwiki.facade.model.SchemaVersionInfo;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigVersionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeBudgetDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SystemConfigDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeBudgetMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/harness")
public class HarnessController {

    private static final Logger log = LoggerFactory.getLogger(HarnessController.class);
    @Autowired
    private HarnessEngine harnessEngine;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ScopeBudgetMapper scopeBudgetMapper;

    @Autowired
    private TokenUsageMonitor tokenUsageMonitor;

    @Autowired
    private TokenUsageDailyService tokenUsageDailyService;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private SchemaBootstrapService schemaBootstrapService;

    @Autowired
    private BootstrapAdvisor bootstrapAdvisor;

    @Autowired
    private ParadigmCatalog paradigmCatalog;

    @Autowired
    private SchemaPatchService schemaPatchService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private ConflictReviewService conflictReviewService;

    @Autowired(required = false)
    private LlmClient llmClient;

    @Autowired
    private ExecutionNodeRegistry executionNodeRegistry;

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down harness sseExecutor");
        sseExecutor.shutdown();
        try {
            if (!sseExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                sseExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sseExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping("/executions/paged")
    public Result<PageResult<ExecutionInfo>> listExecutionsPaged(
            @RequestParam Long scopeId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        IPage<ExecutionModel> paged = executionTracker.listExecutionsPaged(scopeId, type, page, size);
        Map<Long, String> sourceNameMap = batchLoadSourceNames(paged.getRecords());
        PageResult<ExecutionInfo> pr = new PageResult<>();
        pr.setItems(paged.getRecords().stream()
            .map(m -> toExecutionInfo(m, sourceNameMap))
            .collect(Collectors.toList()));
        pr.setTotal(paged.getTotal());
        pr.setPage(paged.getCurrent());
        pr.setSize(paged.getSize());
        pr.setTotalPages(paged.getPages());
        return Result.success(pr);
    }

    @GetMapping("/executions/{id}")
    public Result<ExecutionInfo> getExecution(@PathVariable Long id) {
        ExecutionModel execution = harnessEngine.getExecution(id);
        if (execution == null) {
            return Result.success(new ExecutionInfo());
        }
        return Result.success(toExecutionInfoSingle(execution));
    }

    @PostMapping("/executions/{stepId}/approve")
    public Result<Void> approveStep(@PathVariable Long stepId) {
        approvalService.approveStep(stepId, null);
        return Result.success();
    }

    @PostMapping("/executions/{stepId}/reject")
    public Result<Void> rejectStep(@PathVariable Long stepId) {
        approvalService.rejectStep(stepId, null);
        return Result.success();
    }

    @PostMapping("/executions/{id}/cancel")
    public Result<Void> cancelExecution(@PathVariable Long id) {
        executionTracker.updateExecutionStatus(id, "cancelled");
        return Result.success();
    }

    @GetMapping(value = "/executions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamExecution(@PathVariable Long id) {
        SseEmitter emitter = executionNodeRegistry.createEmitter(id);

        ExecutionModel current = harnessEngine.getExecution(id);
        if (current != null) {
            try {
                emitter.send(SseEmitter.event().name("init").data(toExecutionInfoSingle(current)));

                if (current.getSteps() != null && !current.getSteps().isEmpty()) {
                    for (ExecutionStepModel step : current.getSteps()) {
                        Map<String, Object> stepData = Map.of(
                            "stepId", step.getId(),
                            "stepName", step.getStepName(),
                            "status", step.getStatus(),
                            "outputData", step.getOutputData() != null ? step.getOutputData() : ""
                        );
                        emitter.send(SseEmitter.event().name("step").data(stepData));
                    }
                }

                String status = current.getStatus();
                if ("completed".equals(status) || "failed".equals(status)
                        || "cancelled".equals(status) || "budget_exhausted".equals(status)) {
                    emitter.send(SseEmitter.event().name("done").data(toExecutionInfoSingle(current)));
                    emitter.complete();
                    executionNodeRegistry.removeEmitter(id);
                    return emitter;
                } else if ("paused".equals(status)) {
                    emitter.send(SseEmitter.event().name("pause").data(toExecutionInfoSingle(current)));
                }
            } catch (Exception e) {
                log.error("Failed to send init event for harness SSE", e);
            }
        }

        startHarnessPeriodicSync(id, emitter);
        return emitter;
    }

    private void startHarnessPeriodicSync(Long id, SseEmitter emitter) {
        final boolean[] closed = {false};
        emitter.onCompletion(() -> { closed[0] = true; executionNodeRegistry.cancelSyncTimer(id); });
        emitter.onTimeout(() -> { closed[0] = true; executionNodeRegistry.cancelSyncTimer(id); });
        emitter.onError(e -> { closed[0] = true; executionNodeRegistry.cancelSyncTimer(id); });

        ScheduledFuture<?> timer = executionNodeRegistry.getScheduler().scheduleWithFixedDelay(() -> {
            if (closed[0]) return;
            try {
                ExecutionModel exec = harnessEngine.getExecution(id);
                if (exec == null) return;

                if (exec.getSteps() != null && !exec.getSteps().isEmpty()) {
                    for (ExecutionStepModel step : exec.getSteps()) {
                        Map<String, Object> stepData = new HashMap<>();
                        stepData.put("stepId", step.getId());
                        stepData.put("stepName", step.getStepName());
                        stepData.put("status", step.getStatus());
                        stepData.put("outputData", step.getOutputData() != null ? step.getOutputData() : "");
                        emitter.send(SseEmitter.event().name("step").data(stepData));
                    }
                }

                String status = exec.getStatus();
                if ("completed".equals(status) || "failed".equals(status)
                        || "cancelled".equals(status) || "budget_exhausted".equals(status)) {
                    emitter.send(SseEmitter.event().name("done").data(toExecutionInfoSingle(exec)));
                    emitter.complete();
                    executionNodeRegistry.removeEmitter(id);
                    executionNodeRegistry.cancelSyncTimer(id);
                    closed[0] = true;
                } else if ("paused".equals(status)) {
                    emitter.send(SseEmitter.event().name("pause").data(toExecutionInfoSingle(exec)));
                }
            } catch (Exception e) {
                log.warn("Harness periodic SSE sync failed for executionId={}: {}", id, e.getMessage());
                executionNodeRegistry.cancelSyncTimer(id);
                closed[0] = true;
            }
        }, 3, 3, TimeUnit.SECONDS);
        executionNodeRegistry.putSyncTimer(id, timer);
    }

    @GetMapping("/active-tasks")
    public Result<List<ExecutionInfo>> listActiveTasks(@RequestParam Long scopeId) {
        List<ExecutionModel> executions = executionTracker.listExecutions(scopeId, "page_merge");
        List<ExecutionInfo> active = executions.stream()
            .filter(e -> {
                String s = e.getStatus();
                return "running".equals(s) || "pending".equals(s)
                    || ("completed".equals(s) && e.getCompletedAt() != null
                        && e.getCompletedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(30)));
            })
            .limit(50)
            .map(m -> toExecutionInfo(m, Map.of()))
            .collect(Collectors.toList());
        return Result.success(active);
    }

    @GetMapping("/token-usage")
    public Result<TokenUsageInfo> getTokenUsage() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        TokenUsageInfo info = buildTokenUsageInfo(scopeId);
        return Result.success(info);
    }

    @GetMapping("/token-usage/by-type")
    public Result<TokenUsageInfo> getTokenUsageByType() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        TokenUsageInfo info = buildTokenUsageInfo(scopeId);
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        LocalDate monthEnd = LocalDate.now();
        Map<String, Long> byType = tokenUsageDailyService.queryByType(scopeId, monthStart, monthEnd);
        info.setIngestTokens(byType.getOrDefault("ingest", 0L));
        info.setQueryTokens(byType.getOrDefault("query", 0L));
        info.setLintTokens(byType.getOrDefault("lint", 0L));
        return Result.success(info);
    }

    @GetMapping("/token-usage/trend")
    public Result<List<TokenUsageTrendItem>> getTokenUsageTrend(
        @RequestParam(defaultValue = "30") int days) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(tokenUsageDailyService.queryTrend(scopeId, LocalDate.now().minusDays(days), LocalDate.now()));
    }

    private TokenUsageInfo buildTokenUsageInfo(Long scopeId) {
        ScopeBudgetDO budget = scopeBudgetMapper.selectOne(
            new LambdaQueryWrapper<ScopeBudgetDO>().eq(ScopeBudgetDO::getScopeId, scopeId)
        );
        TokenUsageInfo info = new TokenUsageInfo();
        int usedTokens = budget != null ? (budget.getUsedTokens() != null ? budget.getUsedTokens() : 0) : 0;
        int budgetTokens = budget != null ? budget.getMonthlyBudget() : 1000000;
        info.setTotalTokens(usedTokens);
        info.setBudget(budgetTokens);
        info.setRemaining(budgetTokens - usedTokens);
        info.setUsagePercent(budgetTokens > 0 ? Math.round((double) usedTokens / budgetTokens * 10000) / 100.0 : 0);
        info.setAlertLevel(tokenUsageMonitor.getAlertLevel(scopeId));
        return info;
    }

    @GetMapping("/schema")
    public Result<java.util.List<SchemaInfo>> listSchemas() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        java.util.List<SchemaConfigDO> schemas = schemaManager.listSchemas(scopeId);
        java.util.List<SchemaInfo> infos = schemas.stream().map(this::toSchemaInfo).collect(Collectors.toList());
        return Result.success(infos);
    }

    @GetMapping("/schema/{key}")
    public Result<SchemaInfo> getSchema(@PathVariable String key) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SchemaConfigDO schema = schemaManager.getSchema(scopeId, key);
        if (schema == null) {
            return Result.failed(ErrorCode.SCHEMA_CONFIG_NOT_FOUND);
        }
        return Result.success(toSchemaInfo(schema));
    }

    @GetMapping("/schema/{key}/versions")
    public Result<java.util.List<SchemaVersionInfo>> listSchemaVersions(@PathVariable String key) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        java.util.List<SchemaConfigVersionDO> versions = schemaManager.listVersions(scopeId, key);
        java.util.List<SchemaVersionInfo> infos = versions.stream()
            .map(this::toSchemaVersionInfo)
            .collect(Collectors.toList());
        return Result.success(infos);
    }

    @GetMapping("/schema/versions/{versionId}")
    public Result<SchemaVersionInfo> getSchemaVersion(@PathVariable Long versionId) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SchemaConfigVersionDO version = schemaManager.getVersion(versionId);
        if (version == null) {
            return Result.failed(ErrorCode.SCHEMA_VERSION_NOT_FOUND);
        }
        if (!scopeId.equals(version.getScopeId())) {
            return Result.failed(ErrorCode.SCHEMA_VERSION_ACCESS_DENIED);
        }
        return Result.success(toSchemaVersionInfo(version));
    }

    @GetMapping("/schema/bootstrap/status")
    public Result<java.util.Map<String, Object>> bootstrapStatus() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        boolean required = schemaBootstrapService.isBootstrapRequired(scopeId);
        boolean aiConfigured = llmClient != null && llmClient.isAvailable();
        return Result.success(java.util.Map.of(
            "required", required,
            "scopeId", scopeId,
            "aiConfigured", aiConfigured
        ));
    }

    /**
     * 宪法规则 5：Schema 升级后已有页面不重写，Lint 产出遗留页面迁移报告。
     * 本接口返回当前 scope 最新版本号、未打版本戳页面数、遵循旧版本页面数及最多 50 条示例。
     */
    @GetMapping("/schema/migration-report")
    public Result<SchemaMigrationReport> schemaMigrationReport(
        @RequestParam(name = "key", defaultValue = "wiki_schema") String key
    ) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(schemaManager.buildMigrationReport(scopeId, key));
    }

    @GetMapping("/schema/paradigms")
    public Result<java.util.List<Map<String, Object>>> listParadigms() {
        return Result.success(paradigmCatalog.listAll().stream()
            .map(ParadigmCatalog.Paradigm::toSummary)
            .collect(Collectors.toList()));
    }

    // ===== V2 Bootstrap API =====

    @PostMapping("/schema/bootstrap/v2/start")
    public Result<SchemaBootstrapService.V2StartPayload> bootstrapV2Start() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(schemaBootstrapService.startV2(scopeId));
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/capabilities")
    public Result<Map<String, Object>> bootstrapV2Capabilities(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        @SuppressWarnings("unchecked")
        List<String> capabilityIds = (List<String>) body.get("capabilityIds");
        return Result.success(schemaBootstrapService.selectCapabilities(sessionId, capabilityIds));
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/category-tree")
    public Result<Map<String, Object>> bootstrapV2CategoryTree(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String treeJson = body.get("tree") instanceof String
            ? (String) body.get("tree")
            : new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("tree")).toString();
        return Result.success(schemaBootstrapService.submitCategoryTree(sessionId, treeJson));
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/page-blueprint")
    public Result<Map<String, Object>> bootstrapV2PageBlueprint(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String blueprintJson = body.get("blueprint") instanceof String
            ? (String) body.get("blueprint")
            : new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("blueprint")).toString();
        return Result.success(schemaBootstrapService.submitPageBlueprint(sessionId, blueprintJson));
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/autonomy")
    public Result<Map<String, Object>> bootstrapV2Autonomy(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String level = (String) body.get("level");
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) body.get("overrides");
        return Result.success(schemaBootstrapService.submitAutonomy(sessionId, level, overrides));
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/finalize")
    public Result<SchemaInfo> bootstrapV2Finalize(@PathVariable String sessionId) {
        SchemaConfigDO saved = schemaBootstrapService.finalizeV2(sessionId);
        return Result.success(toSchemaInfo(saved));
    }

    // ===== V2 Advisor API =====

    @PostMapping("/schema/bootstrap/v2/{sessionId}/advisor/check")
    public Result<List<Map<String, Object>>> advisorCheck(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String step = (String) body.get("step");
        String data = body.get("data") instanceof String
            ? (String) body.get("data")
            : (body.get("data") != null ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("data")).toString() : "");
        @SuppressWarnings("unchecked")
        List<String> capIds = (List<String>) body.get("capabilityIds");

        List<Map<String, Object>> result = switch (step) {
            case "category-tree" -> {
                TokenUsageContext.set(jwtTokenProvider.getCurrentScopeId(), "schema");
                try { yield bootstrapAdvisor.checkCategoryTree(capIds, data); }
                finally { TokenUsageContext.clear(); }
            }
            case "page-blueprint" -> {
                TokenUsageContext.set(jwtTokenProvider.getCurrentScopeId(), "schema");
                try { yield bootstrapAdvisor.checkPageBlueprint(data); }
                finally { TokenUsageContext.clear(); }
            }
            case "autonomy" -> {
                TokenUsageContext.set(jwtTokenProvider.getCurrentScopeId(), "schema");
                try { yield bootstrapAdvisor.checkAutonomy(
                    (String) body.get("level"),
                    (Map<String, Object>) body.get("overrides"),
                    capIds); }
                finally { TokenUsageContext.clear(); }
            }
            default -> List.of();
        };
        return Result.success(result);
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/advisor/chat")
    public Result<Map<String, Object>> advisorChat(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String step = (String) body.get("step");
        String message = (String) body.get("message");
        String stepData = body.get("stepData") instanceof String
            ? (String) body.get("stepData")
            : (body.get("stepData") != null ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("stepData")).toString() : "");
        @SuppressWarnings("unchecked")
        List<String> capIds = (List<String>) body.get("capabilityIds");

        TokenUsageContext.set(jwtTokenProvider.getCurrentScopeId(), "schema");
        try {
            return Result.success(bootstrapAdvisor.chat(step, message, stepData, capIds));
        } finally {
            TokenUsageContext.clear();
        }
    }

    @PostMapping("/schema/bootstrap/v2/{sessionId}/advisor/quality-check")
    public Result<List<Map<String, Object>>> advisorQualityCheck(
        @PathVariable String sessionId,
        @RequestBody Map<String, Object> body
    ) {
        String step = (String) body.get("step");
        String stepData = body.get("stepData") instanceof String
            ? (String) body.get("stepData")
            : (body.get("stepData") != null ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("stepData")).toString() : "");
        @SuppressWarnings("unchecked")
        List<String> capIds = (List<String>) body.get("capabilityIds");

        TokenUsageContext.set(jwtTokenProvider.getCurrentScopeId(), "schema");
        try {
            return Result.success(bootstrapAdvisor.qualityCheck(step, stepData, capIds));
        } finally {
            TokenUsageContext.clear();
        }
    }

    @GetMapping("/schema/taxonomy")
    public Result<SchemaStructuredModel.Taxonomy> getTaxonomy() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SchemaStructuredModel model = schemaManager.getStructuredModel(scopeId);
        if (model == null || model.getTaxonomy() == null) {
            return Result.success(new SchemaStructuredModel.Taxonomy());
        }
        return Result.success(model.getTaxonomy());
    }

    @GetMapping("/schema/patches")
    public Result<java.util.List<SchemaPatchModel>> listPendingPatches() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(schemaPatchService.listPending(scopeId));
    }

    @GetMapping("/schema/patches/observing")
    public Result<java.util.List<SchemaPatchModel>> listObservingPatches() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(schemaPatchService.listObserving(scopeId));
    }

    @GetMapping("/schema/patches/{id}/diff")
    public Result<SchemaPatchModel> getPatchDiff(@PathVariable Long id) {
        return Result.success(schemaPatchService.loadPatchDiff(id));
    }

    @GetMapping("/schema/patches/count")
    public Result<Map<String, Object>> countPendingPatches() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        var latestVersion = schemaManager.findLatestVersion(scopeId, "wiki_schema");
        return Result.success(Map.of(
            "pending", schemaPatchService.countPending(scopeId),
            "observing", schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.OBSERVING),
            "conflictRulings", conflictReviewService.countPending(scopeId),
            "currentVersion", latestVersion != null ? latestVersion.getVersionNumber() : 0
        ));
    }

    /**
     * Schema 共治健康聚合指标（Dashboard 看板专用）。一次请求返回：
     *   - 分层堆积（pending / observing）
     *   - 用户终态分布（accepted / rejected / ignored / superseded）
     *   - Gatekeeper 一致率（用户终态 ↔ Gatekeeper 决定）
     *   - 迁移摘要（currentVersionNumber / untagged / outdated）
     */
    @GetMapping("/schema/governance-health")
    public Result<SchemaGovernanceHealth> schemaGovernanceHealth() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SchemaGovernanceHealth h = new SchemaGovernanceHealth();
        h.setPendingCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.PENDING));
        h.setObservingCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.OBSERVING));
        h.setAcceptedCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.ACCEPTED));
        h.setRejectedCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.REJECTED));
        h.setIgnoredCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.IGNORED));
        h.setSupersededCount(schemaPatchService.countByStatus(scopeId, SchemaPatchModel.Status.SUPERSEDED));

        java.util.List<org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO> evaluated =
            schemaPatchService.listGatekeeperEvaluated(scopeId);
        int total = evaluated.size();
        int matched = 0;
        for (org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO p : evaluated) {
            String gk = p.getGatekeeperDecision();
            String st = p.getStatus();
            if ("APPROVE".equals(gk) && SchemaPatchModel.Status.ACCEPTED.name().equals(st)) matched++;
            else if ("OBSERVE".equals(gk) && SchemaPatchModel.Status.IGNORED.name().equals(st)) matched++;
            else if ("REJECT".equals(gk) && SchemaPatchModel.Status.REJECTED.name().equals(st)) matched++;
        }
        h.setGatekeeperEvaluated(total);
        h.setGatekeeperMatched(matched);
        if (total > 0) {
            h.setGatekeeperMatchRate(new java.math.BigDecimal(matched)
                .divide(new java.math.BigDecimal(total), 2, java.math.RoundingMode.HALF_UP));
        }

        SchemaMigrationReport mig = schemaManager.buildMigrationReport(scopeId, "wiki_schema");
        if (mig != null) {
            h.setCurrentVersionNumber(mig.getCurrentVersionNumber());
            h.setMigrationUntaggedCount(mig.getUntaggedCount());
            h.setMigrationOutdatedCount(mig.getOutdatedCount());
        }
        return Result.success(h);
    }

    @PostMapping("/schema/patches/{id}/accept")
    public Result<SchemaPatchModel> acceptPatch(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(schemaPatchService.accept(id, userId));
    }

    @PostMapping("/schema/patches/{id}/reject")
    public Result<SchemaPatchModel> rejectPatch(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(schemaPatchService.reject(id, userId));
    }

    @PostMapping("/schema/patches/{id}/ignore")
    public Result<SchemaPatchModel> ignorePatch(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(schemaPatchService.ignore(id, userId));
    }

    @PostMapping("/schema/patches/{id}/promote")
    public Result<SchemaPatchModel> promotePatch(@PathVariable Long id) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return Result.success(schemaPatchService.promoteObserving(id, userId));
    }

    @PostMapping("/schema/patches/batch/accept")
    public Result<Map<String, Object>> batchAcceptPatches(@RequestBody Map<String, Object> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue).toList();
        SchemaPatchService.BatchResult r = schemaPatchService.batchAccept(ids, userId);
        return Result.success(Map.of("processed", r.processed(), "failed", r.failed()));
    }

    @PostMapping("/schema/patches/batch/reject")
    public Result<Map<String, Object>> batchRejectPatches(@RequestBody Map<String, Object> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue).toList();
        SchemaPatchService.BatchResult r = schemaPatchService.batchReject(ids, userId);
        return Result.success(Map.of("processed", r.processed(), "failed", r.failed()));
    }

    @PostMapping("/schema/patches/batch/ignore")
    public Result<Map<String, Object>> batchIgnorePatches(@RequestBody Map<String, Object> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
            .map(Number::longValue).toList();
        SchemaPatchService.BatchResult r = schemaPatchService.batchIgnore(ids, userId);
        return Result.success(Map.of("processed", r.processed(), "failed", r.failed()));
    }

    @GetMapping("/conflict-rulings")
    public Result<List<ConflictReviewDO>> listConflictRulings() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(conflictReviewService.listPending(scopeId));
    }

    @GetMapping("/conflict-rulings/count")
    public Result<Map<String, Integer>> countConflictRulings() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(Map.of("pending", conflictReviewService.countPending(scopeId)));
    }

    @PostMapping("/conflict-rulings/{id}/execute")
    public Result<ConflictReviewDO> executeRuling(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = jwtTokenProvider.getCurrentUserId();
        String action = body.get("action");
        String detail = body.get("detail");
        if (action == null || action.isBlank()) {
            return Result.failed(ErrorCode.SCHEMA_ACTION_TYPE_REQUIRED);
        }
        return Result.success(conflictReviewService.executeRuling(id, userId, action, detail));
    }

    @PostMapping("/conflict-rulings/{id}/cancel")
    public Result<Void> cancelRuling(@PathVariable Long id) {
        conflictReviewService.cancelRuling(id);
        return Result.success();
    }

    /**
     * 回滚到指定历史版本。会产生一个新版本（sourceType=ROLLBACK），不会删除任何历史版本。
     */
    @PostMapping("/schema/versions/{versionId}/rollback")
    public Result<SchemaVersionInfo> rollbackSchemaVersion(@PathVariable Long versionId) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        SchemaConfigVersionDO target = schemaManager.getVersion(versionId);
        if (target == null) return Result.failed(ErrorCode.SCHEMA_TARGET_VERSION_NOT_FOUND);
        if (!scopeId.equals(target.getScopeId())) return Result.failed(ErrorCode.SCHEMA_ROLLBACK_ACCESS_DENIED);

        SchemaConfigDO current = schemaManager.getSchema(scopeId, target.getConfigKey());
        schemaManager.saveSchema(
            scopeId,
            target.getConfigKey(),
            target.getConfigValue(),
            current == null ? "wiki" : current.getConfigGroup(),
            current == null ? "回滚生成" : current.getDescription(),
            SchemaManager.SOURCE_ROLLBACK,
            target.getId(),
            userId
        );
        Long newId = schemaManager.getCurrentVersionId(scopeId, target.getConfigKey());
        SchemaConfigVersionDO neo = schemaManager.getVersion(newId);
        return Result.success(toSchemaVersionInfo(neo));
    }

    @GetMapping("/system-config/{key}")
    public Result<java.util.Map<String, String>> getSystemConfig(@PathVariable String key) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SystemConfigDO config = systemConfigService.getConfig(scopeId, key);
        if (config == null) {
            return Result.success(java.util.Map.of("key", key, "value", ""));
        }
        return Result.success(java.util.Map.of("key", config.getConfigKey(), "value", config.getConfigValue()));
    }

    @PostMapping("/system-config/{key}")
    public Result<java.util.Map<String, String>> saveSystemConfig(
            @PathVariable String key,
            @RequestBody java.util.Map<String, String> body) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        String value = body.get("value");
        if (value == null) {
            return Result.failed(ErrorCode.SCHEMA_CONFIG_VALUE_REQUIRED);
        }
        SystemConfigDO saved = systemConfigService.saveConfig(scopeId, key, value, userId);
        return Result.success(java.util.Map.of("key", saved.getConfigKey(), "value", saved.getConfigValue()));
    }

    private Map<Long, String> batchLoadSourceNames(java.util.List<ExecutionModel> models) {
        Set<Long> sourceIds = models.stream()
            .map(ExecutionModel::getSourceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return sourceMapper.selectBatchIds(sourceIds).stream()
            .collect(Collectors.toMap(SourceDO::getId, SourceDO::getName));
    }

    private ExecutionInfo toExecutionInfo(ExecutionModel model, Map<Long, String> sourceNameMap) {
        ExecutionInfo info = new ExecutionInfo();
        info.setExecutionId(model.getId());
        info.setOperationType(model.getType());
        info.setStatus(model.getStatus());
        info.setScopeId(model.getScopeId());
        info.setSourceId(model.getSourceId());
        info.setSchemaConfigId(model.getSchemaConfigId());
        info.setStartTime(model.getStartedAt());
        info.setEndTime(model.getCompletedAt());
        info.setCreatedAt(model.getCreatedAt());
        info.setTotalTokens(model.getTotalTokens());
        info.setErrorMessage(model.getErrorMessage());
        if (model.getSourceId() != null && sourceNameMap.containsKey(model.getSourceId())) {
            info.setSourceName(sourceNameMap.get(model.getSourceId()));
        }
        if (model.getSteps() != null) {
            info.setSteps(model.getSteps().stream()
                .map(this::toStepInfo)
                .collect(Collectors.toList()));
        }
        return info;
    }

    private ExecutionInfo toExecutionInfoSingle(ExecutionModel model) {
        Map<Long, String> sourceNameMap;
        if (model.getSourceId() != null) {
            SourceDO source = sourceMapper.selectById(model.getSourceId());
            sourceNameMap = source != null ? Map.of(source.getId(), source.getName()) : Map.of();
        } else {
            sourceNameMap = Map.of();
        }
        return toExecutionInfo(model, sourceNameMap);
    }

    private ExecutionStepInfo toStepInfo(ExecutionStepModel step) {
        ExecutionStepInfo stepInfo = new ExecutionStepInfo();
        stepInfo.setStepId(step.getId());
        stepInfo.setStepName(step.getStepName());
        stepInfo.setStepOrder(step.getStepOrder());
        stepInfo.setStatus(step.getStatus());
        stepInfo.setInputData(step.getInputData());
        stepInfo.setOutputData(step.getOutputData());
        stepInfo.setTokensUsed(step.getTokensUsed());
        stepInfo.setDurationMs(step.getDurationMs());
        stepInfo.setApprovalLevel(step.getApprovalLevel());
        stepInfo.setStartedAt(step.getStartedAt());
        stepInfo.setCompletedAt(step.getCompletedAt());
        return stepInfo;
    }

    private SchemaInfo toSchemaInfo(SchemaConfigDO schema) {
        SchemaInfo info = new SchemaInfo();
        info.setId(schema.getId());
        info.setConfigKey(schema.getConfigKey());
        info.setConfigValue(schema.getConfigValue());
        info.setConfigGroup(schema.getConfigGroup());
        info.setScopeId(schema.getScopeId());
        info.setDescription(schema.getDescription());
        info.setUpdatedAt(schema.getUpdatedAt());
        return info;
    }

    private SchemaVersionInfo toSchemaVersionInfo(SchemaConfigVersionDO v) {
        SchemaVersionInfo info = new SchemaVersionInfo();
        info.setId(v.getId());
        info.setScopeId(v.getScopeId());
        info.setConfigKey(v.getConfigKey());
        info.setConfigValue(v.getConfigValue());
        info.setParentVersionId(v.getParentVersionId());
        info.setVersionNumber(v.getVersionNumber());
        info.setSourceType(v.getSourceType());
        info.setSourceOpId(v.getSourceOpId());
        info.setCreatedBy(v.getCreatedBy());
        info.setCreatedAt(v.getCreatedAt());
        return info;
    }
}