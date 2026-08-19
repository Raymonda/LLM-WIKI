package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.facade.model.WikiPageInfo;
import org.cn.liuwt.llmwiki.facade.model.RelatedPageInfo;
import org.cn.liuwt.llmwiki.facade.model.GraphData;
import org.cn.liuwt.llmwiki.facade.model.GraphOverviewData;
import org.cn.liuwt.llmwiki.facade.model.HealthInfo;
import org.cn.liuwt.llmwiki.facade.model.ModifyRequest;
import org.cn.liuwt.llmwiki.facade.model.ModifyPreCheckRequest;
import org.cn.liuwt.llmwiki.facade.model.ModifyPreCheckResponse;
import org.cn.liuwt.llmwiki.facade.model.ModifySubmitRequest;
import org.cn.liuwt.llmwiki.facade.model.SourceInfo;
import org.cn.liuwt.llmwiki.facade.model.PromotionStats;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.facade.model.DeprecateRequest;
import org.cn.liuwt.llmwiki.facade.model.MergePagesRequest;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.cn.liuwt.llmwiki.domain.service.system.SubscriptionService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.domain.service.wiki.PromotionService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.service.ingest.PageModifyService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/wiki")
public class WikiController {
    private static final Logger log = LoggerFactory.getLogger(WikiController.class);

    @Autowired
    private WikiFileServiceImpl wikiFileService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private PromotionService promotionService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private PageModifyService pageModifyService;
    @Autowired
    private SchemaComplianceChecker schemaComplianceChecker;
    @Autowired
    private StorageProvider storageProvider;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private ScopeMapper scopeMapper;
    @Autowired
    private ScopeService scopeService;
    @Autowired
    private org.cn.liuwt.llmwiki.service.ingest.MergeService mergeService;

    @GetMapping("/pages")
    public Result<List<WikiPageInfo>> listPages(@RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "50") int size,
                                                 @RequestParam(required = false) String category) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageModel> pages = (category != null && !category.isBlank())
            ? wikiFileService.listPagesByCategory(scopeId, category.trim(), page, size)
            : wikiFileService.listPagesByScopeId(scopeId, page, size);
        List<WikiPageInfo> infos = pages.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    @GetMapping("/page/{id}")
    public Result<WikiPageInfo> getPage(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        WikiPageModel page = wikiFileService.readPageById(id, scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }
        WikiPageInfo info = toInfo(page);
        info.setRelatedPages(wikiFileService.getRelatedPages(page.getPath(), scopeId)
            .stream().map(this::toInfo).collect(Collectors.toList()));
        info.setTags(wikiFileService.getPageTags(page.getId(), scopeId));
        info.setKeywords(wikiFileService.getPageKeywords(page.getId(), scopeId));
        List<SourceDO> sources = wikiFileService.getPageSources(page.getId(), scopeId);
        info.setSources(sources.stream().map(this::toSourceInfo).collect(Collectors.toList()));
        info.setLinkResolution(wikiFileService.resolveWikiLinks(page.getContent(), scopeId));
        return Result.success(info);
    }

    @GetMapping("/page-by-path")
    public Result<WikiPageInfo> getPageByPath(@RequestParam String filePath) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        WikiPageModel page = wikiFileService.readPageByFilePath(filePath, scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }
        WikiPageInfo info = toInfo(page);
        info.setRelatedPages(wikiFileService.getRelatedPages(page.getPath(), scopeId)
            .stream().map(this::toInfo).collect(Collectors.toList()));
        info.setTags(wikiFileService.getPageTags(page.getId(), scopeId));
        info.setKeywords(wikiFileService.getPageKeywords(page.getId(), scopeId));
        List<SourceDO> sources = wikiFileService.getPageSources(page.getId(), scopeId);
        info.setSources(sources.stream().map(this::toSourceInfo).collect(Collectors.toList()));
        info.setLinkResolution(wikiFileService.resolveWikiLinks(page.getContent(), scopeId));
        return Result.success(info);
    }

    @GetMapping("/related")
    public Result<List<RelatedPageInfo>> getRelatedPages(@RequestParam Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<RelatedPageInfo> results = wikiFileService.getRelatedPagesById(id, scopeId);
        return Result.success(results);
    }

    @GetMapping("/search")
    public Result<List<SearchResultInfo>> searchPages(
            String query, String category,
            @RequestParam(defaultValue = "false") boolean includeSubscribed,
            @RequestParam(required = false) String scopeIds) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        List<SearchResultInfo> results;
        List<Long> resolvedIds = resolveSearchScopeIds(scopeIds, includeSubscribed, scopeId, userId);
        if (resolvedIds.size() > 1) {
            results = searchService.searchMultiScope(resolvedIds, query, category);
            Map<Long, String> scopeNameCache = new HashMap<>();
            for (SearchResultInfo r : results) {
                if (r.getSourceScopeId() != null && !scopeId.equals(r.getSourceScopeId())) {
                    String name = scopeNameCache.computeIfAbsent(r.getSourceScopeId(), id -> {
                        ScopeDO s = scopeMapper.selectById(id);
                        return s != null ? s.getName() : null;
                    });
                    r.setSourceScopeName(name);
                }
            }
        } else {
            results = searchService.search(resolvedIds.get(0), query, category);
        }
        return Result.success(results);
    }

    @GetMapping("/search/suggest")
    public Result<List<WikiPageInfo>> searchSuggest(String prefix) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageInfo> suggestions = searchService.suggest(scopeId, prefix);
        return Result.success(suggestions);
    }

    @PostMapping("/search/rebuild-index")
    public Result<String> rebuildIndex() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        searchService.rebuildIndex(scopeId);
        return Result.success("索引重建完成");
    }

    @GetMapping("/categories")
    public Result<List<String>> listCategories() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<String> categories = wikiFileService.listDistinctCategories(scopeId);
        return Result.success(categories);
    }

    @GetMapping("/recent")
    public Result<List<WikiPageInfo>> recentPages() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageModel> pages = wikiFileService.listPagesByScopeId(scopeId, 1, 20);
        List<WikiPageInfo> infos = pages.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    @GetMapping("/subscribed-recent")
    public Result<List<WikiPageInfo>> subscribedRecentPages() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<Long> subscribedIds = subscriptionService.getSubscribedScopeIds(scopeId);
        if (subscribedIds.isEmpty()) {
            return Result.success(List.of());
        }
        Map<Long, String> scopeNameCache = new HashMap<>();
        List<WikiPageInfo> all = new ArrayList<>();
        for (Long subScopeId : subscribedIds) {
            List<WikiPageModel> pages = wikiFileService.listPagesByScopeId(subScopeId, 1, 5);
            for (WikiPageModel page : pages) {
                WikiPageInfo info = toInfo(page);
                String name = scopeNameCache.computeIfAbsent(subScopeId, id -> {
                    ScopeDO s = scopeMapper.selectById(id);
                    return s != null ? s.getName() : null;
                });
                info.setSourceScopeName(name);
                all.add(info);
            }
        }
        all.sort((a, b) -> {
            java.time.LocalDateTime ta = b.getContentUpdatedAt() != null ? b.getContentUpdatedAt() : b.getLastModified();
            java.time.LocalDateTime tb = a.getContentUpdatedAt() != null ? a.getContentUpdatedAt() : a.getLastModified();
            if (ta == null && tb == null) return 0;
            if (ta == null) return -1;
            if (tb == null) return 1;
            return ta.compareTo(tb);
        });
        if (all.size() > 10) {
            all = all.subList(0, 10);
        }
        return Result.success(all);
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        long entityCount = wikiFileService.countEntityPagesByScopeId(scopeId);
        List<Map<String, Object>> healthDist = wikiFileService.getHealthDistribution(scopeId);
        long healthy = 0, needsUpdate = 0, hasProblems = 0, other = 0;
        for (Map<String, Object> row : healthDist) {
            String status = row.get("health_status") != null ? row.get("health_status").toString() : "";
            long cnt = ((Number) row.get("cnt")).longValue();
            switch (status) {
                case "healthy" -> healthy += cnt;
                case "needs-update" -> needsUpdate += cnt;
                case "has-problems", "conflict-warning" -> hasProblems += cnt;
                default -> other += cnt;
            }
        }
        return Result.success(Map.of(
            "entityCount", entityCount,
            "healthyCount", healthy,
            "needsUpdateCount", needsUpdate,
            "hasProblemsCount", hasProblems,
            "unknownCount", other
        ));
    }

    @GetMapping("/index")
    public Result<String> getIndex() {
        return Result.success("");
    }

    @GetMapping("/log")
    public Result<String> getLog() {
        return Result.success("");
    }

    private static final int GRAPH_FULL_LOAD_THRESHOLD = 800;

    @GetMapping("/graph")
    public Result<GraphData> getGraph() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageDO> pages = wikiFileService.getAllPagesByScopeId(scopeId);
        if (pages.size() > GRAPH_FULL_LOAD_THRESHOLD) {
            GraphData.GraphStats stats = new GraphData.GraphStats();
            stats.setTotalNodes(pages.size());
            GraphData data = new GraphData();
            data.setNodes(List.of());
            data.setEdges(List.of());
            data.setStats(stats);
            data.setRequiresOverview(true);
            return Result.success(data);
        }
        List<WikiPageLinkDO> links = wikiFileService.getAllLinksByScopeId(scopeId);

        java.util.Set<Long> nodeIds = new java.util.HashSet<>();
        for (WikiPageDO p : pages) nodeIds.add(p.getId());

        Map<Long, Integer> inDegreeMap = new HashMap<>();
        Map<Long, Integer> outDegreeMap = new HashMap<>();
        for (WikiPageLinkDO link : links) {
            if (nodeIds.contains(link.getFromPageId()) && nodeIds.contains(link.getToPageId())) {
                outDegreeMap.merge(link.getFromPageId(), 1, Integer::sum);
                inDegreeMap.merge(link.getToPageId(), 1, Integer::sum);
            }
        }

        int orphanCount = 0;
        int hubCount = 0;
        int conflictCount = 0;
        int needsUpdateCount = 0;
        int hasProblemsCount = 0;
        int deprecatedCount = 0;
        int mergedCount = 0;
        Map<String, Integer> categoryCountMap = new HashMap<>();

        List<GraphData.GraphNode> nodes = new ArrayList<>();
        for (WikiPageDO p : pages) {
            GraphData.GraphNode node = new GraphData.GraphNode();
            node.setId(p.getId());
            node.setTitle(p.getTitle());
            node.setPath(p.getFilePath());
            node.setCategory(p.getCategory());
            node.setHealthStatus(p.getHealthStatus());
            node.setPageType(p.getPageType());
            node.setLifecycleStatus(p.getLifecycleStatus());
            node.setSourceCount(p.getSourceCount());
            int inDeg = inDegreeMap.getOrDefault(p.getId(), 0);
            int outDeg = outDegreeMap.getOrDefault(p.getId(), 0);
            node.setInDegree(inDeg);
            node.setOutDegree(outDeg);
            nodes.add(node);

            categoryCountMap.merge(p.getCategory() != null ? p.getCategory() : "未分类", 1, Integer::sum);
            if (inDeg + outDeg == 0) orphanCount++;
            if (inDeg + outDeg >= 5) hubCount++;
            String hs = p.getHealthStatus();
            if ("conflict-warning".equals(hs) || "has-problems".equals(hs)) conflictCount++;
            if ("needs-update".equals(hs)) needsUpdateCount++;
            if ("has-problems".equals(hs)) hasProblemsCount++;
            String ls = p.getLifecycleStatus();
            if ("DEPRECATED".equals(ls)) deprecatedCount++;
            if ("MERGED".equals(ls)) mergedCount++;
        }

        List<GraphData.GraphEdge> edges = links.stream()
            .filter(l -> nodeIds.contains(l.getFromPageId()) && nodeIds.contains(l.getToPageId()))
            .map(l -> {
                GraphData.GraphEdge edge = new GraphData.GraphEdge();
                edge.setFromId(l.getFromPageId());
                edge.setToId(l.getToPageId());
                edge.setLinkType(l.getLinkType());
                edge.setLinkContext(l.getLinkContext());
                return edge;
            }).collect(Collectors.toList());

        List<GraphData.CategoryCount> categoryCounts = categoryCountMap.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .map(e -> {
                GraphData.CategoryCount cc = new GraphData.CategoryCount();
                cc.setCategory(e.getKey());
                cc.setCount(e.getValue());
                return cc;
            }).collect(Collectors.toList());

        GraphData.GraphStats stats = new GraphData.GraphStats();
        stats.setTotalNodes(nodes.size());
        stats.setTotalEdges(edges.size());
        stats.setOrphanCount(orphanCount);
        stats.setHubCount(hubCount);
        stats.setConflictCount(conflictCount);
        stats.setNeedsUpdateCount(needsUpdateCount);
        stats.setHasProblemsCount(hasProblemsCount);
        stats.setDeprecatedCount(deprecatedCount);
        stats.setMergedCount(mergedCount);
        stats.setLinkDensity(nodes.isEmpty() ? 0 : (double) edges.size() / nodes.size());
        stats.setCategoryCounts(categoryCounts);

        GraphData graphData = new GraphData();
        graphData.setNodes(nodes);
        graphData.setEdges(edges);
        graphData.setStats(stats);

        return Result.success(graphData);
    }

    @GetMapping("/graph/local")
    public Result<GraphData> getLocalGraph(@RequestParam Long pageId) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        GraphData data = wikiFileService.getLocalGraph(pageId, scopeId);
        return Result.success(data);
    }

    @GetMapping("/graph/overview")
    public Result<GraphOverviewData> getGraphOverview() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        GraphOverviewData data = wikiFileService.getGraphOverview(scopeId);
        return Result.success(data);
    }

    @GetMapping("/graph/category")
    public Result<GraphData> getGraphByCategory(@RequestParam String category) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        GraphData data = wikiFileService.getGraphByCategory(scopeId, category);
        return Result.success(data);
    }

    @GetMapping("/graph/search")
    public Result<GraphData> searchGraphNodes(@RequestParam String keyword,
                                               @RequestParam(defaultValue = "50") int limit) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        GraphData data = wikiFileService.searchGraphNodes(scopeId, keyword, Math.min(limit, 200));
        return Result.success(data);
    }

    @GetMapping("/recommended")
    public Result<List<WikiPageInfo>> getRecommended() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageModel> pages = wikiFileService.getRecommendedPages(scopeId);
        List<WikiPageInfo> infos = pages.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    @GetMapping("/health/{id}")
    public Result<HealthInfo> getHealth(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();

        WikiPageModel page = wikiFileService.readPageById(id, scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }

        HealthInfo healthInfo = new HealthInfo();
        healthInfo.setStatus(page.getHealthStatus());
        healthInfo.setLastCheckedAt(page.getLastCheckedAt());

        List<String> issues = new java.util.ArrayList<>();
        List<String> suggestions = new java.util.ArrayList<>();

        if ("needs-update".equals(page.getHealthStatus())) {
            issues.add("页面内容可能需要更新");
            suggestions.add("建议重新摄入相关来源以更新页面内容");
        } else if ("has-problems".equals(page.getHealthStatus())) {
            issues.add("页面存在内容问题");
            suggestions.add("建议运行知识体检以检测具体问题");
        }

        List<WikiPageModel> relatedPages = wikiFileService.getRelatedPages(page.getPath(), scopeId);
        if (relatedPages.isEmpty()) {
            issues.add("页面没有关联的其他页面");
            suggestions.add("建议添加与其他知识页面的交叉引用");
        }

        healthInfo.setIssues(issues);
        healthInfo.setSuggestions(suggestions);
        return Result.success(healthInfo);
    }

    @PostMapping("/modify/precheck")
    public Result<ModifyPreCheckResponse> preCheckModify(@RequestBody ModifyPreCheckRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        WikiPageModel page = wikiFileService.readPageById(request.getPageId(), scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }

        String currentContent = page.getContent() != null ? page.getContent() : "";
        List<Map<String, String>> conflicts = schemaComplianceChecker.preCheckInstruction(
            scopeId, currentContent, request.getInstruction());

        ModifyPreCheckResponse response = new ModifyPreCheckResponse();
        response.setHasConflict(!conflicts.isEmpty());
        if (!conflicts.isEmpty()) {
            List<ModifyPreCheckResponse.ConflictItem> items = new java.util.ArrayList<>();
            for (Map<String, String> c : conflicts) {
                ModifyPreCheckResponse.ConflictItem item = new ModifyPreCheckResponse.ConflictItem();
                item.setRule(c.get("rule"));
                item.setDescription(c.get("description"));
                item.setSeverity(c.get("severity"));
                item.setSection(c.get("section"));
                items.add(item);
            }
            response.setConflicts(items);
        }
        return Result.success(response);
    }

    @PostMapping("/modify/submit")
    public Result<Map<String, Object>> submitModify(@RequestBody ModifySubmitRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();

        WikiPageModel page = wikiFileService.readPageById(request.getPageId(), scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }

        org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO sourceDO =
            pageModifyService.createUserFeedbackSource(scopeId, request.getPageId(), userId, request.getInstruction());

        org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel execution =
            pageModifyService.createModifyExecution(scopeId, request.getPageId(), sourceDO.getId());

        pageModifyService.runModifyPipeline(execution.getId(), scopeId, request.getPageId(),
            request.getInstruction(), sourceDO.getId(), request.isOverrideSchema());

        Map<String, Object> result = new HashMap<>();
        result.put("executionId", execution.getId());
        result.put("status", "running");
        return Result.success(result);
    }

    @PostMapping("/modify")
    @Deprecated
    public Result<WikiPageInfo> modifyPage(@RequestParam Long id, @RequestBody ModifyRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        WikiPageModel page = wikiFileService.readPageById(id, scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }

        String instruction = request.getInstruction();
        String currentContent = page.getContent() != null ? page.getContent() : "";

        String newContent = currentContent + "\n\n---\n\n## 用户修改指令\n\n" + instruction + "\n\n*（修改指令已记录，等待 AI 执行）*";

        wikiFileService.writePageByScopeId(page.getPath(), scopeId, newContent);

        wikiFileService.updatePageHealthStatus(id, "needs-update");

        WikiPageModel updatedPage = wikiFileService.readPageById(id, scopeId);
        return Result.success(toInfo(updatedPage));
    }

    @PutMapping("/pages/deprecated")
    public Result<Void> deprecatePage(@RequestBody DeprecateRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.deprecatePage(request.getPageId(), scopeId, request.getReason());
        return Result.success();
    }

    @DeleteMapping("/pages/deprecated")
    public Result<Void> undeprecatePage(@RequestParam Long pageId) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.undeprecatePage(pageId, scopeId);
        return Result.success();
    }

    @DeleteMapping("/pages/{id}")
    public Result<Void> deletePage(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.softDeletePage(id, scopeId);
        return Result.success();
    }

    @PostMapping("/pages/{id}/restore")
    public Result<Void> restorePage(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.restorePage(id, scopeId);
        return Result.success();
    }

    @DeleteMapping("/pages/{id}/permanent")
    public Result<Void> permanentDeletePage(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.permanentDeletePage(id, scopeId);
        return Result.success();
    }

    @GetMapping("/trash")
    public Result<List<WikiPageInfo>> listTrashPages() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<WikiPageModel> pages = wikiFileService.listTrashPages(scopeId);
        return Result.success(pages.stream().map(this::toInfo).toList());
    }

    @GetMapping("/pages/{id}/delete-impact")
    public Result<org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl.DeleteImpactInfo> getDeleteImpact(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        return Result.success(wikiFileService.getDeleteImpact(id, scopeId));
    }

    @PostMapping("/merge")
    public Result<Map<String, Object>> mergePages(@RequestBody MergePagesRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        if (request.getPageIds() == null || request.getPageIds().size() < 2) {
            return Result.failed(ErrorCode.WIKI_MERGE_MIN_PAGES);
        }
        Map<String, Object> result = mergeService.executeMerge(scopeId, userId, request.getPageIds(),
            request.getTargetTitle(), request.getInstruction());
        return Result.success(result);
    }

    @PostMapping("/init")
    public Result<Void> initWikiData() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        wikiFileService.initWikiData(scopeId);
        return Result.success();
    }

    @PutMapping("/visibility")
    public Result<Void> updateVisibility(@RequestParam Long id, @RequestBody java.util.Map<String, String> body) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();
        String visibility = body.get("visibility");
        if (visibility == null || (!visibility.equals("open") && !visibility.equals("private"))) {
            return Result.failed(ErrorCode.WIKI_INVALID_VISIBILITY);
        }
        WikiPageModel page = wikiFileService.readPageById(id, scopeId);
        if (page == null) {
            return Result.failed(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }
        promotionService.updateVisibility(scopeId, page.getPath(), visibility, userId);
        return Result.success();
    }

    @GetMapping("/promotion-stats")
    public Result<PromotionStats> getPromotionStats() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        PromotionStats stats = promotionService.getPromotionStats(userId);
        return Result.success(stats);
    }

    @GetMapping("/assets/**")
    public ResponseEntity<InputStreamResource> getAsset(HttpServletRequest request) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        String fullPath = request.getRequestURI();
        log.info("[ASSET-DEBUG] requestURI={}, scopeId={}, queryString={}", fullPath, scopeId, request.getQueryString());
        int assetsIdx = fullPath.indexOf("/assets/");
        if (assetsIdx < 0) {
            log.warn("[ASSET-DEBUG] /assets/ not found in URI: {}", fullPath);
            return ResponseEntity.notFound().build();
        }
        String assetPath = "assets/" + fullPath.substring(assetsIdx + "/assets/".length());
        assetPath = URLDecoder.decode(assetPath, StandardCharsets.UTF_8);
        log.info("[ASSET-DEBUG] resolved assetPath={}", assetPath);
        if (assetPath.contains("..") || assetPath.contains("\\")) {
            log.warn("[ASSET-DEBUG] path traversal blocked: {}", assetPath);
            return ResponseEntity.badRequest().build();
        }
        String scopeIdStr = String.valueOf(scopeId);
        try {
            boolean exists = storageProvider.exists(scopeIdStr, assetPath);
            log.info("[ASSET-DEBUG] storageProvider.exists(scopeId={}, path={}) = {}", scopeIdStr, assetPath, exists);
            if (!exists) {
                String resolvedPath = storageProvider.getUrl(scopeIdStr, assetPath);
                log.warn("[ASSET-DEBUG] file NOT found, resolvedPath={}", resolvedPath);
                return ResponseEntity.notFound().build();
            }
            java.io.InputStream stream = storageProvider.readStream(scopeIdStr, assetPath);
            if (stream == null) {
                return ResponseEntity.notFound().build();
            }
            String contentType = guessAssetContentType(assetPath);
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Cache-Control", "public, max-age=86400")
                .body(new InputStreamResource(stream));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private String guessAssetContentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "image/tiff";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    private WikiPageInfo toInfo(WikiPageModel model) {
        WikiPageInfo info = new WikiPageInfo();
        info.setId(model.getId());
        info.setTitle(model.getTitle());
        info.setPath(model.getPath());
        info.setContent(model.getContent());
        info.setCategory(model.getCategory());
        info.setSummary(model.getSummary());
        info.setScopeId(model.getScopeId());
        info.setSourceCount(model.getSourceCount());
        info.setHealthStatus(model.getHealthStatus());
        info.setVisibility(model.getVisibility());
        info.setPromotedFromScopeId(model.getPromotedFromScopeId());
        info.setPromotedFromPageId(model.getPromotedFromPageId());
        info.setPromotedFromUsername(model.getPromotedFromUsername());
        info.setLastCheckedAt(model.getLastCheckedAt());
        info.setContentUpdatedAt(model.getContentUpdatedAt());
        info.setLastModified(model.getLastModified());
        info.setPageType(model.getPageType());
        info.setLifecycleStatus(model.getLifecycleStatus());
        info.setDeprecatedAt(model.getDeprecatedAt());
        info.setDeprecatedReason(model.getDeprecatedReason());
        info.setDeletedAt(model.getDeletedAt());
        info.setMergedIntoPageId(model.getMergedIntoPageId());
        info.setUserModified(model.getUserModified());
        info.setPostSaveStatus(model.getPostSaveStatus());
        info.setPostSaveError(model.getPostSaveError());
        return info;
    }

    private SourceInfo toSourceInfo(SourceDO sourceDO) {
        SourceInfo info = new SourceInfo();
        info.setId(sourceDO.getId());
        info.setName(sourceDO.getName());
        info.setFilePath(sourceDO.getFilePath());
        info.setFormat(sourceDO.getFormat());
        info.setSize(sourceDO.getSize());
        info.setStatus(sourceDO.getStatus());
        info.setCreatedAt(sourceDO.getCreatedAt());
        return info;
    }

    private List<Long> resolveSearchScopeIds(String scopeIdsParam, boolean includeSubscribed, Long currentScopeId, Long userId) {
        if (scopeIdsParam != null && !scopeIdsParam.isBlank()) {
            List<Long> ids = Arrays.stream(scopeIdsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
            for (Long id : ids) {
                if (!scopeService.canView(id, userId)) {
                    throw new org.cn.liuwt.llmwiki.common.util.exception.BusinessException(
                        "PERMISSION_DENIED", "无权访问知识库 " + id);
                }
            }
            return ids.isEmpty() ? List.of(currentScopeId) : ids;
        }
        if (includeSubscribed) {
            List<Long> ids = new ArrayList<>();
            ids.add(currentScopeId);
            ids.addAll(subscriptionService.getSubscribedScopeIds(currentScopeId));
            return ids;
        }
        return List.of(currentScopeId);
    }
}