package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SavePageRequest;
import org.cn.liuwt.llmwiki.facade.model.WikiPageInfo;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 页面保存服务。
 * 同步快速路径：校验 → DB → 文件 → 基础ES → 清理 → 发MQ → 返回。
 * AI 操作（摘要/Schema校验/链接同步）通过 MQ 异步执行。
 */
@Service
public class PageSaveService {

    private static final Logger log = LoggerFactory.getLogger(PageSaveService.class);

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private DraftService draftService;

    @Autowired
    private EditSessionService editSessionService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired(required = false)
    private PostSaveTaskPublisher postSaveTaskPublisher;

    /**
     * 统一保存入口。
     * 不使用 @Transactional，各阶段自行管理事务边界。
     */
    public WikiPageInfo save(Long scopeId, Long userId, SavePageRequest request) {
        String saveMode = normalizeSaveMode(request.getSaveMode());

        return switch (saveMode) {
            case "draft" -> saveDraft(scopeId, userId, request);
            case "save" -> savePublished(scopeId, userId, request);
            default -> throw new BusinessException(ErrorCode.WIKI_INVALID_SAVE_MODE, saveMode);
        };
    }

    /**
     * Schema 预校验（不写入，仅返回校验结果）。
     */
    public Map<String, Object> preValidate(Long scopeId, String title, String content, String category) {
        Map<String, Object> result = new HashMap<>();
        try {
            String metadataJson = buildMetadataJson(title, category);
            Map<String, String> pageContents = Map.of(title != null ? title : "untitled", content != null ? content : "");
            ComplianceResult compliance = schemaComplianceChecker.check(scopeId, metadataJson, pageContents);

            result.put("valid", !compliance.hasViolations());
            result.put("requiresReview", compliance.requiresReview());
            if (compliance.hasViolations()) {
                result.put("violations", compliance.violations().stream()
                    .map(v -> Map.of(
                        "type", v.violationType().name(),
                        "description", v.description(),
                        "severity", v.severity().name()
                    )).toList());
            } else {
                result.put("violations", List.of());
            }
        } catch (Exception e) {
            log.warn("Schema pre-validation failed: scopeId={}, title={}", scopeId, title, e);
            result.put("valid", true);
            result.put("requiresReview", false);
            result.put("violations", List.of());
            result.put("warning", "校验异常，已跳过: " + e.getMessage());
        }
        return result;
    }

    // --- 私有方法 ---

    private String normalizeSaveMode(String saveMode) {
        if (saveMode == null || saveMode.isBlank()) return "save";
        return switch (saveMode) {
            case "draft" -> "draft";
            case "direct", "compile", "save" -> "save";
            default -> saveMode;
        };
    }

    private WikiPageInfo saveDraft(Long scopeId, Long userId, SavePageRequest request) {
        if (request.getDraftId() != null) {
            var updateReq = new org.cn.liuwt.llmwiki.facade.model.UpdateDraftRequest();
            updateReq.setTitle(request.getTitle());
            updateReq.setContent(request.getContent());
            updateReq.setCategory(request.getCategory());
            updateReq.setTags(request.getTags());
            var info = draftService.updateDraft(request.getDraftId(), scopeId, updateReq);
            return toPageInfo(info.getTitle(), info.getContent(), info.getCategory());
        } else {
            var createReq = new org.cn.liuwt.llmwiki.facade.model.CreateDraftRequest();
            createReq.setTitle(request.getTitle());
            createReq.setContent(request.getContent());
            createReq.setCategory(request.getCategory());
            createReq.setTags(request.getTags());
            createReq.setPageId(request.getPageId());
            var info = draftService.createDraft(scopeId, userId, createReq);
            return toPageInfo(info.getTitle(), info.getContent(), info.getCategory());
        }
    }

    /**
     * 同步快速路径：校验 → DB → 文件 → 基础ES → 清理 → 发MQ → 返回。
     * AI 操作（摘要/增强/Schema校验/链接同步）全部异步执行。
     */
    private WikiPageInfo savePublished(Long scopeId, Long userId, SavePageRequest request) {
        String title = request.getTitle();
        String content = request.getContent();
        String category = request.getCategory();

        if (title == null || title.isBlank()) {
            throw new BusinessException(ErrorCode.WIKI_TITLE_REQUIRED);
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.WIKI_CONTENT_REQUIRED);
        }

        // Step 1: 变更检测 — 预加载 existingPage
        String contentHash = DraftService.computeHash(content);
        WikiPageDO existingPage = null;
        String existingSummary = "";

        if (request.getPageId() != null) {
            existingPage = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getId, request.getPageId())
                    .eq(WikiPageDO::getScopeId, scopeId)
            );
            if (existingPage != null) {
                existingSummary = existingPage.getSummary() != null ? existingPage.getSummary() : "";
            }
        }

        // Step 2: DB 持久化（保留已有 summary，异步任务会更新）
        WikiPageDO pageDO = persistPage(scopeId, title, content, category, existingSummary, contentHash, existingPage);

        // Step 3: 文件写入
        try {
            String scopeIdStr = String.valueOf(scopeId);
            storageProvider.write(scopeIdStr, "wiki/" + pageDO.getFilePath(),
                content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to write file: pageId={}", pageDO.getId(), e);
        }

        // Step 4: 基础 ES 索引（无 summary，确保搜索可见）
        syncToSearchIndex(pageDO, scopeId, content);

        // Step 5: 清理编辑会话和草稿
        cleanupAfterSave(request, scopeId);

        // Step 6: 发送异步后处理任务（AI 摘要/Schema校验/链接同步）
        if (postSaveTaskPublisher != null) {
            try {
                postSaveTaskPublisher.publish(scopeId, pageDO.getId(), content, category, contentHash);
            } catch (Exception e) {
                log.warn("Failed to publish post-save task, async processing will be skipped: pageId={}", pageDO.getId(), e);
            }
        } else {
            log.info("PostSaveTaskPublisher unavailable, skipping async post-processing: pageId={}", pageDO.getId());
        }

        log.info("Page saved (sync): scopeId={}, pageId={}, title={}", scopeId, pageDO.getId(), title);
        return toPageInfo(pageDO);
    }

    private WikiPageDO persistPage(Long scopeId, String title, String content, String category,
                                    String summary, String contentHash, WikiPageDO existingPage) {
        boolean isNew = (existingPage == null);
        WikiPageDO pageDO;
        if (isNew) {
            String filePath = buildFilePath(title, category, true);
            pageDO = new WikiPageDO();
            pageDO.setTitle(title);
            pageDO.setFilePath(filePath);
            pageDO.setCategory(category);
            pageDO.setScopeId(scopeId);
            pageDO.setSourceCount(0);
            pageDO.setHealthStatus("healthy");
            pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
            pageDO.setVisibility("public");
            pageDO.setCreatedAt(LocalDateTime.now());
            pageDO.setContentUpdatedAt(LocalDateTime.now());
        } else {
            pageDO = existingPage;
            pageDO.setTitle(title);
            pageDO.setCategory(category);
            pageDO.setContentUpdatedAt(LocalDateTime.now());
        }

        pageDO.setSummary(summary);
        pageDO.setPageType("manual");
        pageDO.setUserModified(1);
        pageDO.setContentHash(contentHash);
        pageDO.setPostSaveStatus("pending");
        pageDO.setPostSaveError(null);
        pageDO.setUpdatedAt(LocalDateTime.now());

        if (isNew) {
            wikiPageMapper.insert(pageDO);
        } else {
            wikiPageMapper.updateById(pageDO);
        }

        return pageDO;
    }

    private String buildFilePath(String title, String category, boolean isNew) {
        String safeTitle = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-_]", "_").toLowerCase();
        String safeCategory = (category != null && !category.isBlank())
            ? category.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-_]", "_").toLowerCase() + "/"
            : "";
        String base = safeCategory + safeTitle;
        if (isNew) {
            base += "_" + UUID.randomUUID().toString().substring(0, 6);
        }
        return base + ".md";
    }

    private String buildMetadataJson(String title, String category) {
        return "{\"title\":\"" + (title != null ? title : "") + "\",\"category\":\"" + (category != null ? category : "") + "\"}";
    }

    private void syncToSearchIndex(WikiPageDO pageDO, Long scopeId, String content) {
        try {
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.error("Failed to sync page to search index: pageId={}", pageDO.getId(), e);
        }
    }

    private void cleanupAfterSave(SavePageRequest request, Long scopeId) {
        if (request.getSessionId() != null) {
            try {
                editSessionService.publishSession(request.getSessionId(), scopeId);
            } catch (Exception e) {
                log.warn("Failed to publish edit session: sessionId={}", request.getSessionId(), e);
            }
        }
        if (request.getDraftId() != null) {
            try {
                draftService.deleteDraft(request.getDraftId(), scopeId);
            } catch (Exception e) {
                log.warn("Failed to delete draft after save: draftId={}", request.getDraftId(), e);
            }
        }
    }

    private WikiPageInfo toPageInfo(WikiPageDO pageDO) {
        WikiPageInfo info = new WikiPageInfo();
        info.setId(pageDO.getId());
        info.setTitle(pageDO.getTitle());
        info.setPath(pageDO.getFilePath());
        info.setCategory(pageDO.getCategory());
        info.setSummary(pageDO.getSummary());
        info.setHealthStatus(pageDO.getHealthStatus());
        info.setPageType(pageDO.getPageType());
        info.setUserModified(pageDO.getUserModified());
        info.setPostSaveStatus(pageDO.getPostSaveStatus());
        info.setPostSaveError(pageDO.getPostSaveError());
        return info;
    }

    private WikiPageInfo toPageInfo(String title, String content, String category) {
        WikiPageInfo info = new WikiPageInfo();
        info.setTitle(title);
        info.setCategory(category);
        return info;
    }
}
