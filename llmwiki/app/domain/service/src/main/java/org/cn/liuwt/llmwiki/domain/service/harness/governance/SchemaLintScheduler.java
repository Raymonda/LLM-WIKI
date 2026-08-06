package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaConsistencyChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SchemaLint 独立调度器(AGENTS.md 《Schema 共治宪法·规则 7》)。
 * 独立于 Wiki Lint 周期跑,职责:
 * 1. 观察期补丁自动提升(P2-f)——避免 OBSERVING 永远观察。
 * 2. 观察期补丁超期归档——避免观察区成为永久垃圾堆。
 * 3. 健康度日志(P2-h)——堆积数、老化数、近期 accept/reject 比例。
 *
 * 提升规则(任一命中即升级):
 * A. 共识聚合:同 (sectionTitle, operation) 分组累计 >= CLUSTER_SIZE 条 OBSERVING,
 *    且合并后去重 evidence >= EVIDENCE_THRESHOLD 条 → 挑最新一条为代表升 PENDING,其余 SUPERSEDED。
 * B. 老化高置信:单条 confidence >= AGING_CONF 且 evidence 数 >= AGING_EVI 且停留 >= AGING_DAYS 天 → 单独升级。
 * C. 超期低置信归档:单条 confidence < EXPIRE_CONF 且停留 >= EXPIRE_DAYS 天 → 标记 EXPIRED。
 */
@Service
public class SchemaLintScheduler {

    private static final Logger log = LoggerFactory.getLogger(SchemaLintScheduler.class);

    // 条件 A:聚合升级阈值
    private static final int CLUSTER_SIZE = 2;
    private static final int EVIDENCE_THRESHOLD = 3;
    
    // 条件 B:老化高置信升级阈值
    private static final BigDecimal AGING_CONF = new BigDecimal("0.75");
    private static final int AGING_EVI = 3;
    private static final long AGING_DAYS = 5;
    
    // 条件 C:超期低置信归档阈值
    private static final BigDecimal EXPIRE_CONF = new BigDecimal("0.60");
    private static final long EXPIRE_DAYS = 30;

    // 条件 D:最大观察窗口——无论置信度，停留超过此天数且证据不足则自动归档
    private static final long MAX_OBSERVE_DAYS = 14;
    private static final int MAX_OBSERVE_EVIDENCE = 2;
    
    // 告警阈值
    private static final long WARN_AGING_DAYS = 30;

    @Autowired
    private SchemaPatchService schemaPatchService;

    @Autowired
    private SchemaConsistencyChecker schemaConsistencyChecker;

    @Autowired
    private SchemaManager schemaManager;

    @Value("${llmwiki.schema-lint.enabled:true}")
    private boolean enabled;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认每小时 30 分跑一次，可通过 llmwiki.schema-lint.cron 覆盖。 */
    @Scheduled(cron = "${llmwiki.schema-lint.cron:0 30 * * * ?}")
    public void runSchemaLint() {
        if (!enabled) {
            log.debug("SchemaLint 调度已关闭，跳过本轮");
            return;
        }
        List<Long> scopes;
        try {
            scopes = schemaPatchService.listScopesWithObserving();
        } catch (Exception e) {
            log.warn("SchemaLint 拉取 scope 列表失败: {}", e.getMessage());
            return;
        }
        if (scopes.isEmpty()) {
            log.debug("SchemaLint 本轮无 OBSERVING 补丁，跳过");
            return;
        }
        for (Long scopeId : scopes) {
            try {
                runForScope(scopeId);
            } catch (Exception e) {
                log.warn("SchemaLint scope={} 处理异常: {}", scopeId, e.getMessage());
            }
        }
    }

    /** 每周日凌晨 3:00 执行 Schema 一致性扫描（规则 7）。扫描所有有 Schema 的 scope，而非仅限有 OBSERVING 补丁的 scope。 */
    @Scheduled(cron = "${llmwiki.schema-lint.consistency-cron:0 0 3 * * SUN}")
    public void runConsistencyScan() {
        if (!enabled) {
            log.debug("SchemaConsistencyScan 调度已关闭，跳过本轮");
            return;
        }
        List<Long> scopes;
        try {
            scopes = schemaManager.listAllScopeIdsWithSchema();
        } catch (Exception e) {
            log.warn("SchemaConsistencyScan 拉取 scope 列表失败: {}", e.getMessage());
            return;
        }
        log.info("SchemaConsistencyScan 开始，共 {} 个 scope 有 Schema", scopes.size());
        for (Long scopeId : scopes) {
            try {
                String report = schemaConsistencyChecker.runConsistencyCheck(scopeId);
                if (report != null) {
                    log.info("SchemaConsistencyCheck scope={}: {}", scopeId,
                        report.length() > 200 ? report.substring(0, 200) + "..." : report);
                }
            } catch (Exception e) {
                log.warn("SchemaConsistencyCheck scope={} 异常: {}", scopeId, e.getMessage());
            }
        }
        log.info("SchemaConsistencyScan 完成");
    }

    private void runForScope(Long scopeId) {
        List<SchemaPatchDO> observing = schemaPatchService.listObservingRaw(scopeId);
        if (observing.isEmpty()) return;
    
        log.info("====== SchemaLint 调度开始 scope={} 观察期补丁数={} ======", scopeId, observing.size());
    
        // 聚合:按 (sectionTitle|operation) 分组
        Map<String, List<SchemaPatchDO>> groups = new LinkedHashMap<>();
        for (SchemaPatchDO p : observing) {
            String key = safe(p.getSectionTitle()) + "||" + safe(p.getOperation());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
    
        int promotedByCluster = 0;
        int promotedByAging = 0;
        int expiredCount = 0;
        int expiredByMaxWindow = 0;
        Set<Long> processedIds = new HashSet<>();
    
        // 条件 A:聚合升级
        for (Map.Entry<String, List<SchemaPatchDO>> entry : groups.entrySet()) {
            List<SchemaPatchDO> list = entry.getValue();
            if (list.size() < CLUSTER_SIZE) continue;
            int evidenceCount = countUniqueEvidence(list);
            if (evidenceCount < EVIDENCE_THRESHOLD) continue;
            SchemaPatchDO representative = pickRepresentative(list);
            String reason = String.format("聚合%d条观察期证据(去重%d条)自动提升", list.size(), evidenceCount);
            schemaPatchService.autoPromote(representative, list, reason);
            promotedByCluster++;
            processedIds.add(representative.getId());
            list.forEach(p -> processedIds.add(p.getId()));
        }
    
        // 条件 B:老化高置信单条升级(跳过已经被聚合处理掉的)
        LocalDateTime now = LocalDateTime.now();
        for (SchemaPatchDO p : observing) {
            if (processedIds.contains(p.getId())) continue;
            if (p.getConfidence() == null) continue;
            if (p.getConfidence().compareTo(AGING_CONF) < 0) continue;
            int evi = countEvidence(p);
            if (evi < AGING_EVI) continue;
            if (p.getCreatedAt() == null) continue;
            long days = Duration.between(p.getCreatedAt(), now).toDays();
            if (days < AGING_DAYS) continue;
            String reason = String.format("高置信%.2f+%d证据且观察满%d天自动提升",
                p.getConfidence(), evi, days);
            schemaPatchService.autoPromote(p, List.of(p), reason);
            promotedByAging++;
            processedIds.add(p.getId());
        }
    
        // 条件 C:超期低置信自动归档
        for (SchemaPatchDO p : observing) {
            if (processedIds.contains(p.getId())) continue;
            if (p.getConfidence() == null) continue;
            if (p.getConfidence().compareTo(EXPIRE_CONF) >= 0) continue;
            if (p.getCreatedAt() == null) continue;
            long days = Duration.between(p.getCreatedAt(), now).toDays();
            if (days < EXPIRE_DAYS) continue;
                
            String reason = String.format("观察满%d天且置信度%.2f低于阈值%.2f,自动归档", 
                days, p.getConfidence(), EXPIRE_CONF);
            schemaPatchService.expirePatch(p, reason);
            expiredCount++;
            processedIds.add(p.getId());
        }
    
        // 条件 D:最大观察窗口——停留超过14天且证据不足(未达到聚合/老化条件)自动归档
        for (SchemaPatchDO p : observing) {
            if (processedIds.contains(p.getId())) continue;
            if (p.getCreatedAt() == null) continue;
            long days = Duration.between(p.getCreatedAt(), now).toDays();
            if (days < MAX_OBSERVE_DAYS) continue;
            int evi = countEvidence(p);
            if (evi >= MAX_OBSERVE_EVIDENCE) continue;
            String reason = String.format("观察满%d天且证据不足(%d条<%d),自动归档",
                days, evi, MAX_OBSERVE_EVIDENCE);
            schemaPatchService.expirePatch(p, reason);
            expiredByMaxWindow++;
            processedIds.add(p.getId());
        }

        // 健康日志:堆积数、老化超 30 天未处置数
        int remaining = observing.size() - processedIds.size();
        int ageingOver30 = 0;
        for (SchemaPatchDO p : observing) {
            if (processedIds.contains(p.getId())) continue;
            if (p.getCreatedAt() == null) continue;
            long days = Duration.between(p.getCreatedAt(), now).toDays();
            if (days >= WARN_AGING_DAYS) ageingOver30++;
        }
            
        log.info("====== SchemaLint 调度完成 scope={} 观察总数={} 提升(聚合/老化)={}/{} 过期归档(低置信/超时)={}/{} 剩余观察={} 超30天未处置={} ======",
            scopeId, observing.size(), promotedByCluster, promotedByAging, 
            expiredCount, expiredByMaxWindow, remaining, ageingOver30);
            
        if (ageingOver30 > 0) {
            log.warn("SchemaLint scope={} 存在 {} 条 OBSERVING 补丁停留超过 {} 天未处置,建议人工审查",
                scopeId, ageingOver30, WARN_AGING_DAYS);
        }
    }

    private SchemaPatchDO pickRepresentative(List<SchemaPatchDO> list) {
        SchemaPatchDO best = list.get(0);
        for (SchemaPatchDO p : list) {
            if (p.getCreatedAt() == null) continue;
            if (best.getCreatedAt() == null || p.getCreatedAt().isAfter(best.getCreatedAt())) {
                best = p;
            }
        }
        return best;
    }

    private int countUniqueEvidence(List<SchemaPatchDO> list) {
        Set<String> uniq = new HashSet<>();
        for (SchemaPatchDO p : list) {
            List<String> arr = readEvidence(p);
            for (String s : arr) {
                if (s != null && !s.isBlank()) uniq.add(s.trim());
            }
        }
        return uniq.size();
    }

    private int countEvidence(SchemaPatchDO p) {
        return readEvidence(p).size();
    }

    private List<String> readEvidence(SchemaPatchDO p) {
        if (p.getEvidenceJson() == null || p.getEvidenceJson().isBlank()) return List.of();
        try {
            JsonNode arr = objectMapper.readTree(p.getEvidenceJson());
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                out.add(n.isTextual() ? n.asText() : n.toString());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
