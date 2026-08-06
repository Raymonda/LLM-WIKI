package org.cn.liuwt.llmwiki.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.edit.CompileSaveOrchestrator;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.DraftService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面保存后异步处理服务。
 * 由 PipelineTaskConsumer 消费 PAGE_SAVE_POST 消息时调用。
 *
 * 状态机: pending → processing → completed/partial/failed
 * - completed: 全部步骤成功
 * - partial: 摘要成功，非关键步骤有失败
 * - failed: 摘要生成失败（重试耗尽后由 Consumer 标记）
 */
@Service
public class PageSavePostService {

    private static final Logger log = LoggerFactory.getLogger(PageSavePostService.class);

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private CompileSaveOrchestrator compileSaveOrchestrator;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private StorageProvider storageProvider;

    /**
     * 异步后处理入口：摘要生成 → Schema校验 → 链接同步 → 完整ES索引。
     *
     * 关键步骤（摘要生成）失败时抛出 RuntimeException，触发 Consumer 重试。
     * 非关键步骤（Schema/链接/ES）失败时记录 warn，最终标记 partial。
     *
     * @throws RuntimeException 摘要生成失败时抛出，触发重试
     */
    public void executePostSave(PipelineTaskMessage msg) {
        Long scopeId = msg.getScopeId();
        Long pageId = msg.getPageId();
        String contentHash = msg.getContentHash();

        // Step 1: 从 DB 读取最新 page
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, pageId)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            log.warn("PAGE_SAVE_POST: page not found, skipping: scopeId={}, pageId={}", scopeId, pageId);
            return;
        }

        // 幂等校验
        if (!checkIdempotency(pageDO, contentHash, pageId)) {
            return;
        }

        String title = pageDO.getTitle();
        String content = msg.getContent() != null ? msg.getContent() : "";
        String category = msg.getCategory();

        log.info("PAGE_SAVE_POST: starting async processing: scopeId={}, pageId={}, title={}",
            scopeId, pageId, title);

        // 标记为 processing
        updatePostSaveStatus(pageId, STATUS_PROCESSING, null);

        // 跟踪非关键步骤的成功/失败
        boolean schemaOk = true;
        boolean linkOk = true;
        boolean indexOk = true;

        // Step 2: AI 摘要生成 — 关键步骤，失败则抛异常触发重试
        String summary;
        try {
            summary = compileSaveOrchestrator.generateSummary(scopeId, title, content);
        } catch (Exception e) {
            log.error("PAGE_SAVE_POST: summary generation failed, will retry: pageId={}",
                pageId, e);
            updatePostSaveStatus(pageId, STATUS_FAILED, "摘要生成失败: " + truncateError(e.getMessage()));
            throw new RuntimeException("PAGE_SAVE_POST: summary generation failed for pageId=" + pageId, e);
        }

        if (summary != null && !summary.isBlank()) {
            pageDO.setSummary(summary);
            pageDO.setPostSaveStatus(null); // 临时清除，后面统一更新
            wikiPageMapper.updateById(pageDO);
            log.info("PAGE_SAVE_POST: summary generated and saved: pageId={}", pageId);
        } else {
            log.warn("PAGE_SAVE_POST: summary generation returned empty, will retry: pageId={}", pageId);
            updatePostSaveStatus(pageId, STATUS_FAILED, "摘要生成返回空结果");
            throw new RuntimeException("PAGE_SAVE_POST: summary generation returned empty for pageId=" + pageId);
        }

        // Step 3: Schema 合规校验 → 有违规则写入 lint_finding（非关键）
        try {
            var compliance = compileSaveOrchestrator.runSchemaCheck(scopeId, title, content, category);
            if (compliance.hasViolations()) {
                for (var violation : compliance.violations()) {
                    String detail = "Schema 校验违规: " + violation.description();
                    lintFindingService.createFinding(
                        scopeId, null, "schema_compliance", "low",
                        "页面 Schema 违规：" + title,
                        detail,
                        pageDO.getFilePath(),
                        pageDO.getId(),
                        Map.of("violationType", violation.violationType().name(),
                               "severity", violation.severity().name())
                    );
                }
                log.info("PAGE_SAVE_POST: schema violations recorded: pageId={}, count={}",
                    pageId, compliance.violations().size());
            }
        } catch (Exception e) {
            schemaOk = false;
            log.warn("PAGE_SAVE_POST: schema check failed (non-critical): pageId={}", pageId, e);
        }

        // Step 4: 链接同步（sanitizeWikiLinks + syncContentLinks）（非关键）
        String sanitizedContent = content;
        try {
            sanitizedContent = linkWritingService.sanitizeWikiLinks(content, scopeId);
            Map<Long, String> pageIdToContent = Map.of(pageId, sanitizedContent);
            linkWritingService.syncContentLinks(scopeId, pageIdToContent, null);
            log.debug("PAGE_SAVE_POST: links synced: pageId={}", pageId);
        } catch (Exception e) {
            linkOk = false;
            log.warn("PAGE_SAVE_POST: link sync failed (non-critical): pageId={}", pageId, e);
        }

        // Step 5: 如果内容被 sanitize 修改，更新 DB content + 文件 + contentHash
        if (!sanitizedContent.equals(content)) {
            try {
                String scopeIdStr = String.valueOf(scopeId);
                storageProvider.write(scopeIdStr, "wiki/" + pageDO.getFilePath(),
                    sanitizedContent.getBytes(StandardCharsets.UTF_8));

                // 重新计算 contentHash，保持幂等一致性
                String newHash = DraftService.computeHash(sanitizedContent);
                pageDO.setContentHash(newHash);
                pageDO.setContentUpdatedAt(LocalDateTime.now());
                wikiPageMapper.updateById(pageDO);

                log.info("PAGE_SAVE_POST: content sanitized, file updated, contentHash recalculated: pageId={}, newHash={}",
                    pageId, newHash);
                content = sanitizedContent; // 用于后续 ES 索引
            } catch (Exception e) {
                linkOk = false; // 归入链接步骤失败
                log.warn("PAGE_SAVE_POST: failed to update sanitized content: pageId={}", pageId, e);
            }
        }

        // Step 6: 完整 ES 索引（含 summary + 清理后的链接）（非关键）
        try {
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
            log.info("PAGE_SAVE_POST: full search index updated: pageId={}", pageId);
        } catch (Exception e) {
            indexOk = false;
            log.warn("PAGE_SAVE_POST: search index update failed (non-critical): pageId={}", pageId, e);
        }

        // Step 7: 根据各步骤结果设置最终状态
        String finalStatus;
        String finalError = null;
        if (schemaOk && linkOk && indexOk) {
            finalStatus = STATUS_COMPLETED;
        } else {
            finalStatus = STATUS_PARTIAL;
            StringBuilder errors = new StringBuilder();
            if (!schemaOk) errors.append("Schema校验失败; ");
            if (!linkOk) errors.append("链接同步失败; ");
            if (!indexOk) errors.append("ES索引更新失败; ");
            finalError = errors.toString();
        }

        updatePostSaveStatus(pageId, finalStatus, finalError);
        log.info("PAGE_SAVE_POST: async processing completed: scopeId={}, pageId={}, status={}",
            scopeId, pageId, finalStatus);
    }

    /**
     * 标记页面异步处理失败（死信场景，由 Consumer 调用）。
     */
    public void markPageFailed(Long scopeId, Long pageId, String errorMessage) {
        try {
            updatePostSaveStatus(pageId, STATUS_FAILED, truncateError(errorMessage));
            log.error("PAGE_SAVE_POST: page marked as dead-letter failed: scopeId={}, pageId={}, error={}",
                scopeId, pageId, errorMessage);
        } catch (Exception e) {
            log.error("PAGE_SAVE_POST: failed to mark page as failed (markPageFailed): pageId={}", pageId, e);
        }
    }

    // --- 私有方法 ---

    /**
     * 幂等校验：
     * - contentHash 不匹配 → 跳过（用户又编辑了新版本，新消息会处理）
     * - postSaveStatus='completed' 且 contentHash 匹配 → 跳过（重复投递，已处理成功）
     * - postSaveStatus='processing' 且 contentHash 匹配 → 继续（上次中断，需要重试）
     * - postSaveStatus='failed' 且 contentHash 匹配 → 继续（上次失败，允许重试）
     *
     * @return true 继续执行，false 跳过
     */
    private boolean checkIdempotency(WikiPageDO pageDO, String msgContentHash, Long pageId) {
        String dbHash = pageDO.getContentHash();
        String dbStatus = pageDO.getPostSaveStatus();

        // contentHash 不匹配：用户已编辑新版本，跳过旧消息
        if (msgContentHash != null && !msgContentHash.equals(dbHash)) {
            log.info("PAGE_SAVE_POST: contentHash mismatch (stale message), skipping: pageId={}, msgHash={}, dbHash={}",
                pageId, msgContentHash, dbHash);
            return false;
        }

        // contentHash 匹配 + 已完成 → 重复投递，跳过
        if (STATUS_COMPLETED.equals(dbStatus) && msgContentHash != null && msgContentHash.equals(dbHash)) {
            log.info("PAGE_SAVE_POST: already completed for this contentHash, skipping duplicate: pageId={}", pageId);
            return false;
        }

        return true;
    }

    private void updatePostSaveStatus(Long pageId, String status, String error) {
        try {
            WikiPageDO update = new WikiPageDO();
            update.setId(pageId);
            update.setPostSaveStatus(status);
            update.setPostSaveError(error);
            update.setUpdatedAt(LocalDateTime.now());
            wikiPageMapper.updateById(update);
        } catch (Exception e) {
            log.error("PAGE_SAVE_POST: failed to update postSaveStatus to '{}': pageId={}", status, pageId, e);
        }
    }

    private String truncateError(String error) {
        if (error == null) return null;
        return error.length() > 900 ? error.substring(0, 900) + "..." : error;
    }
}
