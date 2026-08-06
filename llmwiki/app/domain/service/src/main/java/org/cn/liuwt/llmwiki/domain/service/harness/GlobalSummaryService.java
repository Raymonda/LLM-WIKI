package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.helper.ActivePageScope;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class GlobalSummaryService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSummaryService.class);
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final ExecutorService summaryExecutor = Executors.newFixedThreadPool(4);

    private final ConcurrentHashMap<Long, CachedSummary> cache = new ConcurrentHashMap<>();

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private SourceMapper sourceMapper;

    public GlobalSummary build(Long scopeId) {
        CachedSummary cached = cache.get(scopeId);
        if (cached != null && !cached.isExpired()) {
            return cached.summary;
        }

        GlobalSummary summary = buildFresh(scopeId);
        cache.put(scopeId, new CachedSummary(summary));
        return summary;
    }

    public GlobalSummary buildFresh(Long scopeId) {
        long startMs = System.currentTimeMillis();

        CompletableFuture<Integer> totalPagesFuture = CompletableFuture.supplyAsync(() -> countPages(scopeId), summaryExecutor);
        CompletableFuture<String> categoryFuture = CompletableFuture.supplyAsync(() -> buildCategorySkeleton(scopeId), summaryExecutor);
        CompletableFuture<String> conflictFuture = CompletableFuture.supplyAsync(() -> buildConflictLinkGraph(scopeId), summaryExecutor);
        CompletableFuture<String> activityFuture = CompletableFuture.supplyAsync(() -> extractRecentActivitySummary(scopeId), summaryExecutor);
        CompletableFuture<String> sourceFuture = CompletableFuture.supplyAsync(() -> buildSourceCatalog(scopeId), summaryExecutor);
        CompletableFuture<Integer> conflictWarningFuture = CompletableFuture.supplyAsync(() -> countConflictWarningPages(scopeId), summaryExecutor);
        CompletableFuture<Integer> deprecatedFuture = CompletableFuture.supplyAsync(() -> countDeprecatedPages(scopeId), summaryExecutor);
        CompletableFuture<List<DeprecatedPageSummary>> deprecatedListFuture = CompletableFuture.supplyAsync(() -> listDeprecatedPages(scopeId), summaryExecutor);

        int totalPages = totalPagesFuture.join();
        CompletableFuture<String> linkGraphFuture = CompletableFuture.supplyAsync(() -> buildLinkGraphStats(scopeId, totalPages), summaryExecutor);

        CompletableFuture.allOf(categoryFuture, linkGraphFuture, conflictFuture, activityFuture, sourceFuture, conflictWarningFuture, deprecatedFuture, deprecatedListFuture).join();

        String categorySkeleton = categoryFuture.join();
        String linkGraphStats = linkGraphFuture.join();
        String conflictLinkGraph = conflictFuture.join();
        String recentActivity = activityFuture.join();
        String sourceCatalog = sourceFuture.join();
        int conflictWarningPages = conflictWarningFuture.join();
        int deprecatedPages = deprecatedFuture.join();
        List<DeprecatedPageSummary> deprecatedPageList = deprecatedListFuture.join();

        long elapsed = System.currentTimeMillis() - startMs;
        log.debug("GlobalSummary built for scopeId={}, totalPages={}, conflictWarning={}, deprecated={}, elapsed={}ms", scopeId, totalPages, conflictWarningPages, deprecatedPages, elapsed);

        return new GlobalSummary(categorySkeleton, linkGraphStats, conflictLinkGraph, recentActivity, sourceCatalog, totalPages, conflictWarningPages, deprecatedPages, deprecatedPageList);
    }

    public void invalidate(Long scopeId) {
        if (scopeId == null) {
            cache.clear();
        } else {
            cache.remove(scopeId);
        }
    }

    private int countPages(Long scopeId) {
        try {
            return Math.toIntExact(wikiPageMapper.selectCount(
                ActivePageScope.activeVisible(scopeId)));
        } catch (Exception e) {
            return 0;
        }
    }

    private String buildCategorySkeleton(Long scopeId) {
        try {
            QueryWrapper<WikiPageDO> qw = new QueryWrapper<>();
            qw.select("category",
                "COUNT(*) as cnt",
                "SUM(CASE WHEN health_status != 'healthy' AND health_status IS NOT NULL THEN 1 ELSE 0 END) as unhealthy_cnt");
            qw.eq("scope_id", scopeId);
            qw.apply(ActivePageScope.ACTIVE_VISIBLE_SQL);
            qw.groupBy("category");
            qw.orderByAsc("category");
            List<Map<String, Object>> rows = wikiPageMapper.selectMaps(qw);

            if (rows.isEmpty()) {
                return "(暂无分类)";
            }

            Map<String, Integer> rootCounts = new LinkedHashMap<>();
            Map<String, List<String>> rootSubCats = new LinkedHashMap<>();

            for (Map<String, Object> row : rows) {
                String cat = (String) row.get("category");
                Long cnt = ((Number) row.get("cnt")).longValue();
                Long unhealthy = ((Number) row.get("unhealthy_cnt")).longValue();

                String root = cat.contains("/") ? cat.substring(0, cat.indexOf('/')) : cat;
                rootCounts.merge(root, cnt.intValue(), Integer::sum);
                rootSubCats.computeIfAbsent(root, k -> new ArrayList<>());

                if (cat.contains("/")) {
                    String subLabel = cat + " (" + cnt + "页"
                        + (unhealthy > 0 ? ", " + unhealthy + "异常" : "") + ")";
                    rootSubCats.get(root).add("  " + subLabel);
                } else if (cnt > 0) {
                    rootSubCats.get(root).add("  " + root + " (" + cnt + "页"
                        + (unhealthy > 0 ? ", " + unhealthy + "异常" : "") + ")");
                }
            }

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : rootCounts.entrySet()) {
                sb.append("- **").append(entry.getKey()).append("** (")
                    .append(entry.getValue()).append("页)\n");
                List<String> subs = rootSubCats.getOrDefault(entry.getKey(), List.of());
                if (!subs.isEmpty()) {
                    for (String sub : subs) {
                        sb.append(sub).append("\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build category skeleton for scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取分类统计)";
        }
    }

    private String buildSourceCatalog(Long scopeId) {
        try {
            List<SourceDO> sources = sourceMapper.selectList(
                new QueryWrapper<SourceDO>()
                    .eq("scope_id", scopeId)
                    .eq("status", "processed")
                    .select("id", "name", "format", "size")
                    .orderByDesc("created_at")
            );

            if (sources.isEmpty()) {
                return "(暂无已处理的来源文档)";
            }

            StringBuilder sb = new StringBuilder();
            for (SourceDO s : sources) {
                sb.append("- [").append(s.getId()).append("] ").append(s.getName());
                if (s.getFormat() != null) {
                    sb.append(" (").append(s.getFormat()).append(")");
                }
                if (s.getSize() != null && s.getSize() > 0) {
                    sb.append(" ").append(formatFileSize(s.getSize()));
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build source catalog for scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取来源文档清单)";
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return (bytes / (1024 * 1024)) + "MB";
    }

    private String buildLinkGraphStats(Long scopeId, int totalPages) {
        try {
            int totalLinks = Math.toIntExact(wikiPageLinkMapper.selectCount(
                new QueryWrapper<WikiPageLinkDO>()
                    .eq("scope_id", scopeId)
                    .inSql("from_page_id", "SELECT id FROM wiki_page WHERE lifecycle_status = 'ACTIVE'")
                    .inSql("to_page_id", "SELECT id FROM wiki_page WHERE lifecycle_status = 'ACTIVE'")));

            int orphanCount = (int) wikiPageMapper.selectOrphanCountByScope(scopeId);

            double linkDensity = totalPages > 0 ? (double) totalLinks / totalPages : 0;
            double orphanPct = totalPages > 0 ? (double) orphanCount / totalPages * 100 : 0;

            return String.format(
                "- 总页面: %d | 总链接: %d | 链接密度: %.1f/页\n- 孤页(无入站链接): %d (%.1f%%)",
                totalPages, totalLinks, linkDensity, orphanCount, orphanPct);
        } catch (Exception e) {
            log.warn("Failed to build link graph stats for scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取链接图谱统计)";
        }
    }

    private String buildConflictLinkGraph(Long scopeId) {
        try {
            List<WikiPageLinkDO> conflictLinks = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .eq(WikiPageLinkDO::getLinkType, "contradiction")
            );

            if (conflictLinks.isEmpty()) {
                return "(无矛盾链接)";
            }

            Set<Long> pageIds = new HashSet<>();
            for (WikiPageLinkDO link : conflictLinks) {
                pageIds.add(link.getFromPageId());
                pageIds.add(link.getToPageId());
            }
            Map<Long, WikiPageDO> pageMap = wikiPageMapper.selectBatchIds(pageIds).stream()
                .filter(p -> p != null && PageLifecycle.ACTIVE.name().equals(p.getLifecycleStatus()))
                .collect(java.util.stream.Collectors.toMap(WikiPageDO::getId, p -> p));

            StringBuilder sb = new StringBuilder();
            sb.append("- 矛盾链接数: ").append(conflictLinks.size()).append("\n");

            for (WikiPageLinkDO link : conflictLinks) {
                WikiPageDO fromPage = pageMap.get(link.getFromPageId());
                WikiPageDO toPage = pageMap.get(link.getToPageId());
                if (fromPage != null && toPage != null) {
                    sb.append("  - ⚠️ ").append(fromPage.getTitle())
                        .append(" ↔ ").append(toPage.getTitle()).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build conflict link graph for scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取矛盾链接图谱)";
        }
    }

    private int countConflictWarningPages(Long scopeId) {
        try {
            return Math.toIntExact(wikiPageMapper.selectCount(
                ActivePageScope.active(scopeId)
                    .eq(WikiPageDO::getHealthStatus, "conflict-warning")));
        } catch (Exception e) {
            return 0;
        }
    }

    private int countDeprecatedPages(Long scopeId) {
        try {
            return Math.toIntExact(wikiPageMapper.selectCount(
                new QueryWrapper<WikiPageDO>()
                    .eq("scope_id", scopeId)
                    .eq("lifecycle_status", PageLifecycle.DEPRECATED.name())));
        } catch (Exception e) {
            return 0;
        }
    }

    private List<DeprecatedPageSummary> listDeprecatedPages(Long scopeId) {
        try {
            List<WikiPageDO> pages = wikiPageMapper.selectList(
                new QueryWrapper<WikiPageDO>()
                    .eq("scope_id", scopeId)
                    .eq("lifecycle_status", PageLifecycle.DEPRECATED.name())
                    .select("id", "title", "category", "file_path", "deprecated_reason")
                    .orderByDesc("content_updated_at")
                    .last("LIMIT 50"));
            return pages.stream()
                .map(p -> new DeprecatedPageSummary(p.getId(), p.getTitle(), p.getCategory(), p.getFilePath(), p.getDeprecatedReason()))
                .toList();
        } catch (Exception e) {
            log.warn("Failed to list deprecated pages for scopeId={}: {}", scopeId, e.getMessage());
            return List.of();
        }
    }

    private String extractRecentActivitySummary(Long scopeId) {
        return executionHistoryService.getRecentActivitySummary(scopeId);
    }

    public record DeprecatedPageSummary(Long pageId, String title, String category, String filePath, String deprecatedReason) {}

    public record GlobalSummary(
        String categorySkeleton,
        String linkGraphStats,
        String conflictLinkGraph,
        String recentActivity,
        String sourceCatalog,
        int totalPages,
        int conflictWarningPages,
        int deprecatedPages,
        List<DeprecatedPageSummary> deprecatedPageList
    ) {
        public String toCompactPrompt() {
            StringBuilder sb = new StringBuilder();
            sb.append("## 知识库全局摘要\n\n");
            sb.append("### 分类体系（共 ").append(totalPages).append(" 页");
            if (conflictWarningPages > 0) {
                sb.append(", ").append(conflictWarningPages).append(" 页存在矛盾");
            }
            if (deprecatedPages > 0) {
                sb.append("，另有 ").append(deprecatedPages).append(" 页已标记过时");
            }
            sb.append("）\n");
            sb.append(categorySkeleton).append("\n\n");
            sb.append("### 链接图谱\n").append(linkGraphStats).append("\n\n");
            if (!"(无矛盾链接)".equals(conflictLinkGraph)) {
                sb.append("### ⚠️ 矛盾链接图谱\n").append(conflictLinkGraph).append("\n\n");
            }
            if (deprecatedPageList != null && !deprecatedPageList.isEmpty()) {
                sb.append("### 已过时页面（新资料若涉及以下主题，应复活对应页面而非新建）\n");
                for (DeprecatedPageSummary dp : deprecatedPageList) {
                    sb.append("- **").append(dp.title()).append("**");
                    if (dp.category() != null) sb.append(" (").append(dp.category()).append(")");
                    if (dp.deprecatedReason() != null) sb.append(" — 过时原因: ").append(dp.deprecatedReason());
                    sb.append("\n");
                }
                sb.append("\n");
            }
            sb.append("### 来源文档（原始资料，可通过 readFile 读取 parsed/{sourceId}.parsed.md）\n\n");
            sb.append(sourceCatalog).append("\n\n");
            sb.append("### 最近活动\n").append(recentActivity);
            return sb.toString();
        }
    }

    private static class CachedSummary {
        final GlobalSummary summary;
        final long createdAt;

        CachedSummary(GlobalSummary summary) {
            this.summary = summary;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}