package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final int MAX_PARSED_SOURCES = 8;
    private static final int MAX_SECTION_CHARS = 3000;
    private static final int MAX_TOTAL_PARSED_CHARS = 25000;
    private static final int TOP_PAGES_FOR_SOURCE_LOOKUP = 6;

    private static final int GRAPH_ANCHOR_LIMIT = 5;
    private static final int MAX_GRAPH_NEIGHBORS = 10;
    private static final int MAX_NEIGHBOR_SUMMARY_CHARS = 200;

    private static final int SUPPLEMENTARY_SEARCH_THRESHOLD = 5;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "是", "在", "和", "与", "对", "为", "中", "被", "把", "将", "从",
        "到", "上", "下", "个", "有", "不", "这", "那", "么", "什么", "怎么", "如何",
        "哪些", "可以", "能", "吗", "呢", "吧", "啊", "哪", "又", "也", "都", "就",
        "而", "但", "及", "或", "等", "之", "其", "所", "以", "这个", "那个", "一个",
        "还", "更", "最", "很", "比较", "一下", "一些", "种", "类", "方面", "相关",
        "the", "a", "an", "is", "are", "was", "were", "in", "on", "at", "to", "for",
        "of", "and", "or", "not", "it", "this", "that", "what", "how", "which"
    );

    @Autowired
    private org.cn.liuwt.llmwiki.integration.storage.StorageProvider storageProvider;

    @Autowired
    private SearchService searchService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    public RetrievalContext preRetrieveLight(Long scopeId, String question) {
        long startTime = System.currentTimeMillis();

        CompletableFuture<GlobalSummaryService.GlobalSummary> summaryFuture =
            CompletableFuture.supplyAsync(() -> globalSummaryService.build(scopeId));
        CompletableFuture<List<SearchResultInfo>> searchFuture =
            CompletableFuture.supplyAsync(() -> searchWiki(scopeId, question));

        GlobalSummaryService.GlobalSummary summary = summaryFuture.join();
        List<SearchResultInfo> searchResults = searchFuture.join();

        boolean needSupplementaryContext = searchResults.size() < SUPPLEMENTARY_SEARCH_THRESHOLD;

        List<RetrievalContext.ParsedSection> parsedSections;
        List<RetrievalContext.GraphNeighbor> graphNeighbors;

        if (needSupplementaryContext) {
            CompletableFuture<List<RetrievalContext.ParsedSection>> parsedFuture =
                CompletableFuture.supplyAsync(() -> preloadParsedSections(scopeId, searchResults, question));
            CompletableFuture<List<RetrievalContext.GraphNeighbor>> graphFuture =
                CompletableFuture.supplyAsync(() -> expandGraphNeighbors(scopeId, searchResults));
            parsedSections = parsedFuture.join();
            graphNeighbors = graphFuture.join();
        } else {
            parsedSections = List.of();
            graphNeighbors = List.of();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Light pre-retrieval completed: scopeId={} results={} supplementary={} parsedSections={} graphNeighbors={} elapsed={}ms",
            scopeId, searchResults.size(), needSupplementaryContext,
            parsedSections.size(), graphNeighbors.size(), elapsed);

        return RetrievalContext.builder()
            .globalSummary(summary)
            .pageCount(summary.totalPages())
            .searchResults(searchResults)
            .parsedSourceSections(parsedSections)
            .graphNeighbors(graphNeighbors)
            .build();
    }

    public RetrievalContext preRetrieveMultiScope(List<Long> scopeIds, String question) {
        if (scopeIds.size() == 1) {
            return preRetrieveLight(scopeIds.get(0), question);
        }
        long startTime = System.currentTimeMillis();
        List<SearchResultInfo> searchResults;
        try {
            searchResults = searchService.searchMultiScope(scopeIds, question, null);
        } catch (Exception e) {
            log.warn("Multi-scope ES search failed: {}", e.getMessage());
            searchResults = List.of();
        }
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Multi-scope pre-retrieval completed: scopeIds={} results={} elapsed={}ms",
            scopeIds, searchResults.size(), elapsed);
        return RetrievalContext.builder()
            .globalSummary(null)
            .pageCount(0)
            .searchResults(searchResults)
            .parsedSourceSections(List.of())
            .graphNeighbors(List.of())
            .build();
    }

    private List<SearchResultInfo> searchWiki(Long scopeId, String question) {
        try {
            return searchService.search(scopeId, question, null);
        } catch (Exception e) {
            log.warn("ES search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<RetrievalContext.ParsedSection> preloadParsedSections(
            Long scopeId, List<SearchResultInfo> searchResults, String question) {
        if (searchResults.isEmpty()) {
            return List.of();
        }

        List<Long> pageIds = new ArrayList<>();
        int limit = Math.min(TOP_PAGES_FOR_SOURCE_LOOKUP, searchResults.size());
        for (int i = 0; i < limit; i++) {
            SearchResultInfo r = searchResults.get(i);
            if ("DEPRECATED".equals(r.getLifecycleStatus())) continue;
            Long id = r.getId();
            if (id != null) pageIds.add(id);
        }
        if (pageIds.isEmpty()) {
            return List.of();
        }

        List<WikiPageSourceDO> relations;
        try {
            relations = wikiPageSourceMapper.selectList(
                new LambdaQueryWrapper<WikiPageSourceDO>()
                    .eq(WikiPageSourceDO::getScopeId, scopeId)
                    .in(WikiPageSourceDO::getPageId, pageIds)
            );
        } catch (Exception e) {
            log.warn("Failed to query wiki_page_source: {}", e.getMessage());
            return List.of();
        }

        Set<Long> seenSourceIds = new LinkedHashSet<>();
        for (WikiPageSourceDO rel : relations) {
            if (seenSourceIds.size() >= MAX_PARSED_SOURCES) break;
            seenSourceIds.add(rel.getSourceId());
        }
        if (seenSourceIds.isEmpty()) {
            return List.of();
        }

        List<SourceDO> sources = sourceMapper.selectBatchIds(new ArrayList<>(seenSourceIds));
        Map<Long, String> sourceNameMap = new HashMap<>();
        for (SourceDO s : sources) {
            sourceNameMap.put(s.getId(), s.getName());
        }

        List<String> keywords = extractKeywords(question);
        String scopeIdStr = String.valueOf(scopeId);

        List<CompletableFuture<RetrievalContext.ParsedSection>> futures = new ArrayList<>();
        for (Long sourceId : seenSourceIds) {
            String sourceName = sourceNameMap.getOrDefault(sourceId, "未知来源");
            futures.add(CompletableFuture.supplyAsync(() -> {
                String content = readParsedFile(scopeIdStr, sourceId);
                if (content == null) return null;
                String relevant = extractRelevantSections(content, keywords);
                if (relevant == null || relevant.isBlank()) return null;
                return new RetrievalContext.ParsedSection(sourceId, sourceName, relevant);
            }));
        }

        List<RetrievalContext.ParsedSection> sections = new ArrayList<>();
        int totalChars = 0;
        for (CompletableFuture<RetrievalContext.ParsedSection> f : futures) {
            try {
                RetrievalContext.ParsedSection ps = f.get(5000, TimeUnit.MILLISECONDS);
                if (ps != null) {
                    if (totalChars + ps.sectionContent().length() > MAX_TOTAL_PARSED_CHARS) break;
                    sections.add(ps);
                    totalChars += ps.sectionContent().length();
                }
            } catch (Exception e) {
                log.debug("Parsed section loading failed: {}", e.getMessage());
            }
        }

        log.info("Parsed sections preloaded: scopeId={} sources={} matched={}", scopeId, seenSourceIds.size(), sections.size());
        return sections;
    }

    List<RetrievalContext.GraphNeighbor> expandGraphNeighbors(Long scopeId, List<SearchResultInfo> searchResults) {
        if (searchResults.isEmpty()) return List.of();

        List<Long> anchorIds = new ArrayList<>();
        Map<Long, String> anchorTitleMap = new LinkedHashMap<>();
        Set<Long> anchorIdSet = new HashSet<>();
        int limit = Math.min(GRAPH_ANCHOR_LIMIT, searchResults.size());
        for (int i = 0; i < limit; i++) {
            SearchResultInfo r = searchResults.get(i);
            if ("DEPRECATED".equals(r.getLifecycleStatus())
                || "MERGED".equals(r.getLifecycleStatus())
                || "DELETED".equals(r.getLifecycleStatus())) {
                continue;
            }
            Long id = r.getId();
            if (id != null) {
                anchorIds.add(id);
                anchorIdSet.add(id);
                anchorTitleMap.put(id, r.getTitle());
            }
        }
        if (anchorIds.isEmpty()) return List.of();

        List<WikiPageLinkDO> outgoingLinks;
        List<WikiPageLinkDO> incomingLinks;
        try {
            outgoingLinks = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .in(WikiPageLinkDO::getFromPageId, anchorIds)
            );
            incomingLinks = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .in(WikiPageLinkDO::getToPageId, anchorIds)
            );
        } catch (Exception e) {
            log.warn("Graph neighbor link query failed: scopeId={}, error={}", scopeId, e.getMessage());
            return List.of();
        }

        Set<Long> neighborIds = new LinkedHashSet<>();
        Map<Long, List<LinkInfo>> neighborLinkInfo = new LinkedHashMap<>();

        for (WikiPageLinkDO link : outgoingLinks) {
            Long targetId = link.getToPageId();
            if (!anchorIdSet.contains(targetId)) {
                neighborIds.add(targetId);
                neighborLinkInfo.computeIfAbsent(targetId, k -> new ArrayList<>())
                    .add(new LinkInfo(link.getFromPageId(), link.getLinkType(), link.getLinkContext(), "outgoing"));
            }
        }
        for (WikiPageLinkDO link : incomingLinks) {
            Long sourceId = link.getFromPageId();
            if (!anchorIdSet.contains(sourceId)) {
                neighborIds.add(sourceId);
                neighborLinkInfo.computeIfAbsent(sourceId, k -> new ArrayList<>())
                    .add(new LinkInfo(link.getToPageId(), link.getLinkType(), link.getLinkContext(), "incoming"));
            }
        }

        if (neighborIds.isEmpty()) return List.of();

        List<WikiPageDO> neighborPages;
        try {
            neighborPages = wikiPageMapper.selectBatchIds(new ArrayList<>(neighborIds));
        } catch (Exception e) {
            log.warn("Graph neighbor page query failed: {}", e.getMessage());
            return List.of();
        }

        Map<Long, WikiPageDO> pageMap = new HashMap<>();
        for (WikiPageDO p : neighborPages) {
            String status = p.getLifecycleStatus();
            if (!"DEPRECATED".equals(status) && !"MERGED".equals(status) && !"DELETED".equals(status)) {
                pageMap.put(p.getId(), p);
            }
        }

        List<ScoredNeighbor> scored = new ArrayList<>();
        for (Map.Entry<Long, List<LinkInfo>> entry : neighborLinkInfo.entrySet()) {
            Long nid = entry.getKey();
            WikiPageDO page = pageMap.get(nid);
            if (page == null) continue;

            List<LinkInfo> links = entry.getValue();
            int score = 0;
            boolean hasContradiction = false;
            for (LinkInfo li : links) {
                if ("contradiction".equals(li.linkType)) {
                    score += 100;
                    hasContradiction = true;
                } else {
                    score += 10;
                }
            }
            if ("reference".equals(page.getPageType())) score += 20;
            if ("entity".equals(page.getPageType())) score += 10;
            scored.add(new ScoredNeighbor(nid, score, hasContradiction, links, page));
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        List<RetrievalContext.GraphNeighbor> result = new ArrayList<>();
        for (ScoredNeighbor sn : scored) {
            if (result.size() >= MAX_GRAPH_NEIGHBORS) break;
            WikiPageDO page = sn.page;
            for (LinkInfo li : sn.links) {
                if (result.size() >= MAX_GRAPH_NEIGHBORS) break;
                String anchorTitle = anchorTitleMap.getOrDefault(li.anchorId, "未知");
                String summary = page.getSummary();
                if (summary != null && summary.length() > MAX_NEIGHBOR_SUMMARY_CHARS) {
                    summary = summary.substring(0, MAX_NEIGHBOR_SUMMARY_CHARS) + "...";
                }
                result.add(new RetrievalContext.GraphNeighbor(
                    page.getId(), page.getTitle(), page.getFilePath(),
                    summary, page.getPageType(),
                    li.linkType, li.linkContext, li.direction,
                    page.getHealthStatus(), page.getLifecycleStatus(), anchorTitle
                ));
            }
        }

        log.info("Graph neighbors expanded: scopeId={} anchors={} neighbors={}", scopeId, anchorIds.size(), result.size());
        return result;
    }

    private record LinkInfo(Long anchorId, String linkType, String linkContext, String direction) {}

    private record ScoredNeighbor(Long pageId, int score, boolean hasContradiction, List<LinkInfo> links, WikiPageDO page) {}

    private String readParsedFile(String scopeIdStr, Long sourceId) {
        String parsedPath = "parsed/" + sourceId + ".parsed.md";
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, parsedPath);
            if (bytes != null && bytes.length > 0) {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Failed to read parsed file for sourceId={}: {}", sourceId, e.getMessage());
        }
        return null;
    }

    private List<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) return List.of();
        String[] tokens = question.toLowerCase().split("[\\s,，。？！?！、：:；;（）()\"“”'\'\\[\\]【】《》]+");
        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            String t = token.strip();
            if (t.length() >= 2 && !STOP_WORDS.contains(t)) {
                keywords.add(t);
            }
        }
        return keywords;
    }

    String extractRelevantSections(String content, List<String> keywords) {
        if (content == null || content.isBlank() || keywords.isEmpty()) return null;

        List<int[]> sectionBounds = new ArrayList<>();
        List<String> sectionTexts = new ArrayList<>();

        Matcher matcher = HEADING_PATTERN.matcher(content);
        List<Integer> headingPositions = new ArrayList<>();
        while (matcher.find()) {
            headingPositions.add(matcher.start());
        }

        if (headingPositions.isEmpty()) {
            if (content.length() > MAX_SECTION_CHARS) {
                return content.substring(0, MAX_SECTION_CHARS) + "\n...(截断)";
            }
            return content;
        }

        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i);
            int end = (i + 1 < headingPositions.size()) ? headingPositions.get(i + 1) : content.length();
            sectionBounds.add(new int[]{start, end});
            sectionTexts.add(content.substring(start, end));
        }

        if (headingPositions.get(0) > 200) {
            String preamble = content.substring(0, headingPositions.get(0));
            sectionBounds.add(0, new int[]{0, headingPositions.get(0)});
            sectionTexts.add(0, preamble);
        }

        int[] scores = new int[sectionTexts.size()];
        for (int i = 0; i < sectionTexts.size(); i++) {
            String lower = sectionTexts.get(i).toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                int idx = 0;
                while ((idx = lower.indexOf(kw, idx)) >= 0) {
                    score++;
                    idx += kw.length();
                }
            }
            scores[i] = score;
        }

        List<Integer> rankedIndices = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) rankedIndices.add(i);
        rankedIndices.sort((a, b) -> Integer.compare(scores[b], scores[a]));

        List<Integer> selected = new ArrayList<>();
        int charsUsed = 0;
        for (int idx : rankedIndices) {
            if (scores[idx] == 0) break;
            int len = sectionTexts.get(idx).length();
            if (charsUsed + len > MAX_SECTION_CHARS && !selected.isEmpty()) break;
            selected.add(idx);
            charsUsed += len;
            if (selected.size() >= 5) break;
        }

        if (selected.isEmpty()) return null;

        Collections.sort(selected);

        StringBuilder sb = new StringBuilder();
        for (int idx : selected) {
            String section = sectionTexts.get(idx).strip();
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(section);
        }

        String result = sb.toString();
        if (result.length() > MAX_SECTION_CHARS) {
            result = result.substring(0, MAX_SECTION_CHARS) + "\n...(截断)";
        }
        return result;
    }
}
