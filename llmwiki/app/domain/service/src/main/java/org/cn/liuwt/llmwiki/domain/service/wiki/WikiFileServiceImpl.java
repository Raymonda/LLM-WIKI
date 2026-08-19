package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageTagDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageTagMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.util.PathGuard;
import org.cn.liuwt.llmwiki.common.util.constant.PageLifecycle;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.facade.model.GraphData;
import org.cn.liuwt.llmwiki.facade.model.GraphOverviewData;
import org.cn.liuwt.llmwiki.facade.model.RelatedPageInfo;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WikiFileServiceImpl implements WikiFileService {

    private static final Logger log = LoggerFactory.getLogger(WikiFileServiceImpl.class);

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private WikiPageTagMapper wikiPageTagMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SearchService searchService;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Override
    public WikiPageModel readPage(String path) {
        throw new UnsupportedOperationException(
            "readPage(path) 已废弃：必须通过 readPageByScopeId(path, scopeId) 显式传入 scopeId");
    }

    @Override
    public void writePage(String path, String content) {
        throw new UnsupportedOperationException(
            "writePage(path, content) 已废弃：必须通过 writePageByScopeId(path, scopeId, content) 显式传入 scopeId");
    }

    @Override
    public List<WikiPageModel> listPages() {
        throw new UnsupportedOperationException(
            "listPages() 已废弃：必须通过 listPagesByScopeId(scopeId) 显式传入 scopeId");
    }

    @Override
    public String searchPages(String query) {
        throw new UnsupportedOperationException(
            "searchPages(query) 已废弃：必须通过 SearchService.search(scopeId, ...) 调用");
    }

    public List<WikiPageModel> listPagesByScopeId(Long scopeId) {
        return listPagesByScopeId(scopeId, 1, 200);
    }

    public List<WikiPageModel> listPagesByScopeId(Long scopeId, int page, int pageSize) {
        int safePageSize = Math.min(pageSize, 200);
        int offset = Math.max(0, (page - 1)) * safePageSize;
        List<WikiPageDO> pageDOs = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .orderByDesc(WikiPageDO::getContentUpdatedAt)
                .last("LIMIT " + safePageSize + " OFFSET " + offset)
        );
        return pageDOs.stream().map(this::toModel).collect(Collectors.toList());
    }

    public List<WikiPageModel> listPagesByCategory(Long scopeId, String category, int page, int pageSize) {
        int safePageSize = Math.min(pageSize, 200);
        int offset = Math.max(0, (page - 1)) * safePageSize;
        List<WikiPageDO> pageDOs = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .and(w -> w.eq(WikiPageDO::getCategory, category)
                    .or()
                    .likeRight(WikiPageDO::getCategory, category + "/"))
                .orderByDesc(WikiPageDO::getContentUpdatedAt)
                .last("LIMIT " + safePageSize + " OFFSET " + offset)
        );
        return pageDOs.stream().map(this::toModel).collect(Collectors.toList());
    }

    public long countEntityPagesByScopeId(Long scopeId) {
        return wikiPageMapper.selectCount(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getLifecycleStatus, "ACTIVE")
                .ne(WikiPageDO::getPageType, "reference")
        );
    }

    public List<Map<String, Object>> getHealthDistribution(Long scopeId) {
        return wikiPageMapper.selectHealthDistributionByScope(scopeId);
    }

    public List<String> listDistinctCategories(Long scopeId) {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiPageDO> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.select("DISTINCT category")
            .eq("scope_id", scopeId)
            .isNotNull("category")
            .ne("category", "");
        return wikiPageMapper.selectList(wrapper).stream()
            .map(WikiPageDO::getCategory)
            .collect(Collectors.toList());
    }

    public WikiPageModel readPageByScopeId(String path, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getFilePath, path)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            return null;
        }
        return enrichModelWithContent(pageDO, scopeId);
    }

    public WikiPageModel readPageById(Long id, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            return null;
        }
        return enrichModelWithContent(pageDO, scopeId);
    }

    public WikiPageModel readPageByFilePath(String filePath, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getFilePath, filePath)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            return null;
        }
        return enrichModelWithContent(pageDO, scopeId);
    }

    private WikiPageModel enrichModelWithContent(WikiPageDO pageDO, Long scopeId) {
        WikiPageModel model = toModel(pageDO);
        String scopeIdStr = String.valueOf(scopeId);
        String storagePath = "wiki/" + pageDO.getFilePath();
        if (storageProvider.exists(scopeIdStr, storagePath)) {
            byte[] content = storageProvider.read(scopeIdStr, storagePath);
            if (content != null) {
                model.setContent(new String(content, StandardCharsets.UTF_8));
            }
        }
        return model;
    }

    public void writePageByScopeId(String path, Long scopeId, String content) {
        PathGuard.assertWritable(path);
        String scopeIdStr = String.valueOf(scopeId);
        storageProvider.write(scopeIdStr, "wiki/" + PathGuard.normalize(path), content.getBytes(StandardCharsets.UTF_8));
    }

    public List<WikiPageModel> getRelatedPages(String path, Long scopeId) {
        WikiPageDO currentPage = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getFilePath, path)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (currentPage == null) {
            return List.of();
        }
        return getRelatedPagesByIdInternal(currentPage.getId(), scopeId);
    }

    public List<RelatedPageInfo> getRelatedPagesById(Long id, Long scopeId) {
        WikiPageDO currentPage = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (currentPage == null) {
            return List.of();
        }

        List<WikiPageLinkDO> outgoingLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, currentPage.getId())
        );
        List<WikiPageLinkDO> incomingLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getToPageId, currentPage.getId())
        );

        List<Long> relatedPageIds = new ArrayList<>();
        for (WikiPageLinkDO link : outgoingLinks) {
            relatedPageIds.add(link.getToPageId());
        }
        for (WikiPageLinkDO link : incomingLinks) {
            relatedPageIds.add(link.getFromPageId());
        }
        Map<Long, WikiPageDO> pageMap;
        if (!relatedPageIds.isEmpty()) {
            List<WikiPageDO> pages = wikiPageMapper.selectBatchIds(relatedPageIds);
            pageMap = pages.stream().collect(Collectors.toMap(WikiPageDO::getId, p -> p));
        } else {
            pageMap = Map.of();
        }
        List<RelatedPageInfo> results = new ArrayList<>();

        for (WikiPageLinkDO link : outgoingLinks) {
            WikiPageDO target = pageMap.get(link.getToPageId());
            if (target != null) {
                RelatedPageInfo r = new RelatedPageInfo();
                r.setId(target.getId());
                r.setTitle(target.getTitle());
                r.setPath(target.getFilePath());
                r.setSummary(target.getSummary());
                r.setLinkType(link.getLinkType());
                r.setDirection("outgoing");
                results.add(r);
            }
        }

        for (WikiPageLinkDO link : incomingLinks) {
            WikiPageDO source = pageMap.get(link.getFromPageId());
            if (source != null) {
                RelatedPageInfo r = new RelatedPageInfo();
                r.setId(source.getId());
                r.setTitle(source.getTitle());
                r.setPath(source.getFilePath());
                r.setSummary(source.getSummary());
                r.setLinkType(link.getLinkType());
                r.setDirection("incoming");
                results.add(r);
            }
        }

        return results;
    }

    private List<WikiPageModel> getRelatedPagesByIdInternal(Long pageId, Long scopeId) {

        List<WikiPageLinkDO> outgoingLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageId)
        );
        List<WikiPageLinkDO> incomingLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getToPageId, pageId)
        );

        List<Long> relatedPageIds = new ArrayList<>();
        for (WikiPageLinkDO link : outgoingLinks) {
            relatedPageIds.add(link.getToPageId());
        }
        for (WikiPageLinkDO link : incomingLinks) {
            relatedPageIds.add(link.getFromPageId());
        }

        if (relatedPageIds.isEmpty()) {
            return List.of();
        }

        List<WikiPageDO> relatedPages = wikiPageMapper.selectBatchIds(relatedPageIds);
        return relatedPages.stream().map(this::toModel).collect(Collectors.toList());
    }

    public List<String> getPageTags(Long pageId, Long scopeId) {
        List<WikiPageTagDO> tags = wikiPageTagMapper.selectList(
            new LambdaQueryWrapper<WikiPageTagDO>()
                .eq(WikiPageTagDO::getScopeId, scopeId)
                .eq(WikiPageTagDO::getPageId, pageId)
        );
        return tags.stream().map(WikiPageTagDO::getTag).collect(Collectors.toList());
    }

    public List<String> getPageKeywords(Long pageId, Long scopeId) {
        List<WikiPageKeywordDO> keywords = wikiPageKeywordMapper.selectList(
            new LambdaQueryWrapper<WikiPageKeywordDO>()
                .eq(WikiPageKeywordDO::getScopeId, scopeId)
                .eq(WikiPageKeywordDO::getPageId, pageId)
        );
        return keywords.stream().map(WikiPageKeywordDO::getKeyword).collect(Collectors.toList());
    }

    public List<SourceDO> getPageSources(Long pageId, Long scopeId) {
        List<WikiPageSourceDO> pageSources = wikiPageSourceMapper.selectList(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getPageId, pageId)
        );
        List<Long> sourceIds = pageSources.stream().map(WikiPageSourceDO::getSourceId).collect(Collectors.toList());
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        return sourceMapper.selectBatchIds(sourceIds);
    }

    public String getIndexContent(Long scopeId) {
        return "";
    }

    /**
     * 符号表重整（知识编译隐喻）：重新按分类聚合该 scope 下所有页面，覆盖写入 wiki/index.md。
     * 取代原有的追加式写入，保证索引页始终容易导航。
     */
    public void rebuildWikiIndex(Long scopeId) {
        log.warn("rebuildWikiIndex is deprecated — FS index.md is no longer maintained. scopeId={}", scopeId);
    }

    /**
     * @deprecated 语义已变：不再追加单行，而是触发全量重整。参数仅为向后兼容保留。调用方请直接使用 {@link #rebuildWikiIndex(Long)}。
     */
    @Deprecated
    public void appendToIndex(Long scopeId, String pageTitle, String summary, String category) {
        rebuildWikiIndex(scopeId);
    }

    public String getLogContent(Long scopeId) {
        return "";
    }

    public List<WikiPageDO> getAllPagesByScopeId(Long scopeId) {
        return wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                    WikiPageDO::getCategory, WikiPageDO::getHealthStatus,
                    WikiPageDO::getPageType, WikiPageDO::getSourceCount,
                    WikiPageDO::getLifecycleStatus)
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getLifecycleStatus, "ACTIVE", "DEPRECATED", "MERGED")
        );
    }

    public List<WikiPageLinkDO> getAllLinksByScopeId(Long scopeId) {
        return wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .select(WikiPageLinkDO::getFromPageId, WikiPageLinkDO::getToPageId,
                    WikiPageLinkDO::getLinkType, WikiPageLinkDO::getLinkContext)
                .eq(WikiPageLinkDO::getScopeId, scopeId)
        );
    }

    public GraphData getLocalGraph(Long pageId, Long scopeId) {
        List<WikiPageLinkDO> hop1Outgoing = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageId)
        );
        List<WikiPageLinkDO> hop1Incoming = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getToPageId, pageId)
        );

        java.util.Set<Long> hop1Ids = new java.util.LinkedHashSet<>();
        for (WikiPageLinkDO l : hop1Outgoing) hop1Ids.add(l.getToPageId());
        for (WikiPageLinkDO l : hop1Incoming) hop1Ids.add(l.getFromPageId());

        java.util.Set<Long> allNodeIds = new java.util.LinkedHashSet<>();
        allNodeIds.add(pageId);
        allNodeIds.addAll(hop1Ids);

        List<WikiPageLinkDO> hop2Links = new ArrayList<>();
        if (!hop1Ids.isEmpty()) {
            List<Long> hop1List = new ArrayList<>(hop1Ids);
            hop2Links.addAll(wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .in(WikiPageLinkDO::getFromPageId, hop1List)
            ));
            hop2Links.addAll(wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .in(WikiPageLinkDO::getToPageId, hop1List)
            ));
            for (WikiPageLinkDO l : hop2Links) {
                allNodeIds.add(l.getFromPageId());
                allNodeIds.add(l.getToPageId());
            }
        }

        List<WikiPageDO> allPages = wikiPageMapper.selectBatchIds(new ArrayList<>(allNodeIds));
        java.util.Set<Long> validNodeIds = new java.util.HashSet<>();
        for (WikiPageDO p : allPages) {
            if (!"DELETED".equals(p.getLifecycleStatus())) {
                validNodeIds.add(p.getId());
            }
        }

        java.util.Map<Long, Integer> inDeg = new java.util.HashMap<>();
        java.util.Map<Long, Integer> outDeg = new java.util.HashMap<>();
        java.util.Set<String> edgeKeys = new java.util.HashSet<>();
        List<WikiPageLinkDO> relevantLinks = new ArrayList<>();
        relevantLinks.addAll(hop1Outgoing);
        relevantLinks.addAll(hop1Incoming);
        relevantLinks.addAll(hop2Links);
        for (WikiPageLinkDO l : relevantLinks) {
            String key = l.getFromPageId() + "->" + l.getToPageId();
            if (edgeKeys.add(key)) {
                if (validNodeIds.contains(l.getFromPageId()) && validNodeIds.contains(l.getToPageId())) {
                    outDeg.merge(l.getFromPageId(), 1, Integer::sum);
                    inDeg.merge(l.getToPageId(), 1, Integer::sum);
                }
            }
        }

        List<GraphData.GraphNode> nodes = new ArrayList<>();
        for (WikiPageDO p : allPages) {
            if (!validNodeIds.contains(p.getId())) continue;
            GraphData.GraphNode node = new GraphData.GraphNode();
            node.setId(p.getId());
            node.setTitle(p.getTitle());
            node.setPath(p.getFilePath());
            node.setCategory(p.getCategory());
            node.setHealthStatus(p.getHealthStatus());
            node.setPageType(p.getPageType());
            node.setLifecycleStatus(p.getLifecycleStatus());
            node.setSourceCount(p.getSourceCount());
            node.setInDegree(inDeg.getOrDefault(p.getId(), 0));
            node.setOutDegree(outDeg.getOrDefault(p.getId(), 0));
            nodes.add(node);
        }

        List<GraphData.GraphEdge> edges = new ArrayList<>();
        for (String key : edgeKeys) {
            String[] parts = key.split("->");
            Long from = Long.parseLong(parts[0]);
            Long to = Long.parseLong(parts[1]);
            if (validNodeIds.contains(from) && validNodeIds.contains(to)) {
                GraphData.GraphEdge edge = new GraphData.GraphEdge();
                edge.setFromId(from);
                edge.setToId(to);
                edge.setLinkType("related");
                edges.add(edge);
            }
        }

        GraphData graphData = new GraphData();
        graphData.setNodes(nodes);
        graphData.setEdges(edges);
        return graphData;
    }

    public GraphOverviewData getGraphOverview(Long scopeId) {
        List<Map<String, Object>> categoryStats = wikiPageMapper.selectCategoryStats(scopeId);
        List<Map<String, Object>> degreeStats = wikiPageMapper.selectCategoryDegreeStats(scopeId);
        List<Map<String, Object>> interEdges = wikiPageMapper.selectInterCategoryEdges(scopeId);

        Map<String, Map<String, Object>> degreeMap = new LinkedHashMap<>();
        for (Map<String, Object> row : degreeStats) {
            degreeMap.put((String) row.get("category"), row);
        }

        int totalNodes = 0;
        int totalOrphans = 0;
        int totalHubs = 0;
        int totalConflicts = 0;
        int totalNeedsUpdate = 0;
        int totalHasProblems = 0;

        List<GraphOverviewData.CategoryNode> categoryNodes = new ArrayList<>();
        List<GraphData.CategoryCount> categoryCounts = new ArrayList<>();

        for (Map<String, Object> row : categoryStats) {
            String cat = (String) row.get("category");
            int pageCount = ((Number) row.get("page_count")).intValue();
            int conflictCount = ((Number) row.get("conflict_count")).intValue();
            int needsUpdateCount = row.get("needs_update_count") != null ? ((Number) row.get("needs_update_count")).intValue() : 0;
            int hasProblemsCount = row.get("has_problems_count") != null ? ((Number) row.get("has_problems_count")).intValue() : 0;

            Map<String, Object> deg = degreeMap.get(cat);
            int orphanCount = deg != null ? ((Number) deg.get("orphan_count")).intValue() : 0;
            int hubCount = deg != null ? ((Number) deg.get("hub_count")).intValue() : 0;
            double avgDegree = deg != null ? ((Number) deg.get("avg_degree")).doubleValue() : 0;

            GraphOverviewData.CategoryNode node = new GraphOverviewData.CategoryNode();
            node.setCategory(cat);
            node.setPageCount(pageCount);
            node.setOrphanCount(orphanCount);
            node.setConflictCount(conflictCount);
            node.setNeedsUpdateCount(needsUpdateCount);
            node.setHasProblemsCount(hasProblemsCount);
            node.setHubCount(hubCount);
            node.setAvgDegree(avgDegree);
            categoryNodes.add(node);

            GraphData.CategoryCount cc = new GraphData.CategoryCount();
            cc.setCategory(cat);
            cc.setCount(pageCount);
            categoryCounts.add(cc);

            totalNodes += pageCount;
            totalOrphans += orphanCount;
            totalHubs += hubCount;
            totalConflicts += conflictCount;
            totalNeedsUpdate += needsUpdateCount;
            totalHasProblems += hasProblemsCount;
        }

        int totalEdges = 0;
        List<GraphOverviewData.CategoryEdge> edges = new ArrayList<>();
        for (Map<String, Object> row : interEdges) {
            int linkCount = ((Number) row.get("link_count")).intValue();
            totalEdges += linkCount;
            GraphOverviewData.CategoryEdge edge = new GraphOverviewData.CategoryEdge();
            edge.setFromCategory((String) row.get("from_category"));
            edge.setToCategory((String) row.get("to_category"));
            edge.setLinkCount(linkCount);
            edges.add(edge);
        }

        GraphData.GraphStats stats = new GraphData.GraphStats();
        stats.setTotalNodes(totalNodes);
        stats.setTotalEdges(totalEdges);
        stats.setOrphanCount(totalOrphans);
        stats.setHubCount(totalHubs);
        stats.setConflictCount(totalConflicts);
        stats.setNeedsUpdateCount(totalNeedsUpdate);
        stats.setHasProblemsCount(totalHasProblems);
        stats.setLinkDensity(totalNodes > 0 ? (double) totalEdges / totalNodes : 0);
        stats.setCategoryCounts(categoryCounts);

        GraphOverviewData overview = new GraphOverviewData();
        overview.setCategories(categoryNodes);
        overview.setEdges(edges);
        overview.setStats(stats);
        return overview;
    }

    private static final int GRAPH_CATEGORY_NODE_LIMIT = 800;

    public GraphData getGraphByCategory(Long scopeId, String category) {
        List<WikiPageDO> pages = wikiPageMapper.selectPagesByCategory(scopeId, category);
        if (pages.size() > GRAPH_CATEGORY_NODE_LIMIT) {
            pages = pages.subList(0, GRAPH_CATEGORY_NODE_LIMIT);
        }
        java.util.Set<Long> pageIds = new java.util.HashSet<>();
        for (WikiPageDO p : pages) {
            pageIds.add(p.getId());
        }

        Map<Long, Integer> inDeg = new java.util.HashMap<>();
        Map<Long, Integer> outDeg = new java.util.HashMap<>();
        List<GraphData.GraphEdge> edges = new ArrayList<>();

        if (!pageIds.isEmpty()) {
            List<WikiPageLinkDO> links = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .select(WikiPageLinkDO::getFromPageId, WikiPageLinkDO::getToPageId,
                        WikiPageLinkDO::getLinkType, WikiPageLinkDO::getLinkContext)
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .in(WikiPageLinkDO::getFromPageId, pageIds)
            );
            for (WikiPageLinkDO l : links) {
                if (pageIds.contains(l.getToPageId())) {
                    outDeg.merge(l.getFromPageId(), 1, Integer::sum);
                    inDeg.merge(l.getToPageId(), 1, Integer::sum);
                    GraphData.GraphEdge edge = new GraphData.GraphEdge();
                    edge.setFromId(l.getFromPageId());
                    edge.setToId(l.getToPageId());
                    edge.setLinkType(l.getLinkType());
                    edge.setLinkContext(l.getLinkContext());
                    edges.add(edge);
                }
            }
        }

        int orphanCount = 0;
        int hubCount = 0;
        int conflictCount = 0;
        int needsUpdateCount = 0;
        int hasProblemsCount = 0;
        int deprecatedCount = 0;
        int mergedCount = 0;

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
            int inD = inDeg.getOrDefault(p.getId(), 0);
            int outD = outDeg.getOrDefault(p.getId(), 0);
            node.setInDegree(inD);
            node.setOutDegree(outD);
            nodes.add(node);

            if (inD + outD == 0) orphanCount++;
            if (inD + outD >= 5) hubCount++;
            String hs = p.getHealthStatus();
            if ("conflict-warning".equals(hs) || "has-problems".equals(hs)) conflictCount++;
            if ("needs-update".equals(hs)) needsUpdateCount++;
            if ("has-problems".equals(hs)) hasProblemsCount++;
            String ls = p.getLifecycleStatus();
            if ("DEPRECATED".equals(ls)) deprecatedCount++;
            if ("MERGED".equals(ls)) mergedCount++;
        }

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
        stats.setCategoryCounts(List.of());

        GraphData graphData = new GraphData();
        graphData.setNodes(nodes);
        graphData.setEdges(edges);
        graphData.setStats(stats);
        return graphData;
    }

    public GraphData searchGraphNodes(Long scopeId, String keyword, int limit) {
        List<WikiPageDO> pages = wikiPageMapper.searchPagesForGraph(scopeId, keyword, limit);
        if (pages.isEmpty()) {
            GraphData empty = new GraphData();
            empty.setNodes(List.of());
            empty.setEdges(List.of());
            return empty;
        }

        java.util.Set<Long> pageIds = new java.util.HashSet<>();
        for (WikiPageDO p : pages) {
            pageIds.add(p.getId());
        }

        Map<Long, Integer> inDeg = new java.util.HashMap<>();
        Map<Long, Integer> outDeg = new java.util.HashMap<>();
        List<WikiPageLinkDO> outLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .select(WikiPageLinkDO::getFromPageId, WikiPageLinkDO::getToPageId,
                    WikiPageLinkDO::getLinkType, WikiPageLinkDO::getLinkContext)
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .in(WikiPageLinkDO::getFromPageId, pageIds)
        );
        List<WikiPageLinkDO> inLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .select(WikiPageLinkDO::getFromPageId, WikiPageLinkDO::getToPageId,
                    WikiPageLinkDO::getLinkType, WikiPageLinkDO::getLinkContext)
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .in(WikiPageLinkDO::getToPageId, pageIds)
        );

        java.util.Set<String> edgeKeys = new java.util.HashSet<>();
        List<GraphData.GraphEdge> edges = new ArrayList<>();
        for (WikiPageLinkDO l : outLinks) {
            outDeg.merge(l.getFromPageId(), 1, Integer::sum);
            String key = l.getFromPageId() + "->" + l.getToPageId();
            if (edgeKeys.add(key) && pageIds.contains(l.getToPageId())) {
                GraphData.GraphEdge edge = new GraphData.GraphEdge();
                edge.setFromId(l.getFromPageId());
                edge.setToId(l.getToPageId());
                edge.setLinkType(l.getLinkType());
                edge.setLinkContext(l.getLinkContext());
                edges.add(edge);
            }
        }
        for (WikiPageLinkDO l : inLinks) {
            inDeg.merge(l.getToPageId(), 1, Integer::sum);
            String key = l.getFromPageId() + "->" + l.getToPageId();
            if (edgeKeys.add(key) && pageIds.contains(l.getFromPageId())) {
                GraphData.GraphEdge edge = new GraphData.GraphEdge();
                edge.setFromId(l.getFromPageId());
                edge.setToId(l.getToPageId());
                edge.setLinkType(l.getLinkType());
                edge.setLinkContext(l.getLinkContext());
                edges.add(edge);
            }
        }

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
            node.setInDegree(inDeg.getOrDefault(p.getId(), 0));
            node.setOutDegree(outDeg.getOrDefault(p.getId(), 0));
            nodes.add(node);
        }

        GraphData graphData = new GraphData();
        graphData.setNodes(nodes);
        graphData.setEdges(edges);
        return graphData;
    }

    public List<WikiPageModel> getRecommendedPages(Long scopeId) {
        List<WikiPageDO> candidatePages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .ne(WikiPageDO::getVisibility, "private")
                .ne(WikiPageDO::getHealthStatus, "recalled")
                .isNotNull(WikiPageDO::getCategory)
                .ne(WikiPageDO::getCategory, "")
                .orderByDesc(WikiPageDO::getSourceCount)
                .last("LIMIT 200")
        );

        if (candidatePages.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = candidatePages.stream().map(WikiPageDO::getId).collect(Collectors.toList());

        List<WikiPageLinkDO> incomingLinks = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .in(WikiPageLinkDO::getToPageId, candidateIds)
        );
        Map<Long, Integer> linkCountByPageId = new LinkedHashMap<>();
        for (WikiPageLinkDO link : incomingLinks) {
            linkCountByPageId.merge(link.getToPageId(), 1, Integer::sum);
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<PageScore> scored = new ArrayList<>();
        for (WikiPageDO pageDO : candidatePages) {
            int linkCount = linkCountByPageId.getOrDefault(pageDO.getId(), 0);
            int srcCount = pageDO.getSourceCount() != null ? pageDO.getSourceCount() : 0;

            double srcScore = Math.log(1 + srcCount) * 5;
            int score = (int) (linkCount * 10 + srcScore);

            String health = pageDO.getHealthStatus();
            if ("healthy".equals(health)) {
                score += 10;
            } else if ("needs-update".equals(health)) {
                score += 5;
            }

            if (pageDO.getContentUpdatedAt() != null) {
                long daysAgo = java.time.Duration.between(pageDO.getContentUpdatedAt(), now).toDays();
                double decay = Math.max(0.3, 1.0 - (daysAgo * 0.005));
                score = (int) (score * decay);
            }

            scored.add(new PageScore(pageDO, Math.max(score, 1)));
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        List<WikiPageModel> result = new ArrayList<>();
        int limit = 5;
        for (PageScore ps : scored) {
            if (result.size() >= limit) break;
            String topLevelCat = getTopLevelCategory(ps.pageDO.getCategory());
            int count = categoryCount.getOrDefault(topLevelCat, 0);
            if (count >= 2) continue;
            categoryCount.put(topLevelCat, count + 1);
            result.add(toModel(ps.pageDO));
        }
        return result;
    }

    private String getTopLevelCategory(String category) {
        if (category == null || category.isEmpty()) return "未分类";
        int slashIdx = category.indexOf("/");
        return slashIdx > 0 ? category.substring(0, slashIdx) : category;
    }

    private static class PageScore {
        final WikiPageDO pageDO;
        final int score;

        PageScore(WikiPageDO pageDO, int score) {
            this.pageDO = pageDO;
            this.score = score;
        }
    }

    /**
     * 初始化 Wiki 数据目录和示例页面。
     * 索引不再写入 FS index.md，统一由 DB（wiki_page 表 + GlobalSummaryService）管理。
     */
    public void initWikiData(Long scopeId) {
        String scopeIdStr = String.valueOf(scopeId);
        storageProvider.ensureBucket(scopeIdStr);
        createSamplePages(scopeId);
    }

    private void createSamplePages(Long scopeId) {
        String scopeIdStr = String.valueOf(scopeId);
        String[][] samplePages = {
            {"pages/llm-wiki-overview.md", "LLM Wiki 系统概览", "系统介绍",
             "LLM Wiki 的核心理念：不是检索，而是知识编译——AI 增量构建并维护持久化的知识库。",
             "# LLM Wiki 系统概览\n\nLLM Wiki 是一个基于大语言模型（LLM）的知识管理系统。与传统的 RAG（检索增强生成）系统不同，LLM Wiki 不是每次查询时从原始文档中检索片段，而是**增量构建并维护一个持久化的 Wiki**。\n\n## 核心理念：知识编译，而非检索\n\n大多数人对 LLM 与文档的认知停留在 RAG 模式：上传文件 → 检索相关片段 → 生成回答。这种方式的问题是每次查询都在从零开始「重新发现」知识——没有积累，没有沉淀。\n\nLLM Wiki 的思路不同：\n\n- **知识是编译出来的**：当添加新资料时，LLM 不只是建立索引，而是真正「阅读」内容，提取关键信息，并将其整合到已有的 Wiki 结构中去\n- **知识会持续生长**：每次摄入和问答，Wiki 都在变得更丰富、更完善\n- **维护成本趋近于零**：LLM 负责所有的摘要、交叉引用、归档和整理工作\n\n## 系统三层架构\n\nLLM Wiki 采用三层架构设计：\n\n### 原始资料层（Raw Sources）\n\n存放你收集的源文件——文章、论文、图片、数据文件等。这些文件是**不可变的**，LLM 只读取不修改。这是你的真相来源。\n\n### Wiki 知识层\n\nLLM 生成并维护的 Markdown 文件集合，包括：\n\n- **主题页面**：实体、概念、技术的详细页面\n- **摘要页面**：来源文档的概览\n- **全局摘要**：由数据库 wiki_page 表聚合的分类索引，提供目录导航\n- **执行历史**：记录在 execution 表中，可追溯每次摄入/查询/体检的操作\n\nLLM 完全拥有这一层——创建页面、更新内容、维护交叉引用、保持一致性。\n\n### Schema 配置层\n\n一份配置文档，定义了 Wiki 的结构规范、命名约定和操作流程。它是你和 LLM 共同演化的——随着你对知识管理需求的深入，Schema 也在不断优化。\n\n## 为什么这种方式有效\n\n维护知识库最繁琐的部分不是阅读或思考，而是**记录管理**——更新交叉引用、保持摘要同步、标注新旧数据矛盾、维护数十个页面的一致性。人类放弃 Wiki 是因为维护成本增长超过价值增长。LLM 不会厌倦、不会忘记更新引用，可以在一次操作中触达 15 个文件。Wiki 能持续保持健康，因为维护成本几乎为零。\n\n你的工作是：策划资料源、引导分析方向、提出好问题、思考这一切意味着什么。LLM 处理其余的所有事情。\n\n> 这个理念在精神上与 Vannevar Bush 1945 年提出的 Memex 愿景相通——一个私人的、精心策划的知识存储，文档之间的关联路径与文档本身同等珍贵。Bush 没能解决的是「谁来维护」的问题，LLM 解决了。\n\n## 相关页面\n\n- [[知识摄入详解]]\n- [[知识查询详解]]\n- [[知识体检详解]]\n"},
            {"pages/ingest-guide.md", "知识摄入详解", "操作指南",
             "知识摄入不是简单的上传+索引，而是 AI 阅读、分析、整合的完整知识编译流程。",
             "# 知识摄入（Ingest）详解\n\n知识摄入是 LLM Wiki 的核心操作之一。它不仅仅是「上传并索引」——而是一个完整的知识编译过程。\n\n## 什么是摄入\n\n当你在原始资料库中放入一个新的来源文件，并告诉 LLM 处理它时，LLM 会执行以下流程：\n\n1. **读取来源**：LLM 读取原始文件，理解其内容\n2. **分析讨论**：与你讨论关键要点，确认理解和侧重点\n3. **写入摘要页**：在 Wiki 中创建该来源的摘要页面\n4. **更新数据库索引**：将新页面信息注册到 wiki_page 表\n5. **更新关联页面**：在整个 Wiki 范围内更新相关的实体和概念页面\n6. **记录执行历史**：在 execution 表中记录本次摄入操作\n\n一个单一的来源文件，可能触达 10-15 个 Wiki 页面。\n\n## 摄入策略\n\n### 逐个摄入（推荐）\n\n逐个处理来源文件，保持参与感——阅读摘要、检查更新、引导 LLM 强调重点内容。这种方式让你始终掌握知识库的演化方向。\n\n### 批量摄入\n\n一次性处理多个来源文件，监督程度较低，适合大量资料入库的场景。\n\n选择哪种策略取决于你的知识管理风格。你可以在使用过程中逐步形成自己的工作流，并将其记录在 Schema 中供后续使用。\n\n## 与 RAG 的根本区别\n\n传统的 RAG 系统只是为后续检索建立索引——原始文档原封不动，每次查询时重新检索片段。而摄入操作是**真正的知识编译**：新知识被提取、整合到已有结构中，Wiki 因此变得更完整、更具联结性。\n\n## 实操建议\n\n- **Obsidian Web Clipper**：浏览器扩展，可一键将网页文章转为 Markdown，快速充实原始资料库\n- **图片本地化**：在 Obsidian 中将附件路径设为固定目录，使用快捷键下载图片到本地，让 LLM 也能查看和引用图片\n- 将 LLM Agent 打开在一边，Obsidian 打开在另一边——LLM 编辑 Wiki，你实时浏览结果\n\n## 相关页面\n\n- [[LLM Wiki 系统概览]]\n- [[知识查询详解]]\n"},
            {"pages/query-guide.md", "知识查询详解", "操作指南",
             "与经过编译的知识体系对话：LLM 读取 Wiki 页面综合回答，好答案可沉淀为新页面。",
             "# 知识查询（Query）详解\n\n知识查询让你与 Wiki 对话——不是与零散的文档片段对话，而是与一个经过编译、有机生长的知识体系对话。\n\n## 查询工作方式\n\n当你提出一个问题时，LLM 并不是在一个巨大的向量数据库里找相关片段，而是：\n\n1. **分析全局摘要**：首先查看知识库的分类索引（由 wiki_page 表聚合），定位相关页面\n2. **深入页面**：打开并阅读相关页面的完整内容\n3. **综合回答**：基于 Wiki 中已有的知识，生成有引用来源的回答\n\n这种方式的优势在于：交叉引用已经就位、矛盾已经被标记、综合结论已经反映了所有已摄入的资料。你不必每次都在文档片段中重新拼凑答案。\n\n## 多种输出格式\n\n根据问题的性质，回答可以呈现为不同格式：\n\n- **Markdown 页面**：标准的知识回答，带引用标注\n- **对比表格**：适合差异/对比类问题\n- **幻灯片（Marp）**：适合汇报和分享场景，可直接演示\n- **图表**：适合趋势和分布类问题，可视化呈现\n\n## 好答案存入 Wiki\n\n这是 LLM Wiki 最关键的设计理念之一：**好的回答不应该消失在对话历史中**。\n\n当你进行了一次有价值的对比分析、发现了一个有趣的联系、或获得了一个深入的综合结论——你可以将其保存为 Wiki 的新页面。这样，探索的成果也会像摄入的资料一样，在知识库中持续积累。\n\n举例：\n- 你问「A 方案和 B 方案有什么区别？」→ AI 生成对比表格 → 保存到 Wiki → 以后每次查看都有这份对比\n- 你问「这个领域的核心趋势是什么？」→ AI 综合多个页面给出分析 → 保存到 Wiki → 成为新的知识节点\n\n## 相关页面\n\n- [[LLM Wiki 系统概览]]\n- [[知识摄入详解]]\n- [[知识体检详解]]\n"},
            {"pages/lint-guide.md", "知识体检详解", "操作指南",
             "定期健康检查 Wiki：检测矛盾、过时声明、孤立页面、缺失引用和知识缺口。",
             "# 知识体检（Lint）详解\n\n知识体检是 Wiki 的健康检查机制。随着 Wiki 的增长，定期检查其健康状况至关重要。\n\n## 检查项目\n\nLLM 会定期扫描 Wiki，检测以下问题：\n\n### 矛盾检测\n\n不同页面之间是否存在相互矛盾的陈述？新摄入的资料是否与旧结论冲突？当新旧信息打架时，需要标记出来供你判断。\n\n### 过时声明\n\n是否有被更新的资料来源所取代的陈旧声明？Wiki 是活的——旧知识应该被标注或更新，而不是悄悄过时。\n\n### 孤立页面\n\n是否有没有任何入站链接的页面？这些页面可能是被遗忘的知识孤岛，需要重新连接到 Wiki 网络中。\n\n### 缺失交叉引用\n\n重要概念是否在文中被提及但没有自己的页面？是否缺少应有的链接？交叉引用越丰富，Wiki 的导航和发现体验越好。\n\n### 知识缺口\n\n是否有可以通过网络搜索填补的信息空白？LLM 可以建议新的问题来探索和新的资料来补充。\n\n## 体检频率\n\n- **个人知识库**：建议每周体检一次\n- **团队知识库**：建议每天自动体检\n\n定期体检确保 Wiki 在持续增长的同时保持健康、一致和可导航。\n\n## 体检的智慧\n\nLLM 擅长发现人类难以察觉的模式——某个页面三个月没更新，某两个概念之间的关系虽然没写出来但隐含在多个地方，某个重要话题在索引中完全缺失。这些「体检发现」让知识库的维护变得主动而非被动。\n\n随着 Wiki 规模增长（数百个页面、上百个来源），人工检查变得不可行。Lint 机制让知识库在规模扩大的同时保持质量不退化。\n\n## 相关页面\n\n- [[LLM Wiki 系统概览]]\n- [[知识查询详解]]\n"}
        };

        for (String[] page : samplePages) {
            String filePath = page[0];
            String title = page[1];
            String category = page[2];
            String summary = page[3];
            String content = page[4];

            String storagePath = "wiki/" + filePath;
            if (!storageProvider.exists(scopeIdStr, storagePath)) {
                storageProvider.write(scopeIdStr, storagePath, content.getBytes(StandardCharsets.UTF_8));
            }

            long existing = wikiPageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getFilePath, filePath)
                    .eq(WikiPageDO::getScopeId, scopeId)
            );
            if (existing == 0) {
                WikiPageDO pageDO = new WikiPageDO();
                pageDO.setTitle(title);
                pageDO.setFilePath(filePath);
                pageDO.setCategory(category);
                pageDO.setSummary(summary);
                pageDO.setScopeId(scopeId);
                pageDO.setSourceCount(0);
                pageDO.setHealthStatus("healthy");
                pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
                wikiPageMapper.insert(pageDO);
                syncPageToIndex(pageDO, scopeId);
            }
        }
    }

    public void updatePageHealthStatus(Long pageId, String healthStatus) {
        WikiPageDO pageDO = wikiPageMapper.selectById(pageId);
        if (pageDO != null) {
            pageDO.setHealthStatus(healthStatus);
            wikiPageMapper.updateById(pageDO);
            syncPageToIndex(pageDO, pageDO.getScopeId());
        }
    }

    public void deprecatePage(Long id, Long scopeId, String reason) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            throw new RuntimeException("Page not found: id=" + id);
        }
        if (PageLifecycle.MERGING.name().equals(pageDO.getLifecycleStatus())) {
            throw new RuntimeException("该页面正在合并中，无法标记为过时");
        }
        pageDO.setDeprecatedAt(LocalDateTime.now());
        pageDO.setDeprecatedReason(reason);
        pageDO.setLifecycleStatus(PageLifecycle.DEPRECATED.name());
        wikiPageMapper.updateById(pageDO);
        syncPageToIndex(pageDO, scopeId);
        globalSummaryService.invalidate(scopeId);
        log.info("Page deprecated: scopeId={}, pageId={}, reason={}", scopeId, id, reason);
    }

    public void undeprecatePage(Long id, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            throw new RuntimeException("Page not found: id=" + id);
        }
        pageDO.setDeprecatedAt(null);
        pageDO.setDeprecatedReason(null);
        pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
        wikiPageMapper.updateById(pageDO);
        syncPageToIndex(pageDO, scopeId);
        globalSummaryService.invalidate(scopeId);
        log.info("Page undeprecated: scopeId={}, pageId={}", scopeId, id);
    }

    public void softDeletePage(Long id, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            throw new RuntimeException("Page not found: id=" + id);
        }
        if (PageLifecycle.MERGING.name().equals(pageDO.getLifecycleStatus())) {
            throw new RuntimeException("该页面正在合并中，无法删除");
        }
        pageDO.setVisibility("private");
        pageDO.setDeletedAt(LocalDateTime.now());
        pageDO.setLifecycleStatus(PageLifecycle.DELETED.name());
        wikiPageMapper.updateById(pageDO);
        try {
            searchService.removePage(scopeId, id);
        } catch (Exception e) {
            log.warn("Failed to remove page from search index: pageId={}, error={}", id, e.getMessage());
        }
        globalSummaryService.invalidate(scopeId);
        log.info("Page soft-deleted: scopeId={}, pageId={}, title={}", scopeId, id, pageDO.getTitle());
    }

    public void restorePage(Long id, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            throw new RuntimeException("Page not found: id=" + id);
        }
        if (pageDO.getDeletedAt() == null) {
            throw new RuntimeException("Page is not in trash: id=" + id);
        }
        pageDO.setVisibility("open");
        pageDO.setDeletedAt(null);
        pageDO.setLifecycleStatus(PageLifecycle.ACTIVE.name());
        wikiPageMapper.updateById(pageDO);
        syncPageToIndex(pageDO, scopeId);
        globalSummaryService.invalidate(scopeId);
        log.info("Page restored: scopeId={}, pageId={}, title={}", scopeId, id, pageDO.getTitle());
    }

    public void permanentDeletePage(Long id, Long scopeId) {
        WikiPageDO pageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getId, id)
                .eq(WikiPageDO::getScopeId, scopeId)
        );
        if (pageDO == null) {
            throw new RuntimeException("Page not found: id=" + id);
        }
        String scopeIdStr = String.valueOf(scopeId);
        String filePath = pageDO.getFilePath();
        try {
            storageProvider.delete(scopeIdStr, "wiki/" + filePath);
        } catch (Exception e) {
            log.warn("Failed to delete wiki file: wiki/{}, error={}", filePath, e.getMessage());
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiPageTagDO> tagQw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        tagQw.eq("scope_id", scopeId).eq("page_id", id);
        wikiPageTagMapper.delete(tagQw);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<WikiPageKeywordDO> kwQw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        kwQw.eq("scope_id", scopeId).eq("page_id", id);
        wikiPageKeywordMapper.delete(kwQw);
        wikiPageLinkMapper.delete(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .and(w -> w.eq(WikiPageLinkDO::getFromPageId, id)
                    .or().eq(WikiPageLinkDO::getToPageId, id))
        );
        wikiPageSourceMapper.delete(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getPageId, id)
        );
        try {
            searchService.removePage(scopeId, id);
        } catch (Exception e) {
            log.warn("Failed to remove from search index: pageId={}, error={}", id, e.getMessage());
        }
        wikiPageMapper.deleteById(id);
        globalSummaryService.invalidate(scopeId);
        log.info("Page permanently deleted: scopeId={}, pageId={}, title={}", scopeId, id, pageDO.getTitle());
    }

    public List<WikiPageModel> listTrashPages(Long scopeId) {
        List<WikiPageDO> pageDOs = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .isNotNull(WikiPageDO::getDeletedAt)
                .orderByDesc(WikiPageDO::getDeletedAt)
        );
        return pageDOs.stream().map(this::toModel).collect(Collectors.toList());
    }

    public DeleteImpactInfo getDeleteImpact(Long id, Long scopeId) {
        int inboundLinks = Math.toIntExact(wikiPageLinkMapper.selectCount(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getToPageId, id)
        ));
        int outboundLinks = Math.toIntExact(wikiPageLinkMapper.selectCount(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, id)
        ));
        int sourceCount = Math.toIntExact(wikiPageSourceMapper.selectCount(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeId)
                .eq(WikiPageSourceDO::getPageId, id)
        ));
        return new DeleteImpactInfo(inboundLinks, outboundLinks, sourceCount);
    }

    public record DeleteImpactInfo(int inboundLinks, int outboundLinks, int sourceCount) {}

    public void recalcPageHealthStatus(Long scopeId, Long pageId) {
        WikiPageDO pageDO = wikiPageMapper.selectById(pageId);
        if (pageDO == null) return;

        List<LintFindingDO> openFindings = lintFindingMapper.selectList(
            new LambdaQueryWrapper<LintFindingDO>()
                .eq(LintFindingDO::getScopeId, scopeId)
                .in(LintFindingDO::getStatus, "open", "awaiting_approval", "repairing", "deferred")
                .isNull(LintFindingDO::getArchivedAt)
                .ne(LintFindingDO::getFindingType, "schema_violation")
                .eq(LintFindingDO::getAssetId, pageId)
        );

        String newStatus;
        if (openFindings.stream().anyMatch(f -> "conflict".equals(f.getFindingType()) || "stale".equals(f.getFindingType()))) {
            newStatus = "has-problems";
        } else if (!openFindings.isEmpty()) {
            newStatus = "needs-update";
        } else {
            newStatus = "healthy";
        }

        pageDO.setHealthStatus(newStatus);
        if ("healthy".equals(newStatus)) {
            pageDO.setLastCheckedAt(LocalDateTime.now());
        }
        wikiPageMapper.updateById(pageDO);
        syncPageToIndex(pageDO, scopeId);
        log.info("recalcPageHealthStatus: scopeId={}, pageId={}, openFindings={}, newStatus={}",
            scopeId, pageId, openFindings.size(), newStatus);
    }

    private void syncPageToIndex(WikiPageDO pageDO, Long scopeId) {
        try {
            String scopeIdStr = String.valueOf(scopeId);
            String content = "";
            String storagePath = "wiki/" + pageDO.getFilePath();
            if (storageProvider.exists(scopeIdStr, storagePath)) {
                byte[] contentBytes = storageProvider.read(scopeIdStr, storagePath);
                if (contentBytes != null) {
                    content = new String(contentBytes, StandardCharsets.UTF_8);
                } else {
                    log.warn("syncPageToIndex: file read returned null for storagePath={}", storagePath);
                }
            } else {
                log.warn("syncPageToIndex: file not found at storagePath={}", storagePath);
            }
            if (content.isEmpty()) {
                log.warn("syncPageToIndex: content is empty for pageId={}, title={}", pageDO.getId(), pageDO.getTitle());
            }
            searchService.indexPage(
                scopeId, pageDO.getId(), pageDO.getTitle(), pageDO.getFilePath(),
                pageDO.getCategory(), pageDO.getSummary(), content,
                pageDO.getHealthStatus(), pageDO.getVisibility(),
                pageDO.getLifecycleStatus()
            );
        } catch (Exception e) {
            log.error("Failed to sync page {} to search index: {}", pageDO.getId(), e.getMessage(), e);
        }
    }

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    @Override
    public Map<String, Long> resolveWikiLinks(String content, Long scopeId) {
        if (content == null || content.isEmpty()) {
            return Map.of();
        }
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        Map<String, Long> resolution = new LinkedHashMap<>();
        List<String> linkTexts = new ArrayList<>();
        while (matcher.find()) {
            String linkText = matcher.group(1).trim();
            if (!linkText.isEmpty() && !resolution.containsKey(linkText)) {
                linkTexts.add(linkText);
            }
        }
        if (linkTexts.isEmpty()) {
            return Map.of();
        }
        List<WikiPageDO> matchedPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getTitle, linkTexts)
        );
        for (WikiPageDO page : matchedPages) {
            resolution.put(page.getTitle(), page.getId());
        }
        return resolution;
    }

    private String extractTitle(String content) {
        if (content == null) return "";
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "";
    }

    private WikiPageModel toModel(WikiPageDO pageDO) {
        WikiPageModel model = new WikiPageModel();
        model.setId(pageDO.getId());
        model.setTitle(pageDO.getTitle());
        model.setPath(pageDO.getFilePath());
        model.setCategory(pageDO.getCategory());
        model.setSummary(pageDO.getSummary());
        model.setScopeId(pageDO.getScopeId());
        model.setSourceCount(pageDO.getSourceCount());
        model.setHealthStatus(pageDO.getHealthStatus());
        model.setVisibility(pageDO.getVisibility());
        model.setPromotedFromScopeId(pageDO.getPromotedFromScopeId());
        model.setPromotedFromPageId(pageDO.getPromotedFromPageId());
        model.setPromotedFromUsername(pageDO.getPromotedFromUsername());
        model.setLastCheckedAt(pageDO.getLastCheckedAt());
        model.setContentUpdatedAt(pageDO.getContentUpdatedAt());
        model.setLastModified(pageDO.getUpdatedAt());
        model.setPageType(pageDO.getPageType());
        model.setLifecycleStatus(pageDO.getLifecycleStatus());
        model.setDeprecatedAt(pageDO.getDeprecatedAt());
        model.setDeprecatedReason(pageDO.getDeprecatedReason());
        model.setDeletedAt(pageDO.getDeletedAt());
        model.setMergedIntoPageId(pageDO.getMergedIntoPageId());
        model.setUserModified(pageDO.getUserModified());
        model.setPostSaveStatus(pageDO.getPostSaveStatus());
        model.setPostSaveError(pageDO.getPostSaveError());
        return model;
    }
}