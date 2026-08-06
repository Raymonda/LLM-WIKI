package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ContentDuplicateDetector;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService.GlobalSummary;
import org.cn.liuwt.llmwiki.domain.service.harness.LintAgent.ProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class LintProbeService {

    private static final Logger log = LoggerFactory.getLogger(LintProbeService.class);
    private static final int BATCH_SIZE = 1000;
    private static final int PROBE_TIMEOUT_BASE_SECONDS = 120;
    private static final int PROBE_TIMEOUT_MAX_SECONDS = 300;
    private static final int AI_VALIDATION_MAX_PAGES = 500;

    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(4,
        r -> { Thread t = new Thread(r, "lint-probe"); t.setDaemon(true); return t; });

    @jakarta.annotation.PreDestroy
    public void shutdown() { probeExecutor.shutdown(); }

    @Autowired
    private LintAgent lintAgent;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private ContentDuplicateDetector contentDuplicateDetector;

    public ProbeOutcome probeAndValidate(Long scopeId, Long executionId,
                                          GlobalSummary summary,
                                          List<LintFindingDO> previousFindings,
                                          Map<String, Long> healthDistribution,
                                          LintRulesConfig rulesConfig) {
        return probeAndValidate(scopeId, executionId, summary, previousFindings, healthDistribution, rulesConfig, null, null);
    }

    public ProbeOutcome probeAndValidate(Long scopeId, Long executionId,
                                          GlobalSummary summary,
                                          List<LintFindingDO> previousFindings,
                                          Map<String, Long> healthDistribution,
                                          LintRulesConfig rulesConfig,
                                          List<WikiPageDO> focusPages,
                                          List<WikiPageDO> allPagesForStats) {

        String previousFindingsSummary = buildPreviousFindingsSummary(previousFindings, rulesConfig);
        String previousHealthSummary = buildPreviousHealthSummary(healthDistribution);

        Set<String> downgradedTypes = computeDowngradedTypes(previousFindings, rulesConfig);

        // 增量模式下，利用诊断缓存过滤未变更页面
        List<WikiPageDO> effectiveFocusPages = focusPages;
        if (focusPages != null && !focusPages.isEmpty()) {
            Set<Long> focusPageIds = focusPages.stream()
                .filter(p -> p.getId() != null)
                .map(WikiPageDO::getId)
                .collect(Collectors.toSet());
            Set<Long> stalePageIds = lintFindingService.filterCacheMissPageIds(scopeId, focusPageIds,
                List.of("orphan", "stale", "missing_crossref", "conflict"));
            if (!stalePageIds.isEmpty()) {
                List<WikiPageDO> filtered = focusPages.stream()
                    .filter(p -> p.getId() == null || stalePageIds.contains(p.getId()))
                    .collect(Collectors.toList());
                int cached = focusPages.size() - filtered.size();
                if (cached > 0) {
                    log.info("FindingCache: {} focus pages skipped (cache valid), {} pages need re-diagnosis",
                        cached, filtered.size());
                }
                effectiveFocusPages = filtered;
            }
        }
        final List<WikiPageDO> probeFocusPages = effectiveFocusPages;

        // 并行执行 AI probe + SQL 安全网检测 + 内容重复检测
        // 全量检查、增量操作：SQL 确定性检测零 token 成本，始终全量扫描，
        // 保证无变更时也能发现存量问题；AI probe 仍按 focus 增量执行
        CompletableFuture<ProbeResult> aiFuture = CompletableFuture.supplyAsync(
            () -> lintAgent.probe(scopeId, summary,
                previousFindingsSummary, previousHealthSummary, rulesConfig,
                probeFocusPages, allPagesForStats),
            probeExecutor);
        CompletableFuture<List<SafetyNetFinding>> orphanFuture = CompletableFuture.supplyAsync(
            () -> detectOrphansBySql(scopeId, rulesConfig), probeExecutor);
        CompletableFuture<List<SafetyNetFinding>> staleFuture = CompletableFuture.supplyAsync(
            () -> detectStaleBySql(scopeId, rulesConfig), probeExecutor);
        CompletableFuture<List<SafetyNetFinding>> conflictFuture = CompletableFuture.supplyAsync(
            () -> detectConflictsBySql(scopeId, rulesConfig), probeExecutor);
        CompletableFuture<List<SafetyNetFinding>> refConflictFuture = CompletableFuture.supplyAsync(
            () -> detectReferenceConflictsBySql(scopeId, rulesConfig), probeExecutor);
        CompletableFuture<List<ContentDuplicateDetector.DuplicatePair>> contentDupFuture = CompletableFuture.supplyAsync(
            () -> contentDuplicateDetector.detectAll(scopeId),
            probeExecutor);

        ProbeResult aiResult;
        List<SafetyNetFinding> sqlOrphans, sqlStale, sqlConflicts, sqlRefConflicts;
        List<ContentDuplicateDetector.DuplicatePair> contentDuplicates;
        int probeSize = probeFocusPages != null ? probeFocusPages.size()
            : (allPagesForStats != null ? allPagesForStats.size() : 0);
        int timeoutSeconds = Math.min(PROBE_TIMEOUT_BASE_SECONDS + probeSize / 2, PROBE_TIMEOUT_MAX_SECONDS);
        try {
            CompletableFuture.allOf(aiFuture, orphanFuture, staleFuture, conflictFuture, refConflictFuture, contentDupFuture)
                .get(timeoutSeconds, TimeUnit.SECONDS);
            aiResult = aiFuture.get();
            sqlOrphans = orphanFuture.get();
            sqlStale = staleFuture.get();
            sqlConflicts = conflictFuture.get();
            sqlRefConflicts = refConflictFuture.get();
            contentDuplicates = contentDupFuture.get();
        } catch (Exception e) {
            log.warn("Parallel probe failed after {}s, harvesting completed futures and cancelling the rest: {}",
                timeoutSeconds, e.getMessage());
            boolean aiTimedOut = !aiFuture.isDone();
            for (CompletableFuture<?> f : List.of(aiFuture, orphanFuture, staleFuture, conflictFuture, refConflictFuture, contentDupFuture)) {
                if (!f.isDone()) f.cancel(true);
            }
            aiResult = aiFuture.isDone() && !aiFuture.isCancelled()
                ? aiFuture.getNow(ProbeResult.empty())
                : ProbeResult.withStatus(aiTimedOut ? "timeout" : "cancelled");
            sqlOrphans = orphanFuture.isDone() ? orphanFuture.getNow(Collections.emptyList()) : Collections.emptyList();
            sqlStale = staleFuture.isDone() ? staleFuture.getNow(Collections.emptyList()) : Collections.emptyList();
            sqlConflicts = conflictFuture.isDone() ? conflictFuture.getNow(Collections.emptyList()) : Collections.emptyList();
            sqlRefConflicts = refConflictFuture.isDone() ? refConflictFuture.getNow(Collections.emptyList()) : Collections.emptyList();
            contentDuplicates = contentDupFuture.isDone() ? contentDupFuture.getNow(Collections.emptyList()) : Collections.emptyList();
        }

        Map<String, Long> filePathToId;
        if (!aiResult.isEmpty()) {
            Set<String> aiPaths = aiResult.getFindings().stream()
                .map(f -> String.valueOf(f.getOrDefault("pagePath", "")))
                .filter(p -> !p.isEmpty() && !"null".equals(p))
                .collect(Collectors.toSet());
            filePathToId = buildFilePathToIdMapForPaths(scopeId, aiPaths);
        } else {
            filePathToId = Collections.emptyMap();
        }

        List<MergedFinding> merged = mergeFindings(aiResult, sqlOrphans, sqlStale, sqlConflicts, sqlRefConflicts, filePathToId, rulesConfig);
        merged = validateAiFindings(scopeId, merged, filePathToId);
        merged = appendContentDuplicateFindings(merged, contentDuplicates);
        merged = applyDowngradeFilter(merged, downgradedTypes, rulesConfig);

        Set<Long> touchedFindingIds = new HashSet<>();
        for (MergedFinding mf : merged) {
            Long findingId = lintFindingService.createFinding(scopeId, executionId, mf.type,
                mf.priority, mf.title, mf.detail, mf.pagePath, mf.assetId, mf.extra, rulesConfig);
            if (findingId != null) {
                touchedFindingIds.add(findingId);
            }
        }

        return new ProbeOutcome(merged, aiResult, touchedFindingIds);
    }

    private Map<String, Long> buildFilePathToIdMapForPaths(Long scopeId, Set<String> paths) {
        if (paths.isEmpty()) return Collections.emptyMap();
        List<Map<String, Object>> rows = wikiPageMapper.selectMaps(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getFilePath, paths)
                .select(WikiPageDO::getFilePath, WikiPageDO::getId)
        );
        Map<String, Long> map = new java.util.HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            String filePath = (String) row.get("file_path");
            Long id = ((Number) row.get("id")).longValue();
            if (filePath != null) {
                map.put(filePath, id);
            }
        }
        return map;
    }

    private List<SafetyNetFinding> detectOrphansBySql(Long scopeId, LintRulesConfig rulesConfig) {
        int minAgeDays = rulesConfig.getDiagnosticStandard().getOrphanMinAgeDays();
        LocalDateTime ageThreshold = LocalDateTime.now().minusDays(minAgeDays);
        String defaultPriority = rulesConfig.getDiagnosticRules().get("orphan").getDefaultPriority();

        List<Long> orphanIds = wikiPageMapper.selectOrphanCandidateIds(scopeId, ageThreshold);
        if (orphanIds.isEmpty()) return Collections.emptyList();

        List<SafetyNetFinding> results = new ArrayList<>();
        for (int i = 0; i < orphanIds.size(); i += BATCH_SIZE) {
            List<Long> batchIds = orphanIds.subList(i, Math.min(i + BATCH_SIZE, orphanIds.size()));
            List<Map<String, Object>> rows = wikiPageMapper.selectMaps(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .in(WikiPageDO::getId, batchIds)
                    .select(WikiPageDO::getId, WikiPageDO::getFilePath, WikiPageDO::getTitle)
            );
            for (Map<String, Object> row : rows) {
                Long id = ((Number) row.get("id")).longValue();
                String filePath = (String) row.get("file_path");
                String title = (String) row.get("title");
                results.add(new SafetyNetFinding("orphan", defaultPriority,
                    "孤立页面：「" + title + "」无入站链接（SQL确认）",
                    "该页面创建已超过" + minAgeDays + "天且没有任何其他页面链向它。",
                    filePath, id,
                    Map.of("inboundCount", 0, "source", "sql_safety_net")));
            }
        }
        return results;
    }

    private List<SafetyNetFinding> detectStaleBySql(Long scopeId, LintRulesConfig rulesConfig) {
        int highDays = rulesConfig.getStaleThresholds().getHighPriorityDays();
        int mediumDays = rulesConfig.getStaleThresholds().getMediumPriorityDays();

        List<Map<String, Object>> staleRows = wikiPageMapper.selectStaleCandidateRows(scopeId);
        if (staleRows.isEmpty()) return Collections.emptyList();

        List<SafetyNetFinding> results = new ArrayList<>();
        for (Map<String, Object> row : staleRows) {
            Long pageId = ((Number) row.get("page_id")).longValue();
            String filePath = (String) row.get("file_path");
            String title = (String) row.get("title");
            LocalDateTime contentUpdatedAt = toLocalDateTime(row.get("content_updated_at"));
            LocalDateTime newestSourceAt = toLocalDateTime(row.get("newest_source_at"));
            long staleDays = ((Number) row.get("stale_days")).longValue();

            String priority = staleDays > highDays ? "high" : staleDays > mediumDays ? "medium" : "low";
            results.add(new SafetyNetFinding("stale", priority,
                "过时声明：「" + title + "」内容落后于来源文件更新（SQL确认）",
                "页面内容更新于 " + contentUpdatedAt + "，来源文件最后修改于 " + newestSourceAt + "（滞后 " + staleDays + " 天）",
                filePath, pageId,
                Map.of("staleDays", staleDays, "source", "sql_safety_net")));
        }
        return results;
    }

    private List<SafetyNetFinding> detectConflictsBySql(Long scopeId, LintRulesConfig rulesConfig) {
        int minSharedKeywords = rulesConfig.getDiagnosticStandard().getCrossrefMinSharedKeywords();
        int limitCount = rulesConfig.getPartitionConfig().getPartitionSize();

        List<Map<String, Object>> candidatePairs = wikiPageMapper.selectConflictCandidatePairs(
            scopeId, minSharedKeywords, limitCount);
        if (candidatePairs.isEmpty()) return Collections.emptyList();

        List<SafetyNetFinding> results = new ArrayList<>();
        for (Map<String, Object> row : candidatePairs) {
            Long pageIdA = ((Number) row.get("page_id_a")).longValue();
            String filePathA = (String) row.get("file_path_a");
            String titleA = (String) row.get("title_a");
            Long pageIdB = ((Number) row.get("page_id_b")).longValue();
            String filePathB = (String) row.get("file_path_b");
            String titleB = (String) row.get("title_b");
            long sharedKeywords = ((Number) row.get("shared_keywords")).longValue();

            String pageBPriority = "low";
            String title = "潜在缺失交叉引用：共享" + sharedKeywords + "个关键词但无链接的页面对：「" + titleA + "」↔「" + titleB + "」（SQL探测）";
            String detail = "两个页面共享超过" + minSharedKeywords + "个关键词但彼此之间没有任何链接关系，"
                + "可能缺失交叉引用。页面A：「" + titleA + "」，页面B：「" + titleB + "」。";
            results.add(new SafetyNetFinding("missing_crossref", pageBPriority,
                title, detail, filePathA, pageIdA,
                Map.of("relatedPagePath", filePathB, "relatedPageId", pageIdB,
                    "sharedKeywords", sharedKeywords, "source", "sql_safety_net")));
        }
        return results;
    }

    private List<SafetyNetFinding> detectReferenceConflictsBySql(Long scopeId, LintRulesConfig rulesConfig) {
        int minSharedKeywords = rulesConfig.getDiagnosticStandard().getCrossrefMinSharedKeywords();
        int limitCount = rulesConfig.getPartitionConfig().getPartitionSize();

        List<Map<String, Object>> candidatePairs = wikiPageMapper.selectReferenceConflictPairs(
            scopeId, minSharedKeywords, limitCount);
        if (candidatePairs.isEmpty()) return Collections.emptyList();

        List<SafetyNetFinding> results = new ArrayList<>();
        for (Map<String, Object> row : candidatePairs) {
            Long pageIdA = ((Number) row.get("page_id_a")).longValue();
            String filePathA = (String) row.get("file_path_a");
            String titleA = (String) row.get("title_a");
            Long pageIdB = ((Number) row.get("page_id_b")).longValue();
            String filePathB = (String) row.get("file_path_b");
            String titleB = (String) row.get("title_b");
            long sharedKeywords = ((Number) row.get("shared_keywords")).longValue();

            String title = "同源参考页缺失交叉引用：「" + titleA + "」与「" + titleB + "」共享" + sharedKeywords + "个关键词但无链接";
            String detail = "同一源文档的两个参考页共享大量关键词但彼此没有链接关系，"
                + "可能存在结构性缺失，建议补充交叉引用说明。参考页A：「" + titleA + "」，参考页B：「" + titleB + "」。";
            results.add(new SafetyNetFinding("missing_crossref", "medium",
                title, detail, filePathA, pageIdA,
                Map.of("relatedPagePath", filePathB, "relatedPageId", pageIdB,
                    "relatedPageTitle", titleB,
                    "sharedKeywords", sharedKeywords, "source", "sql_reference_conflict")));
        }
        return results;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        return null;
    }

    private List<MergedFinding> mergeFindings(ProbeResult aiResult,
                                               List<SafetyNetFinding> sqlOrphans,
                                               List<SafetyNetFinding> sqlStale,
                                               List<SafetyNetFinding> sqlConflicts,
                                               List<SafetyNetFinding> sqlRefConflicts,
                                               Map<String, Long> filePathToId,
                                               LintRulesConfig rulesConfig) {
        List<MergedFinding> merged = new ArrayList<>();
        Set<String> aiOrphanPaths = new HashSet<>();
        Set<String> aiStalePaths = new HashSet<>();
        Set<String> aiConflictPaths = new HashSet<>();
        Set<String> aiMissingCrossrefPaths = new HashSet<>();

        if (!aiResult.isEmpty()) {
            for (Map<String, Object> f : aiResult.getFindings()) {
                String type = String.valueOf(f.getOrDefault("type", ""));
                if ("schema_violation".equalsIgnoreCase(type)) {
                    log.debug("Skipping schema_violation from AI probe - handled by SchemaPatchProposer flow");
                    continue;
                }
                String pagePath = String.valueOf(f.getOrDefault("pagePath", ""));
                String title = String.valueOf(f.getOrDefault("title", ""));
                String detail = String.valueOf(f.getOrDefault("detail", ""));
                String priority = resolvePriority(type, String.valueOf(f.getOrDefault("priority", "")), rulesConfig);
                Long assetId = filePathToId.getOrDefault(pagePath, null);
                Map<String, Object> extra = buildExtraFromAiFinding(f);

                if ("orphan".equalsIgnoreCase(type)) aiOrphanPaths.add(pagePath);
                if ("stale".equalsIgnoreCase(type)) aiStalePaths.add(pagePath);
                if ("conflict".equalsIgnoreCase(type)) aiConflictPaths.add(pagePath);
                if ("missing_crossref".equalsIgnoreCase(type)) aiMissingCrossrefPaths.add(pagePath);
                if ("schema_compliance".equalsIgnoreCase(type)) {
                    extra.put("violationType", f.getOrDefault("violationType", "unknown"));
                    extra.put("expectedStructure", f.getOrDefault("expectedStructure", ""));
                    extra.put("actualStructure", f.getOrDefault("actualStructure", ""));
                }
                if ("conflict".equalsIgnoreCase(type)) {
                    extra.put("conflictType", f.getOrDefault("conflictType", "value_conflict"));
                    extra.put("existingClaim", f.getOrDefault("existingClaim", ""));
                    extra.put("newClaim", f.getOrDefault("newClaim", ""));
                }

                merged.add(new MergedFinding(type, priority, title, detail, pagePath, assetId, extra, true));
            }
        }

        for (SafetyNetFinding sf : sqlOrphans) {
            if (!aiOrphanPaths.contains(sf.pagePath)) {
                merged.add(new MergedFinding(sf.type, sf.priority, sf.title, sf.detail,
                    sf.pagePath, sf.assetId, sf.extra, false));
            } else {
                log.debug("SQL orphan {} 已被AI探查覆盖，跳过重复", sf.pagePath);
            }
        }

        for (SafetyNetFinding sf : sqlStale) {
            if (!aiStalePaths.contains(sf.pagePath)) {
                merged.add(new MergedFinding(sf.type, sf.priority, sf.title, sf.detail,
                    sf.pagePath, sf.assetId, sf.extra, false));
            } else {
                log.debug("SQL stale {} 已被AI探查覆盖，跳过重复", sf.pagePath);
            }
        }

        for (SafetyNetFinding sf : sqlConflicts) {
            Set<String> dedupSet = "missing_crossref".equals(sf.type) ? aiMissingCrossrefPaths : aiConflictPaths;
            if (!dedupSet.contains(sf.pagePath)) {
                merged.add(new MergedFinding(sf.type, sf.priority, sf.title, sf.detail,
                    sf.pagePath, sf.assetId, sf.extra, false));
            } else {
                log.debug("SQL {} {} 已被AI探查覆盖，跳过重复", sf.type, sf.pagePath);
            }
        }

        for (SafetyNetFinding sf : sqlRefConflicts) {
            if (!aiMissingCrossrefPaths.contains(sf.pagePath)) {
                merged.add(new MergedFinding(sf.type, sf.priority, sf.title, sf.detail,
                    sf.pagePath, sf.assetId, sf.extra, false));
            } else {
                log.debug("SQL 参考页 {} 已被AI探查覆盖，跳过重复", sf.pagePath);
            }
        }

        return merged;
    }

    /**
     * Q2：对 AI 产出的诊断项做确定性反向校验，不满足事实前提的丢弃，避免幻觉落库。
     * orphan 要求入站链接为 0；missing_crossref 要求两页间双向均无链接。
     */
    private List<MergedFinding> validateAiFindings(Long scopeId, List<MergedFinding> merged, Map<String, Long> filePathToId) {
        if (merged.isEmpty()) return merged;
        List<MergedFinding> validated = new ArrayList<>(merged.size());
        int checked = 0;
        int dropped = 0;
        for (MergedFinding mf : merged) {
            if (!mf.fromAi || mf.assetId == null || checked >= AI_VALIDATION_MAX_PAGES) {
                validated.add(mf);
                continue;
            }
            if ("orphan".equalsIgnoreCase(mf.type)) {
                long inboundCount = wikiPageLinkMapper.selectCount(
                    new LambdaQueryWrapper<WikiPageLinkDO>()
                        .eq(WikiPageLinkDO::getScopeId, scopeId)
                        .eq(WikiPageLinkDO::getToPageId, mf.assetId));
                checked++;
                if (inboundCount > 0) {
                    dropped++;
                    log.info("AI orphan finding rejected by SQL validation: pageId={}, inboundLinks={}",
                        mf.assetId, inboundCount);
                    continue;
                }
            } else if ("missing_crossref".equalsIgnoreCase(mf.type)) {
                Long resolvedRelatedId = toLong(mf.extra.get("relatedPageId"));
                if (resolvedRelatedId == null && mf.extra.get("relatedPagePath") != null) {
                    resolvedRelatedId = filePathToId.get(String.valueOf(mf.extra.get("relatedPagePath")));
                }
                if (resolvedRelatedId != null) {
                    Long relatedPageId = resolvedRelatedId;
                    long linkCount = wikiPageLinkMapper.selectCount(
                        new LambdaQueryWrapper<WikiPageLinkDO>()
                            .eq(WikiPageLinkDO::getScopeId, scopeId)
                            .and(w -> w.and(inner -> inner
                                    .eq(WikiPageLinkDO::getFromPageId, mf.assetId)
                                    .eq(WikiPageLinkDO::getToPageId, relatedPageId))
                                .or(inner -> inner
                                    .eq(WikiPageLinkDO::getFromPageId, relatedPageId)
                                    .eq(WikiPageLinkDO::getToPageId, mf.assetId))));
                    checked++;
                    if (linkCount > 0) {
                        dropped++;
                        log.info("AI missing_crossref finding rejected by SQL validation: pageId={}, relatedPageId={}, links={}",
                            mf.assetId, relatedPageId, linkCount);
                        continue;
                    }
                }
            }
            validated.add(mf);
        }
        if (dropped > 0) {
            log.info("AI finding validation: checked={}, dropped={} (failed deterministic reverse check)", checked, dropped);
        }
        return validated;
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private List<MergedFinding> appendContentDuplicateFindings(List<MergedFinding> merged,
                                                                 List<ContentDuplicateDetector.DuplicatePair> contentDuplicates) {
        if (contentDuplicates == null || contentDuplicates.isEmpty()) return merged;

        Set<String> existingConflictPairs = new HashSet<>();
        for (MergedFinding mf : merged) {
            if ("conflict".equals(mf.type) || "missing_crossref".equals(mf.type)) {
                Object relatedPath = mf.extra.get("relatedPagePath");
                if (relatedPath != null && mf.pagePath != null) {
                    String key = mf.pagePath.compareTo(relatedPath.toString()) < 0
                        ? mf.pagePath + "|" + relatedPath : relatedPath + "|" + mf.pagePath;
                    existingConflictPairs.add(key);
                }
            }
        }

        int added = 0;
        for (ContentDuplicateDetector.DuplicatePair dup : contentDuplicates) {
            String pathA = dup.pageA().getFilePath();
            String pathB = dup.pageB().getFilePath();
            if (pathA == null || pathB == null) continue;

            String pairKey = pathA.compareTo(pathB) < 0 ? pathA + "|" + pathB : pathB + "|" + pathA;
            if (existingConflictPairs.contains(pairKey)) {
                log.debug("Content duplicate pair {} already covered by existing finding, skipping", pairKey);
                continue;
            }

            String simPct = String.format("%.0f", dup.similarity() * 100);
            String title = "内容重复：「" + dup.pageA().getTitle() + "」与「" + dup.pageB().getTitle()
                + "」内容相似度" + simPct + "%（确定性检测）";
            String detail = "两个页面内容高度相似（Jaccard " + simPct + "%），可能为重复知识。"
                + "页面A：「" + dup.pageA().getTitle() + "」，页面B：「" + dup.pageB().getTitle() + "」。";

            Map<String, Object> extra = new java.util.HashMap<>();
            extra.put("relatedPagePath", pathB);
            extra.put("relatedPageId", dup.pageB().getId());
            extra.put("relatedPageTitle", dup.pageB().getTitle());
            extra.put("conflictType", "content_duplication");
            extra.put("similarity", dup.similarity());
            extra.put("source", "content_duplicate_detector");

            merged.add(new MergedFinding("conflict", "high", title, detail,
                pathA, dup.pageA().getId(), extra, false));
            added++;
        }
        if (added > 0) {
            log.info("Lint content duplicate safety net: {} new duplicate pairs detected", added);
        }
        return merged;
    }

    private String resolvePriority(String type, String aiPriority, LintRulesConfig rulesConfig) {
        LintRulesConfig.DiagnosticRule rule = rulesConfig.getDiagnosticRules().get(type);
        if (rule == null) return "medium";
        if (aiPriority == null || aiPriority.isBlank() || "null".equals(aiPriority)) return rule.getDefaultPriority();
        return aiPriority.toLowerCase();
    }

    private Map<String, Object> buildExtraFromAiFinding(Map<String, Object> f) {
        Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("source", "ai_probe");
        if (f.containsKey("handlingMethod")) extra.put("handlingMethod", f.get("handlingMethod"));
        if (f.containsKey("confidence")) extra.put("confidence", f.get("confidence"));
        if (f.containsKey("suggestedAction")) extra.put("suggestedAction", f.get("suggestedAction"));
        if (f.containsKey("relatedPagePath")) extra.put("relatedPagePath", f.get("relatedPagePath"));
        if (f.containsKey("relatedPageId")) extra.put("relatedPageId", f.get("relatedPageId"));
        if (f.containsKey("relatedPageTitle")) extra.put("relatedPageTitle", f.get("relatedPageTitle"));
        if (f.containsKey("suggestedCategory")) extra.put("suggestedCategory", f.get("suggestedCategory"));
        return extra;
    }

    private String buildPreviousFindingsSummary(List<LintFindingDO> previousFindings, LintRulesConfig rulesConfig) {
        if (previousFindings == null || previousFindings.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        Map<String, Long> typeCounts = previousFindings.stream()
            .collect(Collectors.groupingBy(LintFindingDO::getFindingType, Collectors.counting()));
        Map<String, String> typeStatus = previousFindings.stream()
            .collect(Collectors.toMap(
                LintFindingDO::getFindingType,
                f -> f.getStatus() + (f.getUserFeedback() != null ? "(" + f.getUserFeedback() + ")" : ""),
                (a, b) -> a));

        sb.append("上次Lint各类型分布：\n");
        typeCounts.forEach((type, count) ->
            sb.append("- ").append(type).append(": ").append(count).append("个，状态 ").append(typeStatus.getOrDefault(type, "")).append("\n"));

        LintRulesConfig.FeedbackLearningConfig flConfig = rulesConfig.getFeedbackLearning();
        Map<String, Long> dismissCounts = previousFindings.stream()
            .filter(f -> "dismissed".equals(f.getStatus()) || "ignored".equals(f.getUserFeedback()))
            .collect(Collectors.groupingBy(LintFindingDO::getFindingType, Collectors.counting()));
        dismissCounts.forEach((type, count) -> {
            if (count >= flConfig.getDismissCountToDowngrade()) {
                sb.append("- 强制降级：").append(type).append("类型被用户连续忽略").append(count).append("次，探查敏感度已自动降低\n");
            }
        });

        return sb.toString();
    }

    private String buildPreviousHealthSummary(Map<String, Long> healthDistribution) {
        if (healthDistribution == null || healthDistribution.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("上次健康状态分布：\n");
        healthDistribution.forEach((status, count) -> sb.append("- ").append(status).append(": ").append(count).append("个页面\n"));
        return sb.toString();
    }

    private Set<String> computeDowngradedTypes(List<LintFindingDO> previousFindings, LintRulesConfig rulesConfig) {
        if (previousFindings == null || previousFindings.isEmpty()) return Collections.emptySet();
        LintRulesConfig.FeedbackLearningConfig flConfig = rulesConfig.getFeedbackLearning();
        int threshold = flConfig.getDismissCountToDowngrade();
        if (threshold <= 0) return Collections.emptySet();

        Map<String, Long> dismissCounts = previousFindings.stream()
            .filter(f -> "ignored".equals(f.getUserFeedback()) || "dismissed".equals(f.getStatus()))
            .collect(Collectors.groupingBy(LintFindingDO::getFindingType, Collectors.counting()));

        Set<String> downgraded = new HashSet<>();
        dismissCounts.forEach((type, count) -> {
            if (count >= threshold) {
                downgraded.add(type);
                log.info("类型 {} 达到强制降级阈值（被忽略 {} 次 >= {}），将降低探查敏感度", type, count, threshold);
            }
        });
        return downgraded;
    }

    private List<MergedFinding> applyDowngradeFilter(List<MergedFinding> merged, Set<String> downgradedTypes, LintRulesConfig rulesConfig) {
        if (downgradedTypes == null || downgradedTypes.isEmpty()) return merged;
        int downgradeLevels = rulesConfig.getFeedbackLearning().getDowngradePriorityLevels();
        List<MergedFinding> filtered = new ArrayList<>();
        int dropped = 0;
        int lowered = 0;
        for (MergedFinding mf : merged) {
            if (!downgradedTypes.contains(mf.type)) {
                filtered.add(mf);
                continue;
            }
            if ("low".equals(mf.priority)) {
                dropped++;
                log.debug("强制降级过滤：丢弃 {} 类型 low 优先级诊断项「{}」", mf.type, mf.title);
                continue;
            }
            String loweredPriority = lowerPriority(mf.priority, downgradeLevels);
            filtered.add(new MergedFinding(mf.type, loweredPriority, mf.title, mf.detail,
                mf.pagePath, mf.assetId, mf.extra, mf.fromAi));
            lowered++;
            log.debug("强制降级过滤：{} 类型优先级从 {} 降至 {}，诊断项「{}」", mf.type, mf.priority, loweredPriority, mf.title);
        }
        if (dropped > 0 || lowered > 0) {
            log.info("强制降级过滤生效：丢弃 {} 个，降级 {} 个诊断项", dropped, lowered);
        }
        return filtered;
    }

    private String lowerPriority(String priority, int levels) {
        if (levels <= 0) return priority;
        return switch (priority) {
            case "high" -> levels >= 2 ? "low" : "medium";
            case "medium" -> "low";
            case "low" -> "low";
            default -> priority;
        };
    }

    public static class ProbeOutcome {
        private final List<MergedFinding> mergedFindings;
        private final ProbeResult aiResult;
        private final Set<Long> touchedFindingIds;

        public ProbeOutcome(List<MergedFinding> mergedFindings, ProbeResult aiResult, Set<Long> touchedFindingIds) {
            this.mergedFindings = mergedFindings;
            this.aiResult = aiResult;
            this.touchedFindingIds = touchedFindingIds;
        }

        public List<MergedFinding> getMergedFindings() { return mergedFindings; }
        public ProbeResult getAiResult() { return aiResult; }
        public String getAiRawOutput() { return aiResult.getRawOutput(); }
        public Set<Long> getTouchedFindingIds() { return touchedFindingIds; }
    }

    public static class MergedFinding {
        public final String type;
        public final String priority;
        public final String title;
        public final String detail;
        public final String pagePath;
        public final Long assetId;
        public final Map<String, Object> extra;
        public final boolean fromAi;

        public MergedFinding(String type, String priority, String title, String detail,
                             String pagePath, Long assetId, Map<String, Object> extra, boolean fromAi) {
            this.type = type;
            this.priority = priority;
            this.title = title;
            this.detail = detail;
            this.pagePath = pagePath;
            this.assetId = assetId;
            this.extra = extra;
            this.fromAi = fromAi;
        }
    }

    private static class SafetyNetFinding {
        final String type;
        final String priority;
        final String title;
        final String detail;
        final String pagePath;
        final Long assetId;
        final Map<String, Object> extra;

        SafetyNetFinding(String type, String priority, String title, String detail,
                         String pagePath, Long assetId, Map<String, Object> extra) {
            this.type = type;
            this.priority = priority;
            this.title = title;
            this.detail = detail;
            this.pagePath = pagePath;
            this.assetId = assetId;
            this.extra = extra;
        }
    }
}