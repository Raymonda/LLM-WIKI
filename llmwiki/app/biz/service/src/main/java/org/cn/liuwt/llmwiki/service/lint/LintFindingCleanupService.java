package org.cn.liuwt.llmwiki.service.lint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintRepairSnapshotService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class LintFindingCleanupService {

    private static final Logger log = LoggerFactory.getLogger(LintFindingCleanupService.class);

    private static final int AUTO_RESOLVED_RETENTION_DAYS = 30;
    private static final int DISMISSED_RETENTION_DAYS = 30;
    private static final int ROLLED_BACK_RETENTION_DAYS = 30;
    private static final int RESOLVED_RETENTION_DAYS = 90;
    private static final int DEFERRED_RETENTION_DAYS = 90;
    private static final int OPEN_DEFER_DAYS = 30;
    private static final int AWAITING_APPROVAL_EXPIRE_DAYS = 7;
    private static final int BATCH_SIZE = 1000;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private LintRepairSnapshotService lintRepairSnapshotService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupArchivedFindings() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> scopeIds = wikiPageMapper.selectDistinctScopeIds();

        for (Long scopeId : scopeIds) {
            cleanupByStatusAndScope("auto_resolved", now.minusDays(AUTO_RESOLVED_RETENTION_DAYS), scopeId);
            cleanupByStatusAndScope("dismissed", now.minusDays(DISMISSED_RETENTION_DAYS), scopeId);
            cleanupByStatusAndScope("rolled_back", now.minusDays(ROLLED_BACK_RETENTION_DAYS), scopeId);
            cleanupByStatusAndScope("resolved", now.minusDays(RESOLVED_RETENTION_DAYS), scopeId);
            cleanupByStatusAndScope("deferred", now.minusDays(DEFERRED_RETENTION_DAYS), scopeId);
        }
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void deferStaleOpenFindings() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(OPEN_DEFER_DAYS);
        List<Long> scopeIds = wikiPageMapper.selectDistinctScopeIds();

        for (Long scopeId : scopeIds) {
            int scopeDeferred = 0;
            int batchSize;
            do {
                LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getScopeId, scopeId)
                    .eq(LintFindingDO::getStatus, "open")
                    .isNotNull(LintFindingDO::getCreatedAt)
                    .lt(LintFindingDO::getCreatedAt, threshold)
                    .last("LIMIT " + BATCH_SIZE);
                List<LintFindingDO> batch = lintFindingMapper.selectList(wrapper);
                batchSize = batch.size();

                for (LintFindingDO f : batch) {
                    Map<String, Object> extraMap = lintRepairSnapshotService.readExtra(f.getExtra());
                    extraMap.put("deferredReason", "stale_age");
                    extraMap.put("deferredAt", LocalDateTime.now().toString());
                    try {
                        lintFindingMapper.update(null, new LambdaUpdateWrapper<LintFindingDO>()
                            .eq(LintFindingDO::getId, f.getId())
                            .eq(LintFindingDO::getStatus, "open")
                            .set(LintFindingDO::getStatus, "deferred")
                            .set(LintFindingDO::getExtra, objectMapper.writeValueAsString(extraMap))
                            .set(LintFindingDO::getArchivedAt, LocalDateTime.now())
                        );
                    } catch (Exception e) {
                        log.warn("Failed to defer finding id={}: {}", f.getId(), e.getMessage());
                    }
                }
                scopeDeferred += batchSize;
            } while (batchSize > 0);

            if (scopeDeferred > 0) {
                log.info("Deferred {} stale open findings (older than {} days) for scopeId={}",
                    scopeDeferred, OPEN_DEFER_DAYS, scopeId);
                try {
                    notificationService.createScopeNotification(scopeId, "findings_deferred",
                        "诊断自动延期",
                        "有 " + scopeDeferred + " 条超过 " + OPEN_DEFER_DAYS + " 天未处理的诊断已自动转为已延期，不再占用待处理列表；若问题仍然存在，下次体检会重新诊断。",
                        null, null);
                } catch (Exception e) {
                    log.warn("Failed to create findings_deferred notification for scope {}", scopeId);
                }
            }
        }
    }

    @Scheduled(cron = "0 30 9 * * *")
    public void expireAwaitingApprovalFindings() {
        LocalDateTime expireThreshold = LocalDateTime.now().minusDays(AWAITING_APPROVAL_EXPIRE_DAYS);
        List<Long> scopeIds = wikiPageMapper.selectDistinctScopeIds();
        int totalExpired = 0;

        for (Long scopeId : scopeIds) {
            int expired;
            do {
                LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<LintFindingDO>()
                    .eq(LintFindingDO::getScopeId, scopeId)
                    .eq(LintFindingDO::getStatus, "awaiting_approval")
                    .lt(LintFindingDO::getUpdatedAt, expireThreshold)
                    .last("LIMIT " + BATCH_SIZE);
                List<LintFindingDO> findings = lintFindingMapper.selectList(wrapper);
                expired = findings.size();
                
                for (LintFindingDO f : findings) {
                    lintFindingMapper.update(null, new LambdaUpdateWrapper<LintFindingDO>()
                        .eq(LintFindingDO::getId, f.getId())
                        .set(LintFindingDO::getStatus, "open")
                    );
                    if (f.getAssetId() != null) {
                        try {
                            WikiPageDO page = wikiPageMapper.selectById(f.getAssetId());
                            if (page != null && !"needs-update".equals(page.getHealthStatus())) {
                                wikiPageMapper.update(null, new LambdaUpdateWrapper<WikiPageDO>()
                                    .eq(WikiPageDO::getId, f.getAssetId())
                                    .set(WikiPageDO::getHealthStatus, "needs-update")
                                );
                            }
                        } catch (Exception e) {
                            log.warn("Failed to update health_status for page {}", f.getAssetId());
                        }
                    }
                }
                totalExpired += expired;
            } while (expired > 0);
        }

        if (totalExpired > 0) {
            log.info("Expired {} awaiting_approval findings back to open status", totalExpired);
            for (Long scopeId : scopeIds) {
                try {
                    notificationService.createScopeNotification(scopeId, "awaiting_expired",
                        "裁决请求过期提醒",
                        "有 " + totalExpired + " 条裁决请求因超过 7 天未处理已自动转回待处理状态，请及时处理。",
                        null, null);
                } catch (Exception e) {
                    log.warn("Failed to create awaiting_expired notification for scope {}", scopeId);
                }
            }
        }
    }

    private void cleanupByStatusAndScope(String status, LocalDateTime retentionEnd, Long scopeId) {
        int totalDeleted = 0;
        int deleted;
        do {
            LambdaQueryWrapper<LintFindingDO> wrapper = new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .eq(LintFindingDO::getStatus, status)
                .isNotNull(LintFindingDO::getArchivedAt)
                .lt(LintFindingDO::getArchivedAt, retentionEnd)
                .last("LIMIT " + BATCH_SIZE);
            List<LintFindingDO> batch = lintFindingMapper.selectList(wrapper);
            if (batch.isEmpty()) {
                break;
            }
            for (LintFindingDO f : batch) {
                deleteSnapshotIfPresent(f);
            }
            deleted = lintFindingMapper.deleteBatchIds(batch.stream().map(LintFindingDO::getId).toList());
            totalDeleted += deleted;
        } while (deleted > 0);

        if (totalDeleted > 0) {
            log.info("Cleaned up {} archived lint_finding records with status={}, scopeId={}, retentionDays={}",
                totalDeleted, status, scopeId, retentionEnd);
        }
    }

    private void deleteSnapshotIfPresent(LintFindingDO finding) {
        try {
            Object snapshotPath = lintRepairSnapshotService.readExtra(finding.getExtra()).get("snapshotPath");
            if (snapshotPath instanceof String path && !path.isBlank()) {
                lintRepairSnapshotService.deleteSnapshot(finding.getScopeId(), path);
            }
        } catch (Exception e) {
            log.warn("Failed to delete snapshot for finding id={}: {}", finding.getId(), e.getMessage());
        }
    }
}