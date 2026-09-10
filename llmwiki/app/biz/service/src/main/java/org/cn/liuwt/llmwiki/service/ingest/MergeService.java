package org.cn.liuwt.llmwiki.service.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestOrchestrator;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.PipelineTaskMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class MergeService {

    private static final Logger log = LoggerFactory.getLogger(MergeService.class);

    private static final long MERGE_LOCK_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, Long> inFlightMerges = new ConcurrentHashMap<>();

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private ExecutionTracker executionTracker;

    @Autowired
    private IngestOrchestrator ingestOrchestrator;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private ConflictDomainService conflictDomainService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private NotificationService notificationService;

    @Autowired(required = false)
    private RocketMQTemplate rocketMQTemplate;

    @Value("${llmwiki.rocketmq.enabled:false}")
    private boolean mqEnabled;

    private boolean isMqAvailable() {
        return rocketMQTemplate != null && mqEnabled;
    }

    @Autowired
    private ExecutionNodeRegistry registry;

    public Map<String, Object> executeMerge(Long scopeId, Long userId, List<Long> pageIds, String targetTitle, String instruction) {
        List<WikiPageDO> pages = wikiPageMapper.selectBatchIds(pageIds);
        pages = pages.stream()
            .filter(p -> PageLifecycle.ACTIVE.name().equals(p.getLifecycleStatus()))
            .collect(Collectors.toList());
        if (pages.size() < 2) {
            throw new RuntimeException("至少需要两个有效的活跃页面进行合并");
        }

        List<Long> activePageIds = pages.stream().map(WikiPageDO::getId).collect(Collectors.toList());
        String mergeKey = computeMergeKey(activePageIds);
        acquireMergeLock(mergeKey);

        try {
            for (WikiPageDO page : pages) {
                page.setLifecycleStatus(PageLifecycle.MERGING.name());
                wikiPageMapper.updateById(page);
            }

            String mergedContent = buildMergedContent(pages, scopeId);
            String sourceName = "合并: " + pages.stream().map(WikiPageDO::getTitle).collect(Collectors.joining(" + "));
            if (targetTitle != null && !targetTitle.isBlank()) {
                sourceName = "合并至: " + targetTitle;
            }

            SourceDO sourceDO = new SourceDO();
            sourceDO.setName(sourceName);
            sourceDO.setFormat("USER_MERGE");
            sourceDO.setFilePath("");
            sourceDO.setSize((long) mergedContent.getBytes(StandardCharsets.UTF_8).length);
            sourceDO.setStatus("processed");
            sourceDO.setScopeId(scopeId);
            sourceDO.setUploadUserId(userId);
            sourceDO.setCreatedAt(LocalDateTime.now());
            sourceMapper.insert(sourceDO);

            String scopeIdStr = String.valueOf(scopeId);
            String parsedPath = "parsed/" + sourceDO.getId() + ".parsed.md";
            storageProvider.write(scopeIdStr, parsedPath, mergedContent.getBytes(StandardCharsets.UTF_8));

            ExecutionModel execution = executionTracker.createExecution("page_merge", scopeId, sourceDO.getId(), null);
            executionTracker.updateExecutionStatus(execution.getId(), "running");

            String guidance = buildMergeGuidance(pages, targetTitle, instruction);

            if (isMqAvailable()) {
                sendMergeTask(execution.getId(), scopeId, sourceDO.getId(), guidance, activePageIds);
            } else {
                submitLocalMergeTask(execution.getId(), scopeId, sourceDO.getId(), guidance, activePageIds);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("executionId", execution.getId());
            result.put("sourceId", sourceDO.getId());
            result.put("status", "running");
            result.put("mergedPageIds", activePageIds);
            return result;

        } catch (RuntimeException e) {
            restoreMergingPages(scopeId, activePageIds);
            inFlightMerges.remove(mergeKey);
            throw e;
        }
    }

    public void runMergePipeline(Long executionId, Long scopeId, Long sourceId,
                                  String guidance, List<Long> originalPageIds) {
        String mergeKey = computeMergeKey(originalPageIds);
        try {
            ingestOrchestrator.runIngestPipelineWithExecution(executionId, scopeId, sourceId, guidance, true);
            postMergeCleanup(scopeId, originalPageIds, sourceId, executionId);
            sendMergeCompletedNotification(scopeId, executionId, originalPageIds);
        } catch (Exception e) {
            log.error("Merge pipeline failed: executionId={}, scopeId={}", executionId, scopeId, e);
            executionTracker.failExecution(executionId, e.getMessage());
            sendMergeFailedNotification(scopeId, executionId, e.getMessage());
            restoreMergingPages(scopeId, originalPageIds);
            throw e;
        } finally {
            inFlightMerges.remove(mergeKey);
        }
    }

    private void restoreMergingPages(Long scopeId, List<Long> pageIds) {
        try {
            for (Long pageId : pageIds) {
                WikiPageDO pageDO = wikiPageMapper.selectById(pageId);
                if (pageDO != null && PageLifecycle.MERGING.name().equals(pageDO.getLifecycleStatus())) {
                    pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                    wikiPageMapper.updateById(pageDO);
                }
            }
            log.info("Restored {} pages from MERGING to ACTIVE: scopeId={}", pageIds.size(), scopeId);
        } catch (Exception e) {
            log.error("Failed to restore MERGING pages to ACTIVE: scopeId={}, pageIds={}", scopeId, pageIds, e);
        }
    }

    private void acquireMergeLock(String mergeKey) {
        long now = System.currentTimeMillis();
        Long existing = inFlightMerges.putIfAbsent(mergeKey, now);
        if (existing == null) return;
        boolean expired = now - existing >= MERGE_LOCK_TTL_MS;
        if (expired && inFlightMerges.replace(mergeKey, existing, now)) return;
        throw new RuntimeException("该组合的合并任务正在执行中，请勿重复提交");
    }

    private static String computeMergeKey(List<Long> pageIds) {
        return pageIds.stream().sorted().collect(Collectors.toList()).toString();
    }

    private void sendMergeTask(Long executionId, Long scopeId, Long sourceId, String guidance, List<Long> originalPageIds) {
        PipelineTaskMessage msg = new PipelineTaskMessage();
        msg.setExecutionId(executionId);
        msg.setScopeId(scopeId);
        msg.setSourceId(sourceId);
        msg.setGuidance(guidance);
        msg.setTaskType(PipelineTaskMessage.TYPE_MERGE);
        msg.setOriginalPageIds(originalPageIds);
        msg.setNodeId(registry.getNodeId());
        msg.setSubmittedAt(System.currentTimeMillis());
        try {
            rocketMQTemplate.convertAndSend(PipelineTaskMessage.TOPIC, msg);
            log.info("[MQ] Merge task sent: executionId={}, topic={}", executionId, PipelineTaskMessage.TOPIC);
        } catch (Exception e) {
            log.error("[MQ] Failed to send merge task, fallback to local: executionId={}", executionId, e);
            submitLocalMergeTask(executionId, scopeId, sourceId, guidance, originalPageIds);
        }
    }

    private void submitLocalMergeTask(Long executionId, Long scopeId, Long sourceId,
                                       String guidance, List<Long> originalPageIds) {
        java.util.concurrent.Future<?> future = registry.submitTask(() -> {
            try {
                runMergePipeline(executionId, scopeId, sourceId, guidance, originalPageIds);
            } catch (Exception e) {
                log.error("Local merge pipeline failed: executionId={}", executionId, e);
            } finally {
                registry.removeFuture(executionId);
            }
        });
        registry.putFuture(executionId, future);
    }

    private void sendMergeCompletedNotification(Long scopeId, Long executionId, List<Long> originalPageIds) {
        try {
            String title = "知识合并完成";
            String content = String.format("已合并 %d 个页面，新页面已写入知识库。点击查看执行详情。", originalPageIds.size());
            notificationService.createNotification(scopeId, "merge_completed",
                title, content, scopeId, null, executionId);
        } catch (Exception e) {
            log.warn("Failed to send merge completed notification: executionId={}", executionId);
        }
    }

    private void sendMergeFailedNotification(Long scopeId, Long executionId, String error) {
        try {
            String title = "知识合并失败";
            String content = "合并过程出现错误：" + (error != null && error.length() > 200 ? error.substring(0, 200) + "..." : error);
            notificationService.createNotification(scopeId, "merge_failed",
                title, content, scopeId, null, executionId);
        } catch (Exception e) {
            log.warn("Failed to send merge failed notification: executionId={}", executionId);
        }
    }

    private void postMergeCleanup(Long scopeId, List<Long> originalPageIds, Long sourceId, Long executionId) {
        List<WikiPageSourceDO> sourceRels = wikiPageSourceMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getSourceId, sourceId)
        );
        List<Long> pipelinePageIds = new ArrayList<>();
        for (WikiPageSourceDO rel : sourceRels) {
            if (!pipelinePageIds.contains(rel.getPageId())) {
                pipelinePageIds.add(rel.getPageId());
            }
        }

        if (pipelinePageIds.isEmpty()) {
            log.warn("Merge pipeline produced no wiki_page_source records: scopeId={}, sourceId={}", scopeId, sourceId);
            restoreMergingPages(scopeId, originalPageIds);
            return;
        }

        Long targetPageId = null;
        for (Long pid : pipelinePageIds) {
            if (!originalPageIds.contains(pid)) {
                WikiPageDO candidate = wikiPageMapper.selectById(pid);
                if (candidate != null && "summary".equals(candidate.getPageType())) {
                    targetPageId = pid;
                    break;
                }
                if (targetPageId == null) {
                    targetPageId = pid;
                }
            }
        }
        if (targetPageId == null) {
            targetPageId = pipelinePageIds.get(0);
        }

        conflictDomainService.batchArchivePagesForMerge(scopeId, originalPageIds, targetPageId);

        WikiPageDO targetPage = wikiPageMapper.selectById(targetPageId);
        if (targetPage != null && !PageLifecycle.ACTIVE.name().equals(targetPage.getLifecycleStatus())) {
            targetPage.setLifecycleStatus(PageLifecycle.ACTIVE.name());
            wikiPageMapper.updateById(targetPage);
            log.info("Merge post-cleanup: reset target page lifecycle from {} to ACTIVE: pageId={}", targetPage.getLifecycleStatus(), targetPageId);
        }
        if (targetPage != null) {
            try {
                searchService.bulkIndexPages(scopeId, List.of(targetPage));
            } catch (Exception e) {
                log.warn("Merge post-cleanup: failed to re-index target page to ES: pageId={}, error={}", targetPageId, e.getMessage());
            }
        }

        globalSummaryService.invalidate(scopeId);
        log.info("Merge post-cleanup completed: scopeId={}, executionId={}, targetPageId={}, pipelinePages={}",
            scopeId, executionId, targetPageId, pipelinePageIds);
    }

    private String buildMergedContent(List<WikiPageDO> pages, Long scopeId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 知识合并来源\n\n");
        sb.append("以下内容由多个 Wiki 页面合并而来，请消除重复、解决冲突、统一术语，产出一个完整的综合页面。\n\n");
        sb.append("---\n\n");

        for (WikiPageDO page : pages) {
            sb.append("## 来源页面: ").append(page.getTitle()).append("\n\n");
            String scopeIdStr = String.valueOf(scopeId);
            String storagePath = "wiki/" + page.getFilePath();
            byte[] contentBytes = storageProvider.read(scopeIdStr, storagePath);
            if (contentBytes != null) {
                String content = new String(contentBytes, StandardCharsets.UTF_8);
                sb.append(content);
            } else {
                sb.append("(内容读取失败)");
            }
            sb.append("\n\n---\n\n");
        }

        return sb.toString();
    }

    private String buildMergeGuidance(List<WikiPageDO> pages, String targetTitle, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是一个知识合并任务。请将以下多个页面的内容整合为一个统一的页面。\n\n");
        sb.append("合并要求：\n");
        sb.append("1. 消除重复内容\n");
        sb.append("2. 解决矛盾和冲突\n");
        sb.append("3. 统一术语命名\n");
        sb.append("4. 保留所有有价值的信息\n");
        sb.append("5. 产出一个结构清晰、内容完整的综合页面\n\n");
        if (targetTitle != null && !targetTitle.isBlank()) {
            sb.append("目标页面标题: ").append(targetTitle).append("\n\n");
        }
        if (instruction != null && !instruction.isBlank()) {
            sb.append("用户补充指令: ").append(instruction).append("\n\n");
        }
        sb.append("来源页面清单：\n");
        for (WikiPageDO page : pages) {
            sb.append("- ").append(page.getTitle());
            if (page.getCategory() != null) {
                sb.append(" (分类: ").append(page.getCategory()).append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
