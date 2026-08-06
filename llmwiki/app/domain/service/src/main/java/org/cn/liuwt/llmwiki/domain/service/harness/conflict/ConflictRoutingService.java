package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictResolutionStrategy.AutoLevel;

@Component
public class ConflictRoutingService {

    private static final Logger log = LoggerFactory.getLogger(ConflictRoutingService.class);

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ConflictReviewService conflictReviewService;

    private final ExecutorService conflictExecutor = Executors.newFixedThreadPool(2,
        r -> { Thread t = new Thread(r, "conflict-routing"); t.setDaemon(true); return t; });

    public record ConflictRoute(
        ConflictResolutionStrategy strategy,
        AutoLevel autoLevel,
        String reason
    ) {}

    /**
     * 根据页面分类 + Schema 配置 + AI hint 决定冲突路由
     */
    public ConflictRoute route(String category, String aiHint, LintRulesConfig rulesConfig) {
        LintRulesConfig.ConflictResolutionRules cr = rulesConfig != null 
            ? rulesConfig.getConflictResolutionRules() : null;

        // 1. 查 Schema categoryStrategies[category] 是否有明确策略
        String schemaStrategy = null;
        String schemaAutoLevel = null;
        
        if (cr != null && category != null) {
            schemaStrategy = cr.getCategoryStrategies().get(category);
            schemaAutoLevel = cr.getCategoryAutoLevels().get(category);
            
            // 尝试父分类匹配
            if (schemaStrategy == null && category.contains("/")) {
                String parent = category.substring(0, category.indexOf('/'));
                schemaStrategy = cr.getCategoryStrategies().get(parent);
                schemaAutoLevel = cr.getCategoryAutoLevels().get(parent);
            }
        }

        // 2. 确定最终策略
        ConflictResolutionStrategy strategy;
        if (schemaStrategy != null) {
            strategy = ConflictResolutionStrategy.fromString(schemaStrategy);
        } else if (aiHint != null && !aiHint.isBlank()) {
            strategy = ConflictResolutionStrategy.fromString(aiHint);
        } else {
            String defaultStrategy = cr != null ? cr.getDefaultStrategy() : "annotate_both";
            strategy = ConflictResolutionStrategy.fromString(defaultStrategy);
        }

        // 3. 确定自动级别
        AutoLevel autoLevel;
        if (schemaAutoLevel != null) {
            autoLevel = parseAutoLevel(schemaAutoLevel);
        } else if (schemaStrategy != null) {
            // Schema 有明确策略但无明确自动级别 → 使用策略默认级别
            autoLevel = strategy.getDefaultAutoLevel();
        } else {
            // Schema 无明确策略 → fallback 到默认自动级别
            String defaultAutoLevel = cr != null ? cr.getDefaultAutoLevel() : "REVIEW";
            autoLevel = parseAutoLevel(defaultAutoLevel);
        }

        // 4. AI hint 与 Schema 策略不一致时 → 自动级别降为 REVIEW
        if (aiHint != null && !aiHint.isBlank() && schemaStrategy != null 
            && !strategy.getKey().equalsIgnoreCase(aiHint)) {
            if (autoLevel == AutoLevel.AUTO) {
                autoLevel = AutoLevel.REVIEW;
            }
        }

        // 构建决策原因
        String reason = buildRouteReason(category, strategy, autoLevel, schemaStrategy, aiHint);

        return new ConflictRoute(strategy, autoLevel, reason);
    }

    private AutoLevel parseAutoLevel(String level) {
        if (level == null || level.isBlank()) return AutoLevel.REVIEW;
        try {
            return AutoLevel.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AutoLevel.REVIEW;
        }
    }

    private String buildRouteReason(String category, ConflictResolutionStrategy strategy, 
                                     AutoLevel autoLevel, String schemaStrategy, String aiHint) {
        StringBuilder sb = new StringBuilder();
        if (schemaStrategy != null) {
            sb.append("Schema 分类策略 [").append(category).append("] → ").append(schemaStrategy);
        } else if (aiHint != null && !aiHint.isBlank()) {
            sb.append("AI 检测建议 → ").append(aiHint);
        } else {
            sb.append("默认策略 → ").append(strategy.getKey());
        }
        sb.append("，自动级别: ").append(autoLevel.name());
        if (aiHint != null && schemaStrategy != null && !strategy.getKey().equalsIgnoreCase(aiHint)) {
            sb.append("（AI建议与Schema不一致，降级为REVIEW）");
        }
        return sb.toString();
    }

    public CompletableFuture<Void> dispatchDuplicateConflict(Long scopeId, Long executionId,
            WikiPageDO newPage, WikiPageDO existingPage, double similarity) {
        return CompletableFuture.runAsync(() -> {
            try {
                createContradictionLink(scopeId, newPage, existingPage, "content_duplication", executionId);
                markConflictWarning(scopeId, newPage, existingPage);
                String reason = "确定性内容重复检测（Jaccard相似度=" + String.format("%.0f%%", similarity * 100) + "）";
                Long reviewId = conflictReviewService.createReview(
                    scopeId, executionId, "INGEST",
                    newPage, existingPage, "content_duplication",
                    "adjudicate", "需人工裁决", reason);
                String title = "检测到内容重复";
                String content = String.format("页面「%s」与「%s」内容高度相似（相似度%.0f%%），建议合并或处理。（裁决ID: %d）",
                    newPage.getTitle(), existingPage.getTitle(), similarity * 100, reviewId);
                notificationService.createNotification(scopeId, "conflict_pending_review",
                    title, content, scopeId, existingPage.getId(), executionId);
                log.info("Duplicate conflict dispatched: '{}' ↔ '{}' (similarity={}%)",
                    newPage.getTitle(), existingPage.getTitle(), String.format("%.0f", similarity * 100));
            } catch (Exception e) {
                log.warn("dispatchDuplicateConflict failed: scopeId={}, newPage={}, existingPage={}: {}",
                    scopeId, newPage.getTitle(), existingPage.getTitle(), e.getMessage());
            }
        }, conflictExecutor);
    }

    /**
     * Ingest 冲突分流执行（异步，不阻塞 Ingest 主流程）
     */
    public CompletableFuture<Void> dispatchIngestConflict(Long scopeId, Long executionId,
            WikiPageDO newPage, WikiPageDO existingPage, String conflictType, 
            ConflictRoute route) {
        return CompletableFuture.runAsync(() -> {
            try {
                doDispatchConflict(scopeId, executionId, newPage, existingPage, conflictType, route);
            } catch (Exception e) {
                log.warn("dispatchIngestConflict failed: scopeId={}, newPage={}, existingPage={}: {}",
                    scopeId, newPage.getTitle(), existingPage.getTitle(), e.getMessage());
            }
        }, conflictExecutor);
    }

    private void doDispatchConflict(Long scopeId, Long executionId,
            WikiPageDO newPage, WikiPageDO existingPage, String conflictType,
            ConflictRoute route) {

        ConflictResolutionStrategy strategy = route.strategy();
        AutoLevel autoLevel = route.autoLevel();

        log.info("Conflict routing: {} ↔ {}, strategy={}, autoLevel={}, reason={}",
            newPage.getTitle(), existingPage.getTitle(), strategy.getKey(), autoLevel, route.reason());

        // 创建 contradiction 链接（所有路径都需要）
        createContradictionLink(scopeId, newPage, existingPage, conflictType, executionId);

        switch (autoLevel) {
            case AUTO -> {
                // 自动执行
                executeAutoConflict(scopeId, executionId, newPage, existingPage, conflictType, strategy);
                sendAutoResolvedNotification(scopeId, executionId, newPage, existingPage, strategy);
            }
            case REVIEW -> {
                markConflictWarning(scopeId, newPage, existingPage);
                Long reviewId = conflictReviewService.createReview(
                    scopeId, executionId, "INGEST",
                    newPage, existingPage, conflictType,
                    strategy.getKey(), strategy.getLabel(), route.reason());
                sendPendingReviewNotification(scopeId, executionId, newPage, existingPage, conflictType, strategy, reviewId);
            }
            case DEFER -> {
                // 暂缓处理，标注并存
                markConflictWarning(scopeId, newPage, existingPage);
                sendDeferredNotification(scopeId, executionId, newPage, existingPage, conflictType);
            }
        }
    }

    private void executeAutoConflict(Long scopeId, Long executionId,
            WikiPageDO newPage, WikiPageDO existingPage, String conflictType,
            ConflictResolutionStrategy strategy) {
        
        if (chatClient == null || !chatClient.isAvailable()) {
            log.warn("AI not available, cannot auto-resolve conflict");
            return;
        }

        String newContent = readPageContent(scopeId, newPage.getFilePath());
        String existingContent = readPageContent(scopeId, existingPage.getFilePath());

        String strategyInstruction = ConflictResolutionStrategy.buildStrategyInstruction(strategy.getKey());
        String rewritePrompt = schemaInjector.prependForLint(scopeId,
            PromptTemplate.knowledgeNormalizationPrinciple() + "\n\n---\n\n"
                + strategyInstruction
                + "冲突类型：" + (conflictType != null ? conflictType : "未分类") + "\n\n"
                + "【新内容】\n" + truncate(newContent, 8000) + "\n\n"
                + "【现有内容】\n" + truncate(existingContent, 8000) + "\n\n"
                + "请根据策略改写【现有内容】，整合新内容中的更新信息。");

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
        if (!acquired) {
            log.warn("LlmConcurrencyBarrier LINT bucket timeout, skipping auto conflict resolution");
            return;
        }

        try {
            String rewritten = chatClient.chat(rewritePrompt);
            rewritten = stripMarkdownFences(rewritten);

            // 写入改写后的内容
            storageProvider.write(String.valueOf(scopeId), "wiki/" + existingPage.getFilePath(),
                rewritten.getBytes(StandardCharsets.UTF_8));

            // 更新页面状态
            existingPage.setHealthStatus("healthy");
            existingPage.setContentUpdatedAt(java.time.LocalDateTime.now());
            wikiPageMapper.updateById(existingPage);

            // 同步 ES 索引
            searchService.indexPage(scopeId, existingPage.getId(), existingPage.getTitle(),
                existingPage.getFilePath(), existingPage.getCategory(), existingPage.getSummary(),
                rewritten, "healthy", existingPage.getVisibility(),
                existingPage.getLifecycleStatus());

            log.info("Auto conflict resolved: {} merged into {}", newPage.getTitle(), existingPage.getTitle());
        } catch (Exception e) {
            log.warn("Auto conflict resolution failed: {}", e.getMessage());
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
        }
    }

    public void markConflictWarning(Long scopeId, WikiPageDO newPage, WikiPageDO existingPage) {
        newPage.setHealthStatus("conflict-warning");
        wikiPageMapper.updateById(newPage);
        existingPage.setHealthStatus("conflict-warning");
        wikiPageMapper.updateById(existingPage);
    }

    public void createContradictionLink(Long scopeId, WikiPageDO pageA, WikiPageDO pageB,
                                        String conflictType, Long executionId) {
        WikiPageLinkDO existing = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageA.getId())
                .eq(WikiPageLinkDO::getToPageId, pageB.getId())
                .eq(WikiPageLinkDO::getLinkType, "contradiction")
        );
        if (existing != null) return;

        WikiPageLinkDO linkDO = new WikiPageLinkDO();
        linkDO.setScopeId(scopeId);
        linkDO.setFromPageId(pageA.getId());
        linkDO.setToPageId(pageB.getId());
        linkDO.setLinkType("contradiction");
        linkDO.setCreatedBy("ingest_conflict_routing");
        linkDO.setExecutionId(executionId);
        if (conflictType != null && !conflictType.isBlank()) {
            linkDO.setLinkContext("矛盾类型：" + conflictType);
        }
        wikiPageLinkMapper.insert(linkDO);
    }

    private void sendAutoResolvedNotification(Long scopeId, Long executionId,
            WikiPageDO pageA, WikiPageDO pageB, ConflictResolutionStrategy strategy) {
        String title = "冲突已自动处理";
        String content = String.format("页面「%s」与「%s」存在冲突，已按策略【%s】自动合并。",
            pageA.getTitle(), pageB.getTitle(), strategy.getLabel());
        notificationService.createNotification(scopeId, "conflict_auto_resolved", 
            title, content, scopeId, pageB.getId(), executionId);
    }

    private void sendPendingReviewNotification(Long scopeId, Long executionId,
            WikiPageDO pageA, WikiPageDO pageB, String conflictType, ConflictResolutionStrategy strategy, Long reviewId) {
        String title = "发现冲突待处理";
        String content = String.format("页面「%s」与「%s」存在%s冲突，建议按【%s】处理，请人工确认。（裁决ID: %d）",
            pageA.getTitle(), pageB.getTitle(), 
            conflictType != null ? conflictType : "", strategy.getLabel(), reviewId);
        notificationService.createNotification(scopeId, "conflict_pending_review",
            title, content, scopeId, pageB.getId(), executionId);
    }

    private void sendDeferredNotification(Long scopeId, Long executionId,
            WikiPageDO pageA, WikiPageDO pageB, String conflictType) {
        String title = "冲突已暂缓";
        String content = String.format("页面「%s」与「%s」存在%s冲突，已标注并存，将在下次 Lint 时重新评估。",
            pageA.getTitle(), pageB.getTitle(), conflictType != null ? conflictType : "");
        notificationService.createNotification(scopeId, "conflict_deferred",
            title, content, scopeId, pageB.getId(), executionId);
    }

    private String readPageContent(Long scopeId, String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        try {
            byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + filePath);
            if (bytes != null) return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("readPageContent failed: scopeId={}, path={}", scopeId, filePath);
        }
        return "";
    }

    private String stripMarkdownFences(String text) {
        if (text == null) return "";
        text = text.trim();
        if (text.startsWith("```markdown")) text = text.substring(11);
        else if (text.startsWith("```md")) text = text.substring(5);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
