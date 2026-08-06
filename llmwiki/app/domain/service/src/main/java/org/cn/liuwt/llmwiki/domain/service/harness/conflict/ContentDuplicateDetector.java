package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ContentDuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(ContentDuplicateDetector.class);

    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final int NGRAM_SIZE = 4;
    private static final int MIN_CONTENT_LENGTH = 200;
    private static final double MAX_LENGTH_RATIO = 3.0;
    private static final int MAX_EXISTING_CANDIDATES = 10;
    private static final int DETECT_ALL_MAX_PAGES = 300;
    private static final int LLM_BATCH_MAX_PAGES = 40;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private LlmConcurrencyBarrier concurrencyBarrier;

    private final ObjectMapper om = new ObjectMapper();

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private StorageProvider storageProvider;

    public record DuplicatePair(WikiPageDO pageA, WikiPageDO pageB, double similarity) {}

    public List<DuplicatePair> detect(Long scopeId, IngestContext context) {
        List<DuplicatePair> results = new ArrayList<>();

        Map<Long, WikiPageDO> newPages = collectNewEntityPages(context);
        if (newPages.isEmpty()) return results;

        Map<Long, String> newPageContents = readNewPageContents(scopeId, context, newPages);

        Set<Long> newPageIds = new HashSet<>(newPageKeys(newPages));
        Set<Long> alreadyHandled = collectAlreadyHandledPageIds(scopeId, newPageIds);

        results.addAll(detectNewVsExisting(scopeId, newPages, newPageContents, newPageIds, alreadyHandled));
        results.addAll(detectAmongNewPages(newPages, newPageContents));

        log.info("ContentDuplicateDetector: scopeId={}, newEntityPages={}, totalDuplicates={}",
            scopeId, newPages.size(), results.size());
        return results;
    }

    public List<DuplicatePair> detectAll(Long scopeId) {
        List<WikiPageDO> pages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .isNotNull(WikiPageDO::getFilePath)
                .notIn(WikiPageDO::getVisibility, List.of("private"))
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                    WikiPageDO::getCategory, WikiPageDO::getSummary,
                    WikiPageDO::getHealthStatus, WikiPageDO::getContentUpdatedAt,
                    WikiPageDO::getUpdatedAt)
        );
        pages = pages.stream()
            .filter(p -> !isArchivedOrMerged(p))
            .toList();
        if (pages.size() < 2) return Collections.emptyList();

        if (pages.size() > DETECT_ALL_MAX_PAGES) {
            log.warn("ContentDuplicateDetector.detectAll: scopeId={} has {} pages, exceeding limit {}, using priority sampling",
                scopeId, pages.size(), DETECT_ALL_MAX_PAGES);
            pages = prioritizeByRecency(pages, DETECT_ALL_MAX_PAGES);
        }

        Map<Long, WikiPageDO> pageById = new HashMap<>(pages.size());
        for (WikiPageDO p : pages) {
            pageById.put(p.getId(), p);
        }

        Set<String> handledPairs = collectHandledPairs(scopeId, new HashSet<>(pageById.keySet()));

        List<long[]> candidatePairs;
        if (chatClient != null && chatClient.isAvailable()) {
            candidatePairs = llmScreenDuplicates(pages, handledPairs);
        } else {
            candidatePairs = jaccardScreenByMetadata(pages, handledPairs);
        }

        if (candidatePairs.isEmpty()) {
            log.info("ContentDuplicateDetector.detectAll: scopeId={}, pages={}, no candidates", scopeId, pages.size());
            return Collections.emptyList();
        }

        return confirmWithJaccard(scopeId, candidatePairs, pageById, handledPairs);
    }

    public List<DuplicatePair> detectForPages(Long scopeId, Set<Long> focusPageIds) {
        if (focusPageIds == null || focusPageIds.isEmpty()) return Collections.emptyList();

        List<WikiPageDO> focusPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getId, focusPageIds)
                .isNotNull(WikiPageDO::getFilePath)
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                    WikiPageDO::getCategory, WikiPageDO::getSummary,
                    WikiPageDO::getHealthStatus, WikiPageDO::getContentUpdatedAt,
                    WikiPageDO::getUpdatedAt)
        );
        focusPages = focusPages.stream().filter(p -> !isArchivedOrMerged(p)).toList();
        if (focusPages.isEmpty()) return Collections.emptyList();

        List<WikiPageDO> otherPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .notIn(WikiPageDO::getId, focusPageIds)
                .isNotNull(WikiPageDO::getFilePath)
                .notIn(WikiPageDO::getVisibility, List.of("private"))
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                    WikiPageDO::getCategory, WikiPageDO::getSummary,
                    WikiPageDO::getHealthStatus, WikiPageDO::getContentUpdatedAt,
                    WikiPageDO::getUpdatedAt)
        );
        otherPages = otherPages.stream().filter(p -> !isArchivedOrMerged(p)).toList();

        Map<Long, WikiPageDO> pageById = new HashMap<>();
        for (WikiPageDO p : focusPages) pageById.put(p.getId(), p);
        for (WikiPageDO p : otherPages) pageById.put(p.getId(), p);

        Set<String> handledPairs = collectHandledPairs(scopeId, new HashSet<>(pageById.keySet()));

        List<long[]> candidatePairs = new ArrayList<>();
        for (int i = 0; i < focusPages.size(); i++) {
            WikiPageDO fp = focusPages.get(i);
            String normFp = normalizeTitle(fp.getTitle());
            for (WikiPageDO other : otherPages) {
                String pairKey = pairKey(fp.getId(), other.getId());
                if (handledPairs.contains(pairKey)) continue;
                double titleSim = computeJaccardSimilarity(normFp, normalizeTitle(other.getTitle()));
                if (titleSim >= 0.4) {
                    candidatePairs.add(new long[]{fp.getId(), other.getId()});
                    continue;
                }
                if (fp.getSummary() != null && other.getSummary() != null
                    && fp.getSummary().length() > 50 && other.getSummary().length() > 50) {
                    double summarySim = computeJaccardSimilarity(fp.getSummary(), other.getSummary());
                    if (summarySim >= 0.45) {
                        candidatePairs.add(new long[]{fp.getId(), other.getId()});
                    }
                }
            }
        }
        for (int i = 0; i < focusPages.size(); i++) {
            for (int j = i + 1; j < focusPages.size(); j++) {
                WikiPageDO a = focusPages.get(i);
                WikiPageDO b = focusPages.get(j);
                String pairKey = pairKey(a.getId(), b.getId());
                if (handledPairs.contains(pairKey)) continue;
                double titleSim = computeJaccardSimilarity(normalizeTitle(a.getTitle()), normalizeTitle(b.getTitle()));
                if (titleSim >= 0.4) {
                    candidatePairs.add(new long[]{a.getId(), b.getId()});
                }
            }
        }

        if (candidatePairs.isEmpty()) {
            log.info("ContentDuplicateDetector.detectForPages: scopeId={}, focusPages={}, no candidates", scopeId, focusPages.size());
            return Collections.emptyList();
        }

        List<DuplicatePair> results = confirmWithJaccard(scopeId, candidatePairs, pageById, handledPairs);
        log.info("ContentDuplicateDetector.detectForPages: scopeId={}, focusPages={}, duplicates={}", scopeId, focusPages.size(), results.size());
        return results;
    }

    private List<long[]> llmScreenDuplicates(List<WikiPageDO> pages, Set<String> handledPairs) {
        Map<String, List<WikiPageDO>> byCategory = new LinkedHashMap<>();
        for (WikiPageDO p : pages) {
            String cat = p.getCategory() != null ? p.getCategory() : "未分类";
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(p);
        }

        List<long[]> allCandidates = new ArrayList<>();
        for (Map.Entry<String, List<WikiPageDO>> entry : byCategory.entrySet()) {
            List<WikiPageDO> group = entry.getValue();
            if (group.size() < 2) continue;
            allCandidates.addAll(llmScreenGroup(group, handledPairs));
        }

        Map<String, List<WikiPageDO>> crossCategoryPairs = findCrossCategoryTitleMatches(pages, byCategory);
        for (Map.Entry<String, List<WikiPageDO>> entry : crossCategoryPairs.entrySet()) {
            List<WikiPageDO> pair = entry.getValue();
            allCandidates.add(new long[]{pair.get(0).getId(), pair.get(1).getId()});
        }

        log.info("LLM semantic screening: {} candidate pairs from {} pages", allCandidates.size(), pages.size());
        return allCandidates;
    }

    private List<long[]> llmScreenGroup(List<WikiPageDO> group, Set<String> handledPairs) {
        List<long[]> candidates = new ArrayList<>();
        List<WikiPageDO> batch = new ArrayList<>();

        for (int start = 0; start < group.size(); start += LLM_BATCH_MAX_PAGES) {
            batch.clear();
            int end = Math.min(start + LLM_BATCH_MAX_PAGES, group.size());
            batch.addAll(group.subList(start, end));

            List<long[]> batchCandidates = callLlmForGroup(batch, handledPairs);
            candidates.addAll(batchCandidates);
        }
        return candidates;
    }

    private List<long[]> callLlmForGroup(List<WikiPageDO> group, Set<String> handledPairs) {
        if (group.size() < 2) return Collections.emptyList();

        StringBuilder catalog = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            WikiPageDO p = group.get(i);
            catalog.append(i + 1).append(". ").append(p.getTitle());
            if (p.getSummary() != null && !p.getSummary().isBlank()) {
                catalog.append(" — ").append(truncate(p.getSummary(), 150));
            }
            catalog.append("\n");
        }

        String prompt = "以下是知识库中同一分类下的页面列表（编号. 标题 — 摘要）：\n\n"
            + catalog
            + "\n请识别其中**知识内容高度重复**的页面对。判断标准：\n"
            + "1. 两个页面讲述的是同一主题或同一实体的相同知识（不是仅仅属于同一分类）\n"
            + "2. 标题不同但实质覆盖同一概念（如「员工考勤管理」和「出勤管理制度」是重复的）\n"
            + "3. 如果一个页面是另一个的子集（如「安全规则」和「信息安全管理制度」），也算重复\n"
            + "4. 仅属于同一分类但讲不同内容的页面不算重复\n\n"
            + "请输出JSON数组，每对格式为{\"a\":编号,\"b\":编号}。如果没有重复对，输出空数组[]。\n"
            + "只输出JSON，不要其他内容。";

        boolean acquired = false;
        try {
            acquired = concurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 15_000);
            if (!acquired) {
                log.warn("LLM semantic screening skipped: concurrency barrier timeout");
                return Collections.emptyList();
            }
            String raw = chatClient.chat(prompt);
            return parseLlmPairs(raw, group, handledPairs);
        } catch (Exception e) {
            log.warn("LLM semantic screening failed for group of {}: {}", group.size(), e.getMessage());
            return Collections.emptyList();
        } finally {
            if (acquired) {
                concurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
            }
        }
    }

    private List<long[]> parseLlmPairs(String raw, List<WikiPageDO> group, Set<String> handledPairs) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) json = json.substring(start, end + 1);
            }
            List<Map<String, Object>> pairs = om.readValue(json, new TypeReference<>() {});
            List<long[]> results = new ArrayList<>();
            for (Map<String, Object> pair : pairs) {
                int a = toInt(pair.get("a")) - 1;
                int b = toInt(pair.get("b")) - 1;
                if (a < 0 || a >= group.size() || b < 0 || b >= group.size() || a == b) continue;
                Long idA = group.get(a).getId();
                Long idB = group.get(b).getId();
                String pairKey = pairKey(idA, idB);
                if (handledPairs.contains(pairKey)) continue;
                results.add(new long[]{idA, idB});
            }
            return results;
        } catch (Exception e) {
            log.warn("Failed to parse LLM duplicate screening result: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private int toInt(Object val) {
        if (val instanceof Number num) return num.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    private Map<String, List<WikiPageDO>> findCrossCategoryTitleMatches(
            List<WikiPageDO> pages, Map<String, List<WikiPageDO>> byCategory) {
        Map<String, List<WikiPageDO>> matches = new LinkedHashMap<>();
        List<WikiPageDO> allList = new ArrayList<>(pages);
        for (int i = 0; i < allList.size(); i++) {
            for (int j = i + 1; j < allList.size(); j++) {
                WikiPageDO a = allList.get(i);
                WikiPageDO b = allList.get(j);
                if (Objects.equals(a.getCategory(), b.getCategory())) continue;
                double titleSim = computeJaccardSimilarity(
                    normalizeTitle(a.getTitle()), normalizeTitle(b.getTitle()));
                if (titleSim >= 0.6) {
                    matches.put(a.getId() + "_" + b.getId(), List.of(a, b));
                }
            }
        }
        return matches;
    }

    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("[\\s\\p{Punct}]+", "").toLowerCase();
    }

    private List<long[]> jaccardScreenByMetadata(List<WikiPageDO> pages, Set<String> handledPairs) {
        List<long[]> candidates = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            WikiPageDO a = pages.get(i);
            String normA = normalizeTitle(a.getTitle());
            for (int j = i + 1; j < pages.size(); j++) {
                WikiPageDO b = pages.get(j);
                String pairKey = pairKey(a.getId(), b.getId());
                if (handledPairs.contains(pairKey)) continue;

                double titleSim = computeJaccardSimilarity(normA, normalizeTitle(b.getTitle()));
                if (titleSim >= 0.4) {
                    candidates.add(new long[]{a.getId(), b.getId()});
                    continue;
                }

                if (a.getSummary() != null && b.getSummary() != null
                    && a.getSummary().length() > 50 && b.getSummary().length() > 50) {
                    double summarySim = computeJaccardSimilarity(a.getSummary(), b.getSummary());
                    if (summarySim >= 0.45) {
                        candidates.add(new long[]{a.getId(), b.getId()});
                    }
                }
            }
        }
        log.info("Jaccard metadata fallback screening: {} candidate pairs from {} pages",
            candidates.size(), pages.size());
        return candidates;
    }

    private List<DuplicatePair> confirmWithJaccard(Long scopeId, List<long[]> candidatePairs,
                                                     Map<Long, WikiPageDO> pageById,
                                                     Set<String> handledPairs) {
        Map<Long, String> contentCache = new HashMap<>();
        Map<Long, String> strippedCache = new HashMap<>();
        List<DuplicatePair> results = new ArrayList<>();

        for (long[] pair : candidatePairs) {
            Long idA = pair[0], idB = pair[1];
            String pairKey = pairKey(idA, idB);
            if (handledPairs.contains(pairKey)) continue;

            WikiPageDO pageA = pageById.get(idA);
            WikiPageDO pageB = pageById.get(idB);
            if (pageA == null || pageB == null) continue;

            String contentA = getOrReadContent(scopeId, pageA, contentCache);
            String contentB = getOrReadContent(scopeId, pageB, contentCache);
            if (contentA == null || contentA.length() < MIN_CONTENT_LENGTH) continue;
            if (contentB == null || contentB.length() < MIN_CONTENT_LENGTH) continue;

            double lenRatio = Math.max(contentA.length(), contentB.length())
                / (double) Math.min(contentA.length(), contentB.length());
            if (lenRatio > MAX_LENGTH_RATIO) continue;

            String strippedA = strippedCache.computeIfAbsent(idA, k -> stripMarkdown(contentA));
            String strippedB = strippedCache.computeIfAbsent(idB, k -> stripMarkdown(contentB));

            double similarity = computeJaccardSimilarity(strippedA, strippedB);
            if (similarity >= SIMILARITY_THRESHOLD) {
                results.add(new DuplicatePair(pageA, pageB, similarity));
                log.info("Content duplicate confirmed: '{}' ↔ '{}' (similarity={}%)",
                    pageA.getTitle(), pageB.getTitle(), String.format("%.0f", similarity * 100));
            } else {
                log.debug("LLM candidate rejected by Jaccard: '{}' ↔ '{}' (similarity={}%, below threshold)",
                    pageA.getTitle(), pageB.getTitle(), String.format("%.0f", similarity * 100));
            }
        }
        return results;
    }

    private String getOrReadContent(Long scopeId, WikiPageDO page, Map<Long, String> cache) {
        return cache.computeIfAbsent(page.getId(), id -> {
            try {
                byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + page.getFilePath());
                return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
            } catch (Exception e) {
                return "";
            }
        });
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private List<WikiPageDO> prioritizeByRecency(List<WikiPageDO> pages, int max) {
        List<WikiPageDO> sorted = new ArrayList<>(pages);
        sorted.sort((a, b) -> {
            java.time.LocalDateTime ta = a.getContentUpdatedAt() != null ? a.getContentUpdatedAt() : a.getUpdatedAt();
            java.time.LocalDateTime tb = b.getContentUpdatedAt() != null ? b.getContentUpdatedAt() : b.getUpdatedAt();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        return sorted.subList(0, max);
    }

    private boolean isArchivedOrMerged(WikiPageDO page) {
        String status = page.getHealthStatus();
        if (status == null) return false;
        return "archived".equals(status) || status.startsWith("merged-into");
    }

    private Map<Long, WikiPageDO> collectNewEntityPages(IngestContext context) {
        Map<Long, WikiPageDO> pages = new LinkedHashMap<>();
        for (WikiPageDO page : context.getEntityPages().values()) {
            if (page != null && page.getId() != null) {
                pages.put(page.getId(), page);
            }
        }
        WikiPageDO summary = context.getSummaryPage();
        if (summary != null && summary.getId() != null) {
            pages.put(summary.getId(), summary);
        }
        return pages;
    }

    private Set<Long> newPageKeys(Map<Long, WikiPageDO> newPages) {
        return new HashSet<>(newPages.keySet());
    }

    private Map<Long, String> readNewPageContents(Long scopeId, IngestContext context,
                                                   Map<Long, WikiPageDO> newPages) {
        Map<Long, String> contents = new HashMap<>();
        Map<String, String> pageContents = context.getPageContents();
        for (WikiPageDO page : newPages.values()) {
            String content = null;
            if (page.getFilePath() != null && pageContents.containsKey(page.getFilePath())) {
                content = pageContents.get(page.getFilePath());
            }
            if (content == null || content.isBlank()) {
                content = readPageContent(scopeId, page.getFilePath());
            }
            contents.put(page.getId(), content);
        }
        return contents;
    }

    private Set<Long> collectAlreadyHandledPageIds(Long scopeId, Set<Long> newPageIds) {
        Set<Long> handled = new HashSet<>();
        for (Long newPageId : newPageIds) {
            List<WikiPageLinkDO> links = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .eq(WikiPageLinkDO::getFromPageId, newPageId)
                    .eq(WikiPageLinkDO::getLinkType, "contradiction")
            );
            for (WikiPageLinkDO link : links) {
                handled.add(link.getToPageId());
            }
            List<WikiPageLinkDO> reverseLinks = wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeId)
                    .eq(WikiPageLinkDO::getToPageId, newPageId)
                    .eq(WikiPageLinkDO::getLinkType, "contradiction")
            );
            for (WikiPageLinkDO link : reverseLinks) {
                handled.add(link.getFromPageId());
            }
        }
        return handled;
    }

    private Set<String> collectHandledPairs(Long scopeId, Set<Long> pageIds) {
        Set<String> pairs = new HashSet<>();
        List<WikiPageLinkDO> links = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getLinkType, "contradiction")
                .in(WikiPageLinkDO::getFromPageId, pageIds)
        );
        for (WikiPageLinkDO link : links) {
            if (pageIds.contains(link.getToPageId())) {
                pairs.add(pairKey(link.getFromPageId(), link.getToPageId()));
            }
        }
        return pairs;
    }

    private static String pairKey(Long idA, Long idB) {
        return idA < idB ? idA + ":" + idB : idB + ":" + idA;
    }

    private List<DuplicatePair> detectNewVsExisting(Long scopeId, Map<Long, WikiPageDO> newPages,
                                                     Map<Long, String> newPageContents,
                                                     Set<Long> newPageIds, Set<Long> alreadyHandled) {
        List<DuplicatePair> results = new ArrayList<>();

        List<WikiPageDO> existingPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .notIn(WikiPageDO::getId, newPageIds)
                .isNotNull(WikiPageDO::getFilePath)
        );
        if (existingPages.isEmpty()) return results;

        Map<Long, String> existingContentCache = new HashMap<>();
        for (WikiPageDO page : existingPages) {
            String content = readPageContent(scopeId, page.getFilePath());
            if (content != null && content.length() >= MIN_CONTENT_LENGTH) {
                existingContentCache.put(page.getId(), content);
            }
        }
        if (existingContentCache.isEmpty()) return results;

        for (Map.Entry<Long, WikiPageDO> entry : newPages.entrySet()) {
            WikiPageDO newPage = entry.getValue();
            String newContent = newPageContents.get(entry.getKey());
            if (newContent == null || newContent.length() < MIN_CONTENT_LENGTH) continue;
            String strippedNew = stripMarkdown(newContent);
            if (strippedNew.length() < MIN_CONTENT_LENGTH / 2) continue;

            List<Map.Entry<Long, String>> candidates = filterCandidatesFromCache(
                existingPages, existingContentCache, newContent, alreadyHandled);

            for (Map.Entry<Long, String> candidateEntry : candidates) {
                String existingContent = candidateEntry.getValue();
                double similarity = computeJaccardSimilarity(stripMarkdown(newContent), stripMarkdown(existingContent));
                if (similarity >= SIMILARITY_THRESHOLD) {
                    WikiPageDO candidatePage = existingPages.stream()
                        .filter(p -> p.getId().equals(candidateEntry.getKey())).findFirst().orElse(null);
                    if (candidatePage != null) {
                        results.add(new DuplicatePair(newPage, candidatePage, similarity));
                        log.info("Content duplicate detected: '{}' ↔ '{}' (similarity={}%)",
                            newPage.getTitle(), candidatePage.getTitle(), String.format("%.0f", similarity * 100));
                    }
                }
            }
        }
        return results;
    }

    private List<Map.Entry<Long, String>> filterCandidatesFromCache(
            List<WikiPageDO> existingPages, Map<Long, String> contentCache,
            String newContent, Set<Long> alreadyHandled) {
        int newLen = newContent.length();
        Map<Long, String> filtered = new HashMap<>();
        for (WikiPageDO page : existingPages) {
            if (alreadyHandled.contains(page.getId())) continue;
            String content = contentCache.get(page.getId());
            if (content == null) continue;
            double lenRatio = Math.max(newLen, content.length()) / (double) Math.min(newLen, content.length());
            if (lenRatio > MAX_LENGTH_RATIO) continue;
            filtered.put(page.getId(), content);
        }
        List<Map.Entry<Long, String>> sorted = new ArrayList<>(filtered.entrySet());
        sorted.sort((a, b) -> Integer.compare(
            Math.abs(newLen - b.getValue().length()),
            Math.abs(newLen - a.getValue().length())
        ));
        if (sorted.size() > MAX_EXISTING_CANDIDATES) {
            sorted = sorted.subList(0, MAX_EXISTING_CANDIDATES);
        }
        return sorted;
    }

    private List<DuplicatePair> detectAmongNewPages(Map<Long, WikiPageDO> newPages,
                                                     Map<Long, String> newPageContents) {
        List<DuplicatePair> results = new ArrayList<>();
        List<Long> pageIds = new ArrayList<>(newPages.keySet());

        for (int i = 0; i < pageIds.size(); i++) {
            for (int j = i + 1; j < pageIds.size(); j++) {
                Long idA = pageIds.get(i);
                Long idB = pageIds.get(j);
                String contentA = newPageContents.get(idA);
                String contentB = newPageContents.get(idB);
                if (contentA == null || contentA.length() < MIN_CONTENT_LENGTH) continue;
                if (contentB == null || contentB.length() < MIN_CONTENT_LENGTH) continue;

                double lenRatio = Math.max(contentA.length(), contentB.length())
                    / (double) Math.min(contentA.length(), contentB.length());
                if (lenRatio > MAX_LENGTH_RATIO) continue;

                double similarity = computeJaccardSimilarity(stripMarkdown(contentA), stripMarkdown(contentB));
                if (similarity >= SIMILARITY_THRESHOLD) {
                    results.add(new DuplicatePair(newPages.get(idA), newPages.get(idB), similarity));
                    log.info("Content duplicate detected (among new): '{}' ↔ '{}' (similarity={}%)",
                        newPages.get(idA).getTitle(), newPages.get(idB).getTitle(), String.format("%.0f", similarity * 100));
                }
            }
        }
        return results;
    }

    private double computeJaccardSimilarity(String textA, String textB) {
        if (textA == null || textB == null || textA.length() < NGRAM_SIZE || textB.length() < NGRAM_SIZE) {
            return 0.0;
        }
        Set<String> ngramsA = extractCharNgrams(textA);
        Set<String> ngramsB = extractCharNgrams(textB);
        if (ngramsA.isEmpty() || ngramsB.isEmpty()) return 0.0;

        int intersectionSize = 0;
        Set<String> smaller = ngramsA.size() <= ngramsB.size() ? ngramsA : ngramsB;
        Set<String> larger = ngramsA.size() <= ngramsB.size() ? ngramsB : ngramsA;
        for (String ngram : smaller) {
            if (larger.contains(ngram)) intersectionSize++;
        }
        int unionSize = ngramsA.size() + ngramsB.size() - intersectionSize;
        return unionSize > 0 ? (double) intersectionSize / unionSize : 0.0;
    }

    private Set<String> extractCharNgrams(String text) {
        Set<String> ngrams = new HashSet<>();
        int len = text.length();
        for (int i = 0; i <= len - NGRAM_SIZE; i++) {
            ngrams.add(text.substring(i, i + NGRAM_SIZE));
        }
        return ngrams;
    }

    private String stripMarkdown(String content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder(content.length());
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) continue;
            if (trimmed.startsWith("---")) continue;
            if (trimmed.startsWith("|")) continue;
            if (trimmed.startsWith("> ")) trimmed = trimmed.substring(2);
            if (trimmed.startsWith("#")) {
                int idx = 0;
                while (idx < trimmed.length() && trimmed.charAt(idx) == '#') idx++;
                trimmed = trimmed.substring(idx).trim();
            }
            trimmed = trimmed.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            trimmed = trimmed.replaceAll("\\*(.+?)\\*", "$1");
            trimmed = trimmed.replaceAll("__(.+?)__", "$1");
            trimmed = trimmed.replaceAll("_(.+?)_", "$1");
            trimmed = trimmed.replaceAll("~~(.+?)~~", "$1");
            trimmed = trimmed.replaceAll("\\[(.+?)\\]\\(.+?\\)", "$1");
            trimmed = trimmed.replaceAll("!\\[.*?\\]\\(.+?\\)", "");
            trimmed = trimmed.replaceAll("`(.+?)`", "$1");
            if (!trimmed.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(trimmed);
            }
        }
        return sb.toString();
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
}
