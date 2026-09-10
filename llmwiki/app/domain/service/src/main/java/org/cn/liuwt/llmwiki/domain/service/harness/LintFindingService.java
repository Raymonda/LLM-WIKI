package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LintFindingService {

    private static final Logger log = LoggerFactory.getLogger(LintFindingService.class);

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private SchemaSection6Parser schemaSection6Parser;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Long createFinding(Long scopeId, Long executionId, String findingType,
                               String priority, String title, String detail,
                               String pagePath, Long assetId, Map<String, Object> extra) {
        return createFinding(scopeId, executionId, findingType, priority, title, detail,
            pagePath, assetId, extra, null);
    }

    public Long createFinding(Long scopeId, Long executionId, String findingType,
                               String priority, String title, String detail,
                               String pagePath, Long assetId, Map<String, Object> extra,
                               LintRulesConfig rulesConfig) {
        LambdaQueryWrapper<LintFindingDO> existingWrapper = new LambdaQueryWrapper<LintFindingDO>()
            .eq(LintFindingDO::getScopeId, scopeId)
            .eq(LintFindingDO::getFindingType, findingType)
            .in(LintFindingDO::getStatus, "open", "repairing", "awaiting_approval");
        if (assetId != null) {
            existingWrapper.eq(LintFindingDO::getAssetId, assetId);
        } else {
            existingWrapper.isNull(LintFindingDO::getAssetId);
        }
        LintFindingDO existing = lintFindingMapper.selectOne(existingWrapper);
        if (existing != null) {
            existing.setTitle(title);
            existing.setDetail(detail);
            existing.setPriority(priority);
            existing.setExecutionId(executionId);
            if (extra != null) {
                try {
                    existing.setExtra(objectMapper.writeValueAsString(extra));
                } catch (JsonProcessingException e) {
                    log.warn("Failed to serialize extra for finding {}", existing.getId(), e);
                }
                if (extra.containsKey("handlingMethod")) {
                    existing.setHandlingMethod((String) extra.get("handlingMethod"));
                }
                existing.setRiskScore(computeRiskScore(scopeId, findingType, priority, extra, rulesConfig));
            }
            lintFindingMapper.updateById(existing);
            log.debug("Updated existing lint_finding id={}, type={}", existing.getId(), findingType);
            return existing.getId();
        }
        LambdaQueryWrapper<LintFindingDO> dismissedWrapper = new LambdaQueryWrapper<LintFindingDO>()
            .eq(LintFindingDO::getScopeId, scopeId)
            .eq(LintFindingDO::getFindingType, findingType)
            .eq(LintFindingDO::getStatus, "dismissed");
        if (assetId != null) {
            dismissedWrapper.eq(LintFindingDO::getAssetId, assetId);
        } else {
            dismissedWrapper.isNull(LintFindingDO::getAssetId);
        }
        dismissedWrapper.orderByDesc(LintFindingDO::getId).last("LIMIT 1");
        LintFindingDO dismissed = lintFindingMapper.selectOne(dismissedWrapper);
        if (dismissed != null && !isRevivalAllowed(dismissed, assetId)) {
            log.debug("Skip creating finding type={}, assetId={}: user dismissed and page unchanged",
                findingType, assetId);
            return null;
        }
        LintFindingDO finding = new LintFindingDO();
        finding.setScopeId(scopeId);
        finding.setExecutionId(executionId);
        finding.setFindingType(findingType);
        finding.setPriority(priority);
        finding.setTitle(title);
        finding.setDetail(detail);
        finding.setPagePath(pagePath);
        finding.setAssetId(assetId);
        finding.setStatus("open");
        if (extra != null) {
            try {
                finding.setExtra(objectMapper.writeValueAsString(extra));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize extra for new finding", e);
            }
            if (extra.containsKey("handlingMethod")) {
                finding.setHandlingMethod((String) extra.get("handlingMethod"));
            } else {
                finding.setHandlingMethod(inferHandlingMethod(scopeId, findingType, priority, rulesConfig));
            }
            finding.setRiskScore(computeRiskScore(scopeId, findingType, priority, extra, rulesConfig));
        } else {
            finding.setHandlingMethod(inferHandlingMethod(scopeId, findingType, priority, rulesConfig));
            finding.setRiskScore(computeRiskScore(scopeId, findingType, priority, null, rulesConfig));
        }
        lintFindingMapper.insert(finding);
        log.debug("Created lint_finding type={}, title={}, id={}", findingType, title, finding.getId());
        return finding.getId();
    }

    private boolean isRevivalAllowed(LintFindingDO dismissed, Long assetId) {
        LocalDateTime dismissedAt = dismissed.getArchivedAt() != null ? dismissed.getArchivedAt()
            : (dismissed.getUpdatedAt() != null ? dismissed.getUpdatedAt() : dismissed.getCreatedAt());
        if (dismissedAt == null) {
            return true;
        }
        if (assetId == null) {
            return false;
        }
        WikiPageDO page = wikiPageMapper.selectById(assetId);
        if (page == null || page.getDeletedAt() != null) {
            return false;
        }
        LocalDateTime contentUpdatedAt = page.getContentUpdatedAt() != null
            ? page.getContentUpdatedAt() : page.getUpdatedAt();
        return contentUpdatedAt != null && contentUpdatedAt.isAfter(dismissedAt);
    }

    @Transactional
    public int autoArchiveSupersededFindings(Long scopeId, Long currentExecutionId, Set<Long> touchedFindingIds) {
        LambdaQueryWrapper<LintFindingDO> queryWrapper = new LambdaQueryWrapper<LintFindingDO>()
            .eq(LintFindingDO::getScopeId, scopeId)
            .in(LintFindingDO::getStatus, "open", "awaiting_approval", "auto_resolved", "failed")
            .isNull(LintFindingDO::getArchivedAt);
        if (currentExecutionId != null) {
            queryWrapper.ne(LintFindingDO::getExecutionId, currentExecutionId);
        }
        if (touchedFindingIds != null && !touchedFindingIds.isEmpty()) {
            queryWrapper.notIn(LintFindingDO::getId, touchedFindingIds);
        }
        List<LintFindingDO> superseded = lintFindingMapper.selectList(queryWrapper);

        if (superseded.isEmpty()) {
            log.debug("No superseded findings to archive for scopeId={}, executionId={}", scopeId, currentExecutionId);
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        java.util.Set<Long> affectedPageIds = new java.util.LinkedHashSet<>();
        for (LintFindingDO f : superseded) {
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, f.getId())
                    .set(LintFindingDO::getStatus, "auto_resolved")
                    .set(LintFindingDO::getHandlingMethod, "superseded")
                    .set(LintFindingDO::getAutoResolvedAt, now)
                    .set(LintFindingDO::getArchivedAt, now)
            );
            if (f.getAssetId() != null) {
                affectedPageIds.add(f.getAssetId());
            }
        }
        for (Long pageId : affectedPageIds) {
            wikiFileService.recalcPageHealthStatus(scopeId, pageId);
        }
        log.info("Auto-archived {} superseded findings for scopeId={}, executionId={}",
            superseded.size(), scopeId, currentExecutionId);
        return superseded.size();
    }

    public List<LintFindingDO> listFindings(Long scopeId, String findingType, String status, String priority) {
        LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LintFindingDO::getScopeId, scopeId);
        wrapper.isNull(LintFindingDO::getArchivedAt);
        wrapper.ne(LintFindingDO::getFindingType, "schema_violation");
        if (findingType != null && !findingType.isBlank()) {
            wrapper.eq(LintFindingDO::getFindingType, findingType);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(LintFindingDO::getStatus, status);
        }
        if (priority != null && !priority.isBlank()) {
            wrapper.eq(LintFindingDO::getPriority, priority);
        }
        wrapper.orderByAsc(LintFindingDO::getPriority);
        wrapper.orderByDesc(LintFindingDO::getCreatedAt);
        return lintFindingMapper.selectList(wrapper);
    }

    public List<LintFindingDO> listFindingsByStatuses(Long scopeId, String findingType, List<String> statuses, String priority) {
        LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LintFindingDO::getScopeId, scopeId);
        wrapper.isNull(LintFindingDO::getArchivedAt);
        wrapper.ne(LintFindingDO::getFindingType, "schema_violation");
        if (findingType != null && !findingType.isBlank()) {
            wrapper.eq(LintFindingDO::getFindingType, findingType);
        }
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(LintFindingDO::getStatus, statuses);
        }
        if (priority != null && !priority.isBlank()) {
            wrapper.eq(LintFindingDO::getPriority, priority);
        }
        wrapper.orderByAsc(LintFindingDO::getPriority);
        wrapper.orderByDesc(LintFindingDO::getCreatedAt);
        return lintFindingMapper.selectList(wrapper);
    }

    public List<LintFindingDO> findByRepairExecutionId(Long repairExecutionId) {
        return lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getRepairExecutionId, repairExecutionId)
                .eq(LintFindingDO::getStatus, "repairing")
        );
    }

    public List<LintFindingDO> listFindingsByExecution(Long executionId) {
        return lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getExecutionId, executionId)
                .orderByDesc(LintFindingDO::getCreatedAt)
        );
    }

    private static final Set<String> VALID_FINDING_STATUSES = Set.of(
        "open", "awaiting_approval", "repairing", "deferred",
        "auto_resolved", "resolved", "dismissed", "rolled_back", "failed");

    public void updateStatus(Long findingId, String status) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) {
            throw new BusinessException(ErrorCode.LINT_FINDING_NOT_FOUND, findingId);
        }
        if (status == null || !VALID_FINDING_STATUSES.contains(status)
                || !isValidStatusTransition(finding.getStatus(), status)) {
            throw new BusinessException(ErrorCode.LINT_INVALID_STATUS_TRANSITION,
                finding.getStatus() != null ? finding.getStatus() : "null",
                status != null ? status : "null");
        }
        Set<String> terminalStatuses = Set.of("resolved", "dismissed", "rolled_back");
        boolean isTerminal = terminalStatuses.contains(status);
        LambdaUpdateWrapper<LintFindingDO> updateWrapper = new LambdaUpdateWrapper<LintFindingDO>()
            .eq(LintFindingDO::getId, findingId)
            .set(LintFindingDO::getStatus, status);
        if (isTerminal) {
            updateWrapper.set(LintFindingDO::getArchivedAt, LocalDateTime.now());
        }
        lintFindingMapper.update(null, updateWrapper);
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
    }

    public Map<String, Object> getHealthSummary(Long scopeId) {
        List<Map<String, Object>> results = lintFindingMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LintFindingDO>()
                .eq("scope_id", scopeId)
                .in("status", "open", "awaiting_approval", "repairing", "deferred", "failed")
                .isNull("archived_at")
                .ne("finding_type", "schema_violation")
                .groupBy("priority")
                .select("priority", "COUNT(*) AS cnt")
        );
        long highCount = 0, mediumCount = 0, lowCount = 0;
        for (Map<String, Object> row : results) {
            String priority = (String) row.get("priority");
            long count = ((Number) row.get("cnt")).longValue();
            switch (priority) {
                case "high" -> highCount = count;
                case "medium" -> mediumCount = count;
                case "low" -> lowCount = count;
            }
        }
        return Map.of(
            "total", highCount + mediumCount + lowCount,
            "high", highCount,
            "medium", mediumCount,
            "low", lowCount
        );
    }

    private boolean isValidStatusTransition(String from, String to) {
        if (from == null || to == null) return false;
        Set<String> terminalStatuses = Set.of("resolved", "dismissed", "rolled_back", "failed", "auto_resolved");
        if (terminalStatuses.contains(from)) {
            return "rolled_back".equals(to) || ("failed".equals(from) && "open".equals(to));
        }
        return true;
    }

    private String inferHandlingMethod(Long scopeId, String findingType, String priority, LintRulesConfig rulesConfig) {
        if (rulesConfig == null) {
            rulesConfig = schemaSection6Parser.parse(scopeId);
        }
        LintRulesConfig.DiagnosticRule rule = rulesConfig.getDiagnosticRules().get(findingType);
        if (rule != null) {
            String method = rule.getDefaultHandlingMethod();
            if ("action".equals(findingType) && "high".equals(priority) && "auto_repair".equals(method)) {
                return "ruling_brief";
            }
            return method;
        }
        return "dismiss";
    }

    private int computeRiskScore(Long scopeId, String findingType, String priority, Map<String, Object> extra, LintRulesConfig rulesConfig) {
        if (rulesConfig == null) {
            rulesConfig = schemaSection6Parser.parse(scopeId);
        }
        LintRulesConfig.RiskScoreWeights weights = rulesConfig.getRiskScoreWeights();
        int reversibility = getWeightedScore("reversibility", findingType) * weights.getReversibilityWeight();
        int scope = switch (priority) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        } * weights.getScopeWeight();
        int schemaCompliance = getWeightedScore("schemaCompliance", findingType) * weights.getSchemaComplianceWeight();
        int infoLoss = getWeightedScore("infoLoss", findingType) * weights.getInfoLossWeight();
        int subjectivity = getWeightedScore("subjectivity", findingType) * weights.getSubjectivityWeight();
        return reversibility + scope + schemaCompliance + infoLoss + subjectivity;
    }

    private int getWeightedScore(String dimension, String findingType) {
        return switch (dimension) {
            case "reversibility" -> switch (findingType) {
                case "orphan", "missing_crossref" -> 0;
                case "stale", "gap" -> 1;
                case "conflict", "schema_violation" -> 2;
                default -> 1;
            };
            case "schemaCompliance" -> "conflict".equals(findingType) || "schema_violation".equals(findingType) ? 2 : 0;
            case "infoLoss" -> "stale".equals(findingType) || "conflict".equals(findingType) || "schema_violation".equals(findingType) ? 1 : 0;
            case "subjectivity" -> switch (findingType) {
                case "orphan", "missing_crossref" -> 0;
                case "gap", "stale" -> 1;
                case "conflict", "schema_violation" -> 2;
                default -> 1;
            };
            default -> 0;
        };
    }

    public String determineInterventionTier(Long scopeId, int riskScore) {
        LintRulesConfig config = schemaSection6Parser.parse(scopeId);
        LintRulesConfig.InterventionTiers tiers = config.getInterventionTiers();
        if (riskScore <= tiers.getAutoRepairMax()) return "auto_repair";
        if (riskScore <= tiers.getAutoRepairWithNotifyMax()) return "auto_repair_with_notify";
        return "ruling_brief";
    }

    public void setRulingBrief(Long findingId, String rulingBriefJson) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getRulingBriefJson, rulingBriefJson)
                .set(LintFindingDO::getStatus, "awaiting_approval")
        );
        log.info("Set ruling brief for finding id={}, status → awaiting_approval", findingId);
    }

    public void annotateDeferred(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        String existingExtra = finding.getExtra();
        Map<String, Object> extraMap;
        try {
            extraMap = (existingExtra != null && !existingExtra.isBlank())
                ? objectMapper.readValue(existingExtra, Map.class) : new java.util.HashMap<>();
        } catch (Exception e) {
            extraMap = new java.util.HashMap<>();
        }
        extraMap.put("deferredAt", java.time.LocalDateTime.now().toString());
        extraMap.put("deferredReason", "Schema 路由决策为 DEFER，暂缓处理");
        try {
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, findingId)
                    .set(LintFindingDO::getExtra, objectMapper.writeValueAsString(extraMap))
                    .set(LintFindingDO::getStatus, "deferred")
            );
            log.info("Annotated finding id={} as deferred", findingId);
        } catch (Exception e) {
            log.warn("annotateDeferred failed for findingId={}: {}", findingId, e.getMessage());
        }
    }

    @Transactional
    public void autoResolve(Long findingId, String handlingMethod) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null || !Set.of("open", "awaiting_approval").contains(finding.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "auto_resolved")
                .set(LintFindingDO::getHandlingMethod, handlingMethod != null ? handlingMethod : finding.getHandlingMethod())
                .set(LintFindingDO::getAutoResolvedAt, now)
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Auto-resolved lint_finding id={}, type={}, handling={}", findingId, finding.getFindingType(), finding.getHandlingMethod());
    }

    @Transactional
    public void generatePlanForApproval(Long findingId, String handlingMethod, String planSummary) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null || !"open".equals(finding.getStatus())) return;
        String existingExtra = finding.getExtra();
        Map<String, Object> extraMap;
        try {
            extraMap = (existingExtra != null && !existingExtra.isBlank())
                ? objectMapper.readValue(existingExtra, Map.class) : new java.util.HashMap<>();
        } catch (Exception e) {
            extraMap = new java.util.HashMap<>();
        }
        Map<String, Object> rulingBrief = new java.util.HashMap<>();
        rulingBrief.put("type", handlingMethod);
        rulingBrief.put("summary", planSummary);
        rulingBrief.put("findingType", finding.getFindingType());
        rulingBrief.put("generatedAt", java.time.LocalDateTime.now().toString());
        try {
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, findingId)
                    .set(LintFindingDO::getStatus, "awaiting_approval")
                    .set(LintFindingDO::getHandlingMethod, handlingMethod)
                    .set(LintFindingDO::getRulingBriefJson, objectMapper.writeValueAsString(rulingBrief))
                    .set(LintFindingDO::getExtra, objectMapper.writeValueAsString(extraMap))
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ruling brief for findingId={}", findingId, e);
            return;
        }
        log.info("Generated plan for approval: findingId={}, type={}, handling={}", findingId, finding.getFindingType(), handlingMethod);
    }

    public void resolveSchemaCompliance(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        if (!"schema_compliance".equals(finding.getFindingType()) || !"open".equals(finding.getStatus())) {
            throw new BusinessException(ErrorCode.LINT_INVALID_RESOLVE_TYPE);
        }
        LocalDateTime now = LocalDateTime.now();
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "resolved")
                .set(LintFindingDO::getHandlingMethod, "manual_edit_confirmed")
                .set(LintFindingDO::getArchivedAt, now));
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Schema compliance finding id={} resolved by user confirmation", findingId);
    }

    /**
     * Phase 1 新增：AI 拒绝链接时，标记为 dismissed
     */
    public void dismissFinding(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null || !"open".equals(finding.getStatus())) return;
        
        // 更新 extra 字段记录拒绝理由
        String extra = finding.getExtra();
        try {
            if (extra != null && !extra.isBlank()) {
                var extraMap = objectMapper.readValue(extra, Map.class);
                extraMap.put("aiRejectReason", "语义不相关");
                extra = objectMapper.writeValueAsString(extraMap);
            } else {
                extra = objectMapper.writeValueAsString(Map.of("aiRejectReason", "语义不相关"));
            }
        } catch (Exception e) {
            extra = "{\"aiRejectReason\":\"语义不相关\"}";
        }
        
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "dismissed")
                .set(LintFindingDO::getExtra, extra)
                .set(LintFindingDO::getArchivedAt, LocalDateTime.now())
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Dismissed lint_finding id={}, type={}, reason=AI rejected", findingId, finding.getFindingType());
    }

    /**
     * 从 finding 的 extra 字段解析交叉引用建议，构建前端所需的 crossrefSuggestions JSON
     */
    public String buildCrossrefSuggestions(LintFindingDO finding) {
        if (finding == null || !"missing_crossref".equals(finding.getFindingType())) return null;
        String extra = finding.getExtra();
        if (extra == null || extra.isBlank()) return null;
        try {
            var extraMap = objectMapper.readValue(extra, Map.class);
            Object relatedPageIdObj = extraMap.get("relatedPageId");
            Object relatedPagePathObj = extraMap.get("relatedPagePath");
            if (relatedPageIdObj == null && relatedPagePathObj == null) return null;

            WikiPageDO sourcePage = finding.getAssetId() != null ? wikiPageMapper.selectById(finding.getAssetId()) : null;
            WikiPageDO relatedPage = null;
            if (relatedPageIdObj instanceof Number num) {
                relatedPage = wikiPageMapper.selectById(num.longValue());
            }
            if (relatedPage == null && relatedPagePathObj instanceof String rp && !rp.isBlank()) {
                relatedPage = wikiPageMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageDO>()
                        .eq(WikiPageDO::getScopeId, finding.getScopeId())
                        .eq(WikiPageDO::getFilePath, rp)
                        .last("LIMIT 1"));
            }
            if (sourcePage == null || relatedPage == null) return null;

            String sourceTitle = sourcePage.getTitle() != null ? sourcePage.getTitle() : "";
            String targetTitle = relatedPage.getTitle() != null ? relatedPage.getTitle() : "";
            String sourcePath = sourcePage.getFilePath() != null ? sourcePage.getFilePath() : "";
            String targetPath = relatedPage.getFilePath() != null ? relatedPage.getFilePath() : "";

            long sharedKeywords = extraMap.get("sharedKeywords") instanceof Number sk ? sk.longValue() : 0;
            String sourceCategory = sourcePage.getCategory() != null ? sourcePage.getCategory() : "";
            String targetCategory = relatedPage.getCategory() != null ? relatedPage.getCategory() : "";
            boolean sameCategory = !sourceCategory.isEmpty() && sourceCategory.equals(targetCategory);
            boolean siblingCategory = !sourceCategory.isEmpty() && !targetCategory.isEmpty()
                && extractTopCategory(sourceCategory).equals(extractTopCategory(targetCategory));

            String linkContext;
            if (extraMap.get("linkContext") instanceof String lc && !lc.isBlank()) {
                linkContext = lc;
            } else {
                StringBuilder ctx = new StringBuilder();
                if (sameCategory) {
                    ctx.append("同分类（").append(sourceCategory).append("）下的关联页面");
                } else if (siblingCategory) {
                    ctx.append("相邻分类（").append(extractTopCategory(sourceCategory))
                        .append("）下的关联页面");
                } else {
                    ctx.append("跨分类关联页面");
                }
                if (sharedKeywords > 0) {
                    ctx.append("，共享 ").append(sharedKeywords).append(" 个关键词");
                }
                linkContext = ctx.toString();
            }

            Object confidenceObj = extraMap.get("confidence");
            double confidence;
            if (confidenceObj instanceof Number cn) {
                confidence = cn.doubleValue();
            } else if (sharedKeywords > 0) {
                confidence = Math.min(0.95, 0.5 + sharedKeywords * 0.05);
            } else {
                confidence = 0.6;
            }

            String reason;
            if (extraMap.get("reason") instanceof String r && !r.isBlank()) {
                reason = r;
            } else {
                StringBuilder rsn = new StringBuilder();
                rsn.append("「").append(sourceTitle).append("」与「").append(targetTitle).append("」");
                if (sharedKeywords > 0) {
                    rsn.append("共享 ").append(sharedKeywords).append(" 个关键词");
                }
                if (sameCategory) {
                    rsn.append("且属于同一分类（").append(sourceCategory).append("）");
                } else if (siblingCategory) {
                    rsn.append("且属于相邻分类");
                }
                rsn.append("，建议建立交叉引用以提升知识发现性");
                reason = rsn.toString();
            }

            String linkType;
            if (extraMap.get("linkType") instanceof String lt && !lt.isBlank()) {
                linkType = lt;
            } else if (sameCategory) {
                linkType = "sibling";
            } else if (siblingCategory) {
                linkType = "hierarchy";
            } else {
                linkType = "related";
            }

            var suggestion = Map.of(
                "sourceTitle", sourceTitle,
                "targetTitle", targetTitle,
                "sourcePath", sourcePath,
                "targetPath", targetPath,
                "sourceAssetId", sourcePage.getId(),
                "targetAssetId", relatedPage.getId(),
                "linkContext", linkContext,
                "confidence", confidence,
                "reason", reason,
                "linkType", linkType
            );
            return objectMapper.writeValueAsString(List.of(suggestion));
        } catch (Exception e) {
            log.debug("buildCrossrefSuggestions failed for findingId={}: {}", finding.getId(), e.getMessage());
            return null;
        }
    }

    private String extractTopCategory(String category) {
        if (category == null) return "";
        int slash = category.indexOf('/');
        return slash > 0 ? category.substring(0, slash) : category;
    }

    /**
     * 基于 finding.extra 中的页面 ID 批准交叉引用链接
     */
    @Transactional
    public void approveLinkByIds(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null || !"open".equals(finding.getStatus())) {
            log.warn("Cannot approve link for finding id={}, status={}", findingId, finding != null ? finding.getStatus() : "not_found");
            return;
        }
        try {
            String extra = finding.getExtra();
            if (extra == null || extra.isBlank()) {
                log.warn("Cannot approve link: extra is empty for findingId={}", findingId);
                return;
            }
            var extraMap = objectMapper.readValue(extra, Map.class);

            WikiPageDO sourcePage = finding.getAssetId() != null ? wikiPageMapper.selectById(finding.getAssetId()) : null;
            WikiPageDO targetPage = null;
            Object relatedPageIdObj = extraMap.get("relatedPageId");
            if (relatedPageIdObj instanceof Number num) {
                targetPage = wikiPageMapper.selectById(num.longValue());
            }
            if (targetPage == null) {
                Object relatedPath = extraMap.get("relatedPagePath");
                if (relatedPath instanceof String rp && !rp.isBlank()) {
                    targetPage = wikiPageMapper.selectOne(
                        new LambdaQueryWrapper<WikiPageDO>()
                            .eq(WikiPageDO::getScopeId, finding.getScopeId())
                            .eq(WikiPageDO::getFilePath, rp)
                            .last("LIMIT 1"));
                }
            }
            if (sourcePage == null || targetPage == null) {
                log.warn("Cannot find source or target page for link approval: findingId={}", findingId);
                return;
            }

            LinkWritingService.LinkSuggestion suggestion = new LinkWritingService.LinkSuggestion(
                sourcePage.getFilePath(), targetPage.getFilePath(), "related",
                "用户确认的交叉引用", 1.0, "manual", null
            );
            linkWritingService.upsertLinkRecord(finding.getScopeId(), suggestion);

            autoResolve(findingId, "manual_approve");
            recordFeedback(findingId, "accepted");
            log.info("Approved link for finding id={}: {} -> {}", findingId, sourcePage.getTitle(), targetPage.getTitle());
        } catch (Exception e) {
            log.error("Failed to approve link for finding id={}", findingId, e);
            throw new RuntimeException("批准链接失败: " + e.getMessage(), e);
        }
    }

    /**
     * Phase 2 新增：用户拒绝创建交叉引用
     */
    @Transactional
    public void rejectLink(Long findingId, String sourceTitle, String targetTitle, String reason) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null || !"open".equals(finding.getStatus())) {
            log.warn("Cannot reject link for finding id={}, status={}", findingId, finding != null ? finding.getStatus() : "not_found");
            return;
        }

        try {
            // 更新 extra 字段记录拒绝理由
            String extra = finding.getExtra();
            try {
                if (extra != null && !extra.isBlank()) {
                    var extraMap = objectMapper.readValue(extra, Map.class);
                    extraMap.put("userRejectReason", reason != null ? reason : "用户认为不相关");
                    extra = objectMapper.writeValueAsString(extraMap);
                } else {
                    extra = objectMapper.writeValueAsString(Map.of("userRejectReason", reason != null ? reason : "用户认为不相关"));
                }
            } catch (Exception e) {
                extra = "{\"userRejectReason\":\"" + (reason != null ? reason : "用户认为不相关") + "\"}";
            }

            // 标记 finding 为 dismissed
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, findingId)
                    .set(LintFindingDO::getStatus, "dismissed")
                    .set(LintFindingDO::getExtra, extra)
                    .set(LintFindingDO::getArchivedAt, java.time.LocalDateTime.now())
            );

            // 记录用户反馈
            recordFeedback(findingId, "rejected");

            log.info("Rejected link for finding id={}: {} -> {}, reason={}", findingId, sourceTitle, targetTitle, reason);
        } catch (Exception e) {
            log.error("Failed to reject link for finding id={}", findingId, e);
            throw e;
        }
    }

    @Autowired
    @org.springframework.context.annotation.Lazy
    private StaleRefreshService staleRefreshService;

    @Autowired
    private LinkWritingService linkWritingService;

    @Transactional
    public void batchAutoResolveStaleBySource(Long scopeId) {
        List<LintFindingDO> staleFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getFindingType, "stale")
                .eq(LintFindingDO::getStatus, "open")
        );
        java.util.Set<Long> processedPageIds = new java.util.HashSet<>();
        int refreshed = 0, failed = 0, noSource = 0;

        for (LintFindingDO f : staleFindings) {
            if (!"auto_refresh".equals(f.getHandlingMethod())) continue;
            if (processedPageIds.contains(f.getAssetId())) {
                autoResolve(f.getId(), "auto_refresh");
                refreshed++;
                continue;
            }

            List<Long> sourceIds = findSourceIdsForPage(scopeId, f.getAssetId());
            if (sourceIds.isEmpty()) {
                autoResolve(f.getId(), "manual_edit");
                noSource++;
                continue;
            }

            try {
                markAsRepairing(f.getId(), null);
                staleRefreshService.refreshPage(scopeId, f.getAssetId(), sourceIds.get(0));
                processedPageIds.add(f.getAssetId());
                refreshed++;
            } catch (Exception e) {
                markRepairFailed(f.getId());
                failed++;
                log.warn("batchAutoResolveStaleBySource: refresh failed for finding id={}: {}", f.getId(), e.getMessage());
            }
        }
        log.info("batchAutoResolveStaleBySource completed: scopeId={}, refreshed={}, failed={}, noSource={}", scopeId, refreshed, failed, noSource);
    }

    public List<Long> findSourceIdsForPage(Long scopeId, Long pageId) {
        List<WikiPageSourceDO> relations = wikiPageSourceMapper.selectList(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getPageId, pageId)
        );
        return relations.stream().map(WikiPageSourceDO::getSourceId).collect(Collectors.toList());
    }

    public LintFindingDO getFinding(Long findingId) {
        return lintFindingMapper.selectById(findingId);
    }

    public List<LintFindingDO> listFindingsByScope(Long scopeId, LambdaQueryWrapper<LintFindingDO> wrapper) {
        return lintFindingMapper.selectList(wrapper);
    }

    @Transactional
    public void recordFeedback(Long findingId, String feedback) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;

        Set<String> validFeedbacks = Set.of("accepted", "ignored", "modified");
        if (!validFeedbacks.contains(feedback)) {
            log.warn("Invalid feedback '{}' for finding id={}", feedback, findingId);
            return;
        }

        int newFeedbackCount = (finding.getFeedbackCount() != null ? finding.getFeedbackCount() : 0) + 1;
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getUserFeedback, feedback)
                .set(LintFindingDO::getFeedbackCount, newFeedbackCount)
        );

        if ("ignored".equals(feedback)) {
            String findingType = finding.getFindingType();
            LintRulesConfig config = schemaSection6Parser.parse(finding.getScopeId());
            int dismissThreshold = config.getFeedbackLearning().getDismissCountToDowngrade();
            Long dismissCount = lintFindingMapper.selectCount(
                new LambdaQueryWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getScopeId, finding.getScopeId())
                    .eq(LintFindingDO::getFindingType, findingType)
                    .eq(LintFindingDO::getUserFeedback, "ignored")
            );
            if (dismissCount >= dismissThreshold) {
                log.info("Finding type '{}' reached dismiss threshold ({}) in scopeId={}, probe sensitivity will be MANDATORY downgraded on next Lint run",
                    findingType, dismissThreshold, finding.getScopeId());
            }
        }

        log.info("Recorded feedback '{}' for finding id={}, type={}, feedbackCount={}",
            feedback, findingId, finding.getFindingType(), newFeedbackCount);
    }

    public void markAsRepairing(Long findingId, Long repairExecutionId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        if (!Set.of("open", "awaiting_approval").contains(finding.getStatus())) {
            log.warn("Cannot mark finding id={} as repairing from status {}", findingId, finding.getStatus());
            return;
        }
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "repairing")
                .set(LintFindingDO::getRepairExecutionId, repairExecutionId)
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Finding id={} marked as repairing, repairExecutionId={}", findingId, repairExecutionId);
    }

    public void markRepairFailed(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "open")
                .set(LintFindingDO::getRepairExecutionId, null)
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Finding id={} repair failed, rolled back to open", findingId);
    }

    public void markAsFailed(Long findingId, String errorMessage) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        if (!Set.of("open", "awaiting_approval").contains(finding.getStatus())) {
            log.warn("Cannot mark finding id={} as failed from status {}", findingId, finding.getStatus());
            return;
        }
        String extraJson = finding.getExtra();
        if (extraJson == null || extraJson.isBlank()) {
            extraJson = "{}";
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> extraMap = objectMapper.readValue(extraJson, java.util.Map.class);
            extraMap.put("failedAt", java.time.LocalDateTime.now().toString());
            if (errorMessage != null) {
                extraMap.put("failedReason", errorMessage);
            }
            extraJson = objectMapper.writeValueAsString(extraMap);
        } catch (Exception e) {
            log.warn("Failed to update extra JSON for finding id={}", findingId);
        }
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "failed")
                .set(LintFindingDO::getExtra, extraJson)
                .set(LintFindingDO::getRepairExecutionId, null)
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.warn("Finding id={} marked as failed: {}", findingId, errorMessage);
    }

    public void retryFailedFinding(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        if (!"failed".equals(finding.getStatus())) {
            log.warn("Cannot retry finding id={} with status {}, only failed can be retried", findingId, finding.getStatus());
            return;
        }
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "open")
        );
        if (finding.getAssetId() != null) {
            wikiFileService.recalcPageHealthStatus(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Finding id={} retried from failed to open", findingId);
    }

    @Transactional
    public void rollbackFinding(Long findingId) {
        LintFindingDO finding = lintFindingMapper.selectById(findingId);
        if (finding == null) return;
        if (!Set.of("auto_resolved", "resolved", "dismissed", "failed").contains(finding.getStatus())) {
            log.warn("Cannot rollback finding id={} with status={}, only auto_resolved/resolved/dismissed/failed can be rolled back", findingId, finding.getStatus());
            return;
        }
        String prevStatus = finding.getStatus();
        String extraJson = finding.getExtra();
        if (extraJson == null || extraJson.isBlank()) {
            extraJson = "{}";
        }
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> extraMap = objectMapper.readValue(extraJson, java.util.Map.class);
            extraMap.put("rollbackAt", LocalDateTime.now().toString());
            extraMap.put("prevStatus", prevStatus);
            extraJson = objectMapper.writeValueAsString(extraMap);
        } catch (Exception e) {
            log.warn("Failed to update extra JSON for finding id={}", findingId);
        }
        lintFindingMapper.update(null,
            new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getStatus, "rolled_back")
                .set(LintFindingDO::getExtra, extraJson)
                .set(LintFindingDO::getRepairExecutionId, null)
                .set(LintFindingDO::getArchivedAt, LocalDateTime.now())
        );
        if (finding.getAssetId() != null) {
            markPageNeedsUpdateAfterRollback(finding.getScopeId(), finding.getAssetId());
        }
        log.info("Finding id={} rolled back from {} to rolled_back", findingId, prevStatus);
    }

    private void markPageNeedsUpdateAfterRollback(Long scopeId, Long pageId) {
        WikiPageDO page = wikiPageMapper.selectById(pageId);
        if (page == null) return;
        if (!"needs-update".equals(page.getHealthStatus()) && !"has-problems".equals(page.getHealthStatus())) {
            wikiPageMapper.update(null,
                new LambdaUpdateWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getId, pageId)
                    .set(WikiPageDO::getHealthStatus, "needs-update")
            );
        }
        List<LintFindingDO> otherOpenFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getAssetId, pageId)
                .in(LintFindingDO::getStatus, "open", "awaiting_approval", "repairing", "failed")
        );
        if (!otherOpenFindings.isEmpty()) {
            try {
                wikiFileService.recalcPageHealthStatus(scopeId, pageId);
            } catch (Exception e) {
                log.warn("Failed to recalc health status for page {}", pageId);
            }
        }
    }

    @Transactional
    public void resolvePageFindingsOnIngest(Long scopeId, Long pageId) {
        List<LintFindingDO> staleFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getAssetId, pageId)
                .eq(LintFindingDO::getFindingType, "stale")
                .in(LintFindingDO::getStatus, "open", "repairing")
        );
        if (staleFindings.isEmpty()) {
            return;
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (LintFindingDO f : staleFindings) {
            String prevStatus = f.getStatus();
            String handlingMethod = (f.getHandlingMethod() == null || f.getHandlingMethod().isBlank())
                ? "auto_refresh" : f.getHandlingMethod();
            lintFindingMapper.update(null,
                new LambdaUpdateWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getId, f.getId())
                    .set(LintFindingDO::getStatus, "auto_resolved")
                    .set(LintFindingDO::getAutoResolvedAt, now)
                    .set(LintFindingDO::getHandlingMethod, handlingMethod)
                    .set(LintFindingDO::getRepairExecutionId, null)
                    .set(LintFindingDO::getArchivedAt, now)
            );
            log.info("Ingest auto-resolved stale finding id={}, pageId={}, previousStatus={}", 
                f.getId(), pageId, prevStatus);
        }
        wikiFileService.recalcPageHealthStatus(scopeId, pageId);
    }

    public IPage<LintFindingDO> listFindingsPaged(Long scopeId, String findingType, String status,
                                                    String priority, boolean archived, int page, int size) {
        LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LintFindingDO::getScopeId, scopeId);
        if (archived) {
            wrapper.isNotNull(LintFindingDO::getArchivedAt);
        } else {
            wrapper.isNull(LintFindingDO::getArchivedAt);
        }
        wrapper.ne(LintFindingDO::getFindingType, "schema_violation");
        if (findingType != null && !findingType.isBlank()) {
            wrapper.eq(LintFindingDO::getFindingType, findingType);
        }
        if (status != null && !status.isBlank()) {
            if (status.contains(",")) {
                wrapper.in(LintFindingDO::getStatus, status.split(","));
            } else {
                wrapper.eq(LintFindingDO::getStatus, status);
            }
        }
        if (priority != null && !priority.isBlank()) {
            wrapper.eq(LintFindingDO::getPriority, priority);
        }
        wrapper.orderByAsc(LintFindingDO::getPriority);
        wrapper.orderByDesc(LintFindingDO::getCreatedAt);
        Page<LintFindingDO> pageReq = new Page<>(Math.max(page, 1), Math.max(size, 1));
        return lintFindingMapper.selectPage(pageReq, wrapper);
    }

    public Map<String, Long> getHealthDistribution(Long scopeId) {
        List<Map<String, Object>> results = wikiPageMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiPageDO>()
                .eq("scope_id", scopeId)
                .groupBy("health_status")
                .select("health_status", "COUNT(*) AS cnt")
        );
        Map<String, Long> distribution = new java.util.HashMap<>();
        for (Map<String, Object> row : results) {
            String healthStatus = (String) row.get("health_status");
            long count = ((Number) row.get("cnt")).longValue();
            distribution.put(healthStatus != null ? healthStatus : "unknown", count);
        }
        return distribution;
    }

    public Map<String, Long> getFindingCountsByType(Long scopeId) {
        List<Map<String, Object>> results = lintFindingMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LintFindingDO>()
                .eq("scope_id", scopeId)
                .in("status", "open", "awaiting_approval", "repairing", "deferred", "failed")
                .isNull("archived_at")
                .ne("finding_type", "schema_violation")
                .groupBy("finding_type")
                .select("finding_type", "COUNT(*) AS cnt")
        );
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : results) {
            String type = (String) row.get("finding_type");
            long count = ((Number) row.get("cnt")).longValue();
            counts.put(type, count);
        }
        return counts;
    }

    public Map<String, Long> countDismissedOrIgnoredByType(Long scopeId) {
        List<Map<String, Object>> results = lintFindingMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<LintFindingDO>()
                .eq("scope_id", scopeId)
                .and(w -> w.eq("status", "dismissed").or().eq("user_feedback", "ignored"))
                .groupBy("finding_type")
                .select("finding_type", "COUNT(*) AS cnt")
        );
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Map<String, Object> row : results) {
            String type = (String) row.get("finding_type");
            if (type == null) continue;
            long count = ((Number) row.get("cnt")).longValue();
            counts.put(type, count);
        }
        return counts;
    }

    public long getTotalPages(Long scopeId) {
        return wikiPageMapper.selectCount(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
        );
    }

    public LocalDateTime getLastLintTime(Long scopeId) {
        return executionHistoryService.findLastCompletedAt(scopeId, "lint");
    }

    public List<LintFindingDO> getTopFindings(Long scopeId, int limit) {
        return lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .isNull(LintFindingDO::getArchivedAt)
                .ne(LintFindingDO::getFindingType, "schema_violation")
                .in(LintFindingDO::getStatus, "open", "awaiting_approval", "repairing", "deferred", "failed")
                .orderByAsc(LintFindingDO::getPriority)
                .orderByDesc(LintFindingDO::getCreatedAt)
                .last("LIMIT " + limit)
        );
    }

    public List<Map<String, Object>> getOpenFindingAssetDistribution(Long scopeId) {
        return lintFindingMapper.selectOpenFindingAssetDistribution(scopeId);
    }

    public List<LintFindingDO> listOpenFindings(Long scopeId, String pagePath) {
        LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<LintFindingDO>()
            .eq(LintFindingDO::getScopeId, scopeId)
            .in(LintFindingDO::getStatus, "open", "repairing")
            .isNull(LintFindingDO::getArchivedAt);
        if (pagePath != null && !pagePath.isBlank()) {
            wrapper.eq(LintFindingDO::getPagePath, pagePath);
        }
        wrapper.orderByAsc(LintFindingDO::getPriority);
        wrapper.orderByDesc(LintFindingDO::getCreatedAt);
        return lintFindingMapper.selectList(wrapper);
    }

    // ==================== 诊断结果缓存 ====================

    private static final int ORPHAN_CACHE_DAYS = 7;

    /**
     * 检查某页面的某类型诊断结果是否仍然有效（缓存命中）。
     * 有效条件：存在 open/repairing/dismissed 状态的 finding，且 finding.created_at >= page.content_updated_at。
     * orphan 类型额外有 7 天有效期限制。
     */
    public boolean isCacheValid(Long scopeId, Long pageId, String findingType) {
        if (pageId == null) return false;
        WikiPageDO page = wikiPageMapper.selectById(pageId);
        if (page == null) return false;

        LintFindingDO finding = lintFindingMapper.selectOne(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getAssetId, pageId)
                .eq(LintFindingDO::getFindingType, findingType)
                .in(LintFindingDO::getStatus, "open", "repairing", "dismissed")
                .orderByDesc(LintFindingDO::getCreatedAt)
                .last("LIMIT 1")
        );
        if (finding == null || finding.getCreatedAt() == null) return false;

        LocalDateTime contentUpdatedAt = page.getContentUpdatedAt();
        if (contentUpdatedAt == null) return false;

        // finding 创建时间必须晚于页面内容更新时间
        if (finding.getCreatedAt().isBefore(contentUpdatedAt)) return false;

        // orphan 类型有 7 天缓存有效期
        if ("orphan".equals(findingType)) {
            long daysSinceCreation = ChronoUnit.DAYS.between(finding.getCreatedAt(), LocalDateTime.now());
            if (daysSinceCreation > ORPHAN_CACHE_DAYS) return false;
        }

        return true;
    }

    /**
     * 批量检查一组页面的某类型诊断缓存有效性，返回缓存失效的页面 ID 集合。
     */
    public Set<Long> filterCacheMissPageIds(Long scopeId, Set<Long> pageIds, String findingType) {
        if (pageIds == null || pageIds.isEmpty()) return Set.of();
        Set<Long> missIds = new java.util.HashSet<>();
        for (Long pageId : pageIds) {
            if (!isCacheValid(scopeId, pageId, findingType)) {
                missIds.add(pageId);
            }
        }
        return missIds;
    }

    /**
     * 批量检查一组页面在多个诊断类型上的缓存有效性，任一类型缓存失效即纳入返回集。
     */
    public Set<Long> filterCacheMissPageIds(Long scopeId, Set<Long> pageIds, List<String> findingTypes) {
        if (pageIds == null || pageIds.isEmpty() || findingTypes == null || findingTypes.isEmpty()) {
            return Set.of();
        }
        Set<Long> missIds = new java.util.HashSet<>();
        for (String findingType : findingTypes) {
            missIds.addAll(filterCacheMissPageIds(scopeId, pageIds, findingType));
        }
        return missIds;
    }
}