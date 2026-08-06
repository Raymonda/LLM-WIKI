package org.cn.liuwt.llmwiki.domain.service.harness.crossref;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
public class CrossRefDomainService {

    private static final Logger log = LoggerFactory.getLogger(CrossRefDomainService.class);
    private static final int CONTENT_PREVIEW_MAX_CHARS = 3000;
    private static final int INVENTORY_MAX_CHARS = 40000;
    private static final int INVENTORY_ENTRY_MAX_CHARS = 120;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private LinkWritingService linkWritingService;

    @Autowired
    private LintFindingService lintFindingService;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmConcurrencyBarrier;

    @Autowired
    private SearchService searchService;

    @Autowired
    private GlobalSummaryService globalSummaryService;

    @Value("${llmwiki.crossref.max-candidates-per-page:10}")
    private int maxCandidatesPerPage;

    @Value("${llmwiki.crossref.confidence-threshold:0.5}")
    private double confidenceThreshold;

    @Value("${llmwiki.crossref.deterministic-keyword-multiplier:2.0}")
    private double deterministicKeywordMultiplier;

    @Value("${llmwiki.crossref.min-shared-keywords-default:3}")
    private int minSharedKeywordsDefault;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService crossRefExecutor = Executors.newFixedThreadPool(3,
        r -> { Thread t = new Thread(r, "crossref-eval"); t.setDaemon(true); return t; });

    public record CrossRefOutcome(int fixed, int failed, int skipped, int rejected, String summary) {}
    private record EvalTask(WikiPageDO candidate, CompletableFuture<LinkDecision> future) {}
    public record LinkGenerationResult(int evaluated, int linked, int rejected, int failed, String summary) {}
    public record LinkDecision(boolean shouldLink, LinkType linkType, String linkContext,
                               double confidence, String reason) {}

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        crossRefExecutor.shutdown();
    }

    // ==================== Lint 交叉引用修复（保留原逻辑，使用 LINT bucket）====================

    public CrossRefOutcome resolveFindings(Long scopeId, List<LintFindingDO> crossrefFindings,
                                           List<LintFindingDO> conflictFindings,
                                           Long executionId, LintRulesConfig rulesConfig) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new CrossRefOutcome(0, 0, 0, 0, "AI 未配置，跳过缺失交叉引用自动修复");
        }
        int autoFixLimit = rulesConfig.getDiagnosticStandard().getAutoFixLimit();
        int fixed = 0, failed = 0, skipped = 0, rejected = 0;
        StringBuilder fixReport = new StringBuilder();
        Set<Long> processedPairs = new HashSet<>();

        for (LintFindingDO f : crossrefFindings) {
            if (!"auto_repair".equals(f.getHandlingMethod())) continue;
            if (processedPairs.contains(f.getId())) continue;
            if (fixed + failed + rejected >= autoFixLimit) {
                skipped++;
                continue;
            }
            WikiPageDO[] pages = resolvePagePair(scopeId, f);
            WikiPageDO pageA = pages[0];
            WikiPageDO pageB = pages[1];
            if (pageA == null || pageB == null) continue;

            String pageAContent = readPageContent(scopeId, pageA.getFilePath());
            String pageBContent = readPageContent(scopeId, pageB.getFilePath());

            String crossrefPrompt = schemaInjector.prependForLint(scopeId,
                PromptRegistry.forLint().generateCrossrefLink(
                    pageA.getTitle(), pageAContent,
                    pageB.getTitle(), pageBContent
                ));

            boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 30_000);
            if (!acquired) {
                log.warn("LlmConcurrencyBarrier LINT bucket timeout, skipping crossref findingId={}", f.getId());
                failed++;
                continue;
            }
            try {
                String linkSuggestions = chatClient.chat(crossrefPrompt);
                int applied = linkWritingService.applyLinkSuggestionsWithExecution(
                    linkSuggestions, scopeId, executionId);

                if (applied > 0) {
                    lintFindingService.autoResolve(f.getId(), "auto_repair");
                    processedPairs.add(f.getId());
                    fixed++;
                    fixReport.append("- 已创建交叉引用：").append(pageA.getTitle())
                        .append(" ↔ ").append(pageB.getTitle()).append("\n");
                } else {
                    rejected++;
                    lintFindingService.dismissFinding(f.getId());
                    processedPairs.add(f.getId());
                    fixReport.append("- AI 拒绝链接：").append(pageA.getTitle())
                        .append(" ↔ ").append(pageB.getTitle())
                        .append("（语义不相关）\n");
                }
            } catch (Exception e) {
                log.warn("CrossRef resolve failed for findingId={}: {}", f.getId(), e.getMessage());
                lintFindingService.markRepairFailed(f.getId());
                failed++;
            } finally {
                llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
            }
        }

        for (LintFindingDO f : conflictFindings) {
            if (!"auto_repair".equals(f.getHandlingMethod())) continue;
            if (processedPairs.contains(f.getId())) continue;
            if (fixed + failed >= autoFixLimit) {
                skipped++;
                continue;
            }
            WikiPageDO[] pages = resolvePagePair(scopeId, f);
            WikiPageDO pageA = pages[0];
            WikiPageDO pageB = pages[1];
            if (pageA == null || pageB == null) continue;

            String conflictType = extractExtraField(f.getExtra(), "conflictType");
            boolean created = createContradictionLink(scopeId, pageA, pageB, conflictType, executionId);
            if (created) {
                lintFindingService.autoResolve(f.getId(), "auto_repair");
                processedPairs.add(f.getId());
                fixed++;
                fixReport.append("- 已创建矛盾链接：").append(pageA.getTitle())
                    .append(" ⚠ ").append(pageB.getTitle())
                    .append(" (").append(conflictType).append(")\n");
            } else {
                lintFindingService.autoResolve(f.getId(), "auto_repair");
                processedPairs.add(f.getId());
            }
        }

        String summary = String.format("缺失交叉引用自动修复：成功 %d 对，失败 %d，拒绝 %d，超限跳过 %d\n%s",
            fixed, failed, rejected, skipped, fixReport);
        return new CrossRefOutcome(fixed, failed, skipped, rejected, summary);
    }

    // ==================== 公共方法：矛盾链接创建 ====================

    public boolean createContradictionLink(Long scopeId, WikiPageDO pageA, WikiPageDO pageB,
                                           String conflictType, Long executionId) {
        WikiPageLinkDO existingLink = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageA.getId())
                .eq(WikiPageLinkDO::getToPageId, pageB.getId())
                .eq(WikiPageLinkDO::getLinkType, LinkType.CONTRADICTION.getValue())
        );
        if (existingLink != null) {
            return false;
        }
        WikiPageLinkDO linkDO = new WikiPageLinkDO();
        linkDO.setScopeId(scopeId);
        linkDO.setFromPageId(pageA.getId());
        linkDO.setToPageId(pageB.getId());
        linkDO.setLinkType(LinkType.CONTRADICTION.getValue());
        linkDO.setCreatedBy("lint_ai");
        linkDO.setExecutionId(executionId);
        if (conflictType != null && !conflictType.isBlank()) {
            linkDO.setLinkContext("矛盾类型：" + conflictType);
        }
        wikiPageLinkMapper.insert(linkDO);
        return true;
    }

    public boolean createLink(Long scopeId, Long fromPageId, Long toPageId, LinkType linkType,
                              String linkContext, Double confidence, String createdBy, Long executionId) {
        WikiPageLinkDO existing = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, fromPageId)
                .eq(WikiPageLinkDO::getToPageId, toPageId)
                .eq(WikiPageLinkDO::getLinkType, linkType.getValue())
        );
        if (existing != null) {
            boolean needUpdate = false;
            if (linkContext != null && !linkContext.isBlank()) {
                existing.setLinkContext(linkContext);
                needUpdate = true;
            }
            if (confidence != null) {
                existing.setConfidence(confidence);
                needUpdate = true;
            }
            if (needUpdate) {
                wikiPageLinkMapper.updateById(existing);
            }
            return false;
        }
        WikiPageLinkDO linkDO = new WikiPageLinkDO();
        linkDO.setScopeId(scopeId);
        linkDO.setFromPageId(fromPageId);
        linkDO.setToPageId(toPageId);
        linkDO.setLinkType(linkType.getValue());
        linkDO.setLinkContext(linkContext);
        linkDO.setConfidence(confidence);
        linkDO.setCreatedBy(createdBy != null ? createdBy : "system");
        linkDO.setExecutionId(executionId);
        wikiPageLinkMapper.insert(linkDO);
        return true;
    }

    // ==================== Ingest 两阶段智能交叉引用 ====================

    public CompletableFuture<LinkGenerationResult> generateLinksForNewPageAsync(
            Long scopeId, WikiPageDO newPageDO, List<WikiPageDO> candidatePages, Long executionId) {
        return CompletableFuture.supplyAsync(() ->
            generateLinksForNewPage(scopeId, newPageDO, candidatePages, executionId), crossRefExecutor);
    }

    public CompletableFuture<Integer> generateLinksForIngestAsync(
            IngestContext context, Long scopeId, Long executionId) {
        return CompletableFuture.supplyAsync(() -> {
            List<WikiPageDO> newPages = collectNewPages(context);
            if (newPages.isEmpty()) return 0;

            Set<Long> newPageIds = newPages.stream().map(WikiPageDO::getId).collect(Collectors.toSet());
            Set<String> newPageCategories = collectNewPageCategories(newPages);
            String pageInventory = buildTieredPageInventory(scopeId, newPageIds, newPageCategories);
            if (pageInventory.isBlank()) return 0;

            Map<Long, Set<String>> esCandidateMap = discoverEsCandidates(scopeId, newPages, newPageCategories);

            Map<Long, Set<String>> selectedMap = batchSelectCandidates(
                scopeId, newPages, pageInventory, esCandidateMap, executionId);

            int totalLinked = 0;
            int totalEvaluated = 0;
            for (WikiPageDO newPage : newPages) {
                Set<String> paths = selectedMap.getOrDefault(newPage.getId(), Set.of());
                if (paths.isEmpty()) {
                    log.debug("No candidates for new page: {}", newPage.getTitle());
                    continue;
                }
                List<WikiPageDO> candidates = resolveCandidatesByPaths(
                    scopeId, newPage.getId(), paths, maxCandidatesPerPage);
                if (candidates.isEmpty()) continue;

                LinkGenerationResult result = generateLinksForNewPage(
                    scopeId, newPage, candidates, executionId);
                totalLinked += result.linked();
                totalEvaluated += result.evaluated();
            }
            log.info("Ingest crossref: {} new pages, {} candidates evaluated, {} links created",
                newPages.size(), totalEvaluated, totalLinked);
            return totalLinked;
        }, crossRefExecutor);
    }

    private Set<String> collectNewPageCategories(List<WikiPageDO> newPages) {
        Set<String> categories = new HashSet<>();
        for (WikiPageDO p : newPages) {
            String cat = p.getCategory();
            if (cat != null && !cat.isBlank()) {
                categories.add(cat);
                if (cat.contains("/")) {
                    categories.add(cat.substring(0, cat.indexOf('/')));
                }
            }
        }
        return categories;
    }

    // ==================== Phase 1: 页面索引构建（DB 层分类过滤，快速精准）====================

    private String buildTieredPageInventory(Long scopeId, Set<Long> excludeIds, Set<String> newPageCategories) {
        List<WikiPageDO> relevantPages;

        if (!newPageCategories.isEmpty()) {
            LambdaQueryWrapper<WikiPageDO> wrapper = new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .isNotNull(WikiPageDO::getFilePath)
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath, WikiPageDO::getCategory, WikiPageDO::getSummary);
            if (!excludeIds.isEmpty()) {
                wrapper.notIn(WikiPageDO::getId, excludeIds);
            }
            wrapper.and(w -> {
                boolean first = true;
                for (String cat : newPageCategories) {
                    if (first) {
                        w.eq(WikiPageDO::getCategory, cat);
                        first = false;
                    } else {
                        w.or().eq(WikiPageDO::getCategory, cat);
                    }
                    w.or().likeRight(WikiPageDO::getCategory, cat + "/");
                }
            });
            relevantPages = wikiPageMapper.selectList(wrapper);
            log.debug("buildTieredPageInventory: {} same-category pages found for categories={}", relevantPages.size(), newPageCategories);
        } else {
            relevantPages = List.of();
        }

        if (relevantPages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 同分类/相邻分类页面\n");
        int charBudget = INVENTORY_MAX_CHARS;
        for (WikiPageDO p : relevantPages) {
            String entry = formatInventoryEntry(p);
            if (sb.length() + entry.length() + 2 > charBudget) {
                int remaining = relevantPages.size() - relevantPages.indexOf(p);
                sb.append("... (剩余 ").append(remaining).append(" 个同分类页面被省略，将由 Lint 补全)\n");
                break;
            }
            sb.append(entry).append("\n");
        }

        return sb.toString();
    }

    private String formatInventoryEntry(WikiPageDO p) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(p.getTitle() != null ? p.getTitle() : "(无标题)")
            .append(" [").append(p.getCategory() != null ? p.getCategory() : "未分类").append("]")
            .append(" (path: ").append(p.getFilePath()).append(")");
        if (p.getSummary() != null && !p.getSummary().isBlank()) {
            int remaining = INVENTORY_ENTRY_MAX_CHARS - sb.length();
            if (remaining > 10) {
                String summary = p.getSummary().length() > remaining
                    ? p.getSummary().substring(0, remaining) + "..." : p.getSummary();
                sb.append(" — ").append(summary);
            }
        }
        return sb.toString();
    }

    // ==================== Phase 1: ES 语义搜索（标题查询，所有新页面并行）====================

    private Map<Long, Set<String>> discoverEsCandidates(Long scopeId, List<WikiPageDO> newPages, Set<String> newPageCategories) {
        Map<Long, Set<String>> result = new HashMap<>();
        List<CompletableFuture<Map.Entry<Long, Set<String>>>> futures = newPages.stream()
            .map(page -> CompletableFuture.supplyAsync(() -> {
                Set<String> paths = new HashSet<>();
                String query = page.getTitle() != null ? page.getTitle() : "";
                if (!query.isBlank()) {
                    try {
                        List<SearchResultInfo> searchResults = searchService.search(scopeId, query, null);
                        for (SearchResultInfo r : searchResults) {
                            if (r.getPath() != null) paths.add(r.getPath());
                        }
                    } catch (Exception e) {
                        log.debug("ES search failed for page '{}': {}", page.getTitle(), e.getMessage());
                    }
                }
                return Map.entry(page.getId(), paths);
            }, crossRefExecutor))
            .toList();

        for (CompletableFuture<Map.Entry<Long, Set<String>>> f : futures) {
            try {
                Map.Entry<Long, Set<String>> entry = f.join();
                result.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.debug("ES candidate discovery future failed: {}", e.getMessage());
            }
        }
        return result;
    }

    // ==================== Phase 1: AI 批量筛选（所有新页面合并为 1 次 LLM 调用）====================

    private static final int NEW_PAGE_CONTENT_SNIPPET_CHARS = 1000;

    private Map<Long, Set<String>> batchSelectCandidates(
            Long scopeId, List<WikiPageDO> newPages, String pageInventory,
            Map<Long, Set<String>> esCandidateMap, Long executionId) {

        Map<Long, Set<String>> result = new HashMap<>();
        for (WikiPageDO p : newPages) {
            Set<String> esPaths = esCandidateMap.getOrDefault(p.getId(), Set.of());
            if (!esPaths.isEmpty()) {
                result.put(p.getId(), new HashSet<>(esPaths));
            }
        }

        if (chatClient == null || !chatClient.isAvailable()) {
            return result;
        }

        String globalSummaryCompact = null;
        try {
            GlobalSummaryService.GlobalSummary summary = globalSummaryService.build(scopeId);
            globalSummaryCompact = summary.toCompactPrompt();
        } catch (Exception e) {
            log.debug("GlobalSummary build failed for crossref candidate selection: {}", e.getMessage());
        }

        Map<Long, String> newPageContentSnippets = buildNewPageContentSnippets(scopeId, newPages);

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.CROSSREF, 30_000);
        if (!acquired) {
            log.warn("LlmConcurrencyBarrier CROSSREF timeout in batchSelectCandidates");
            return result;
        }
        try {
            StringBuilder newPagesInfo = new StringBuilder();
            for (int i = 0; i < newPages.size(); i++) {
                WikiPageDO p = newPages.get(i);
                newPagesInfo.append(i + 1).append(". 标题：").append(p.getTitle() != null ? p.getTitle() : "(无标题)")
                    .append("\n   分类：").append(p.getCategory() != null ? p.getCategory() : "未分类")
                    .append("\n   摘要：").append(p.getSummary() != null ? truncate(p.getSummary(), 120) : "");

                String snippet = newPageContentSnippets.get(p.getId());
                if (snippet != null && !snippet.isBlank()) {
                    newPagesInfo.append("\n   内容片段：").append(snippet);
                }

                newPagesInfo.append("\n   id：").append(p.getId())
                    .append("\n\n");
            }

            String basePrompt = String.format("""
                你是知识库交叉引用评估专家。你需要为一批新录入的知识页面，从已有知识库索引中各自选出最相关的候选页面。

                【知识库全局概览】
                %s

                【新页面列表（共 %d 个）】
                %s
                【知识库页面索引】
                以下是知识库中所有已有页面（每行包含标题、分类和摘要片段）：
                %s

                请为每个新页面分别选出最适合建立交叉引用的候选页面（每个页面最多 %d 个）。

                选择标准：
                1. 同一分类或相邻分类下的页面
                2. 标题/摘要/内容片段与新页面有明显主题重叠
                3. 可能是新页面的上位概念、下位概念、并列概念或互补知识
                4. 优先选择与新页面内容互补（而非重复）的页面
                5. 如果知识库中存在矛盾链接的页面，评估新页面是否与其中一方相关

                返回 JSON 对象：
                {
                  "selections": {
                    "新页面id1": {
                      "selectedPaths": ["path1", "path2"],
                      "reason": "简要理由"
                    },
                    "新页面id2": {
                      "selectedPaths": ["path3"],
                      "reason": "简要理由"
                    }
                  }
                }

                注意：
                - 不要选择与新页面明显无关的页面
                - 如果某个新页面确实没有相关候选，对应 selectedPaths 返回空数组
                - selectedPaths 中的值必须是上面索引中存在的 path 值
                - selections 的 key 必须是上面新页面列表中的 id 值（字符串形式）
                """,
                globalSummaryCompact != null ? globalSummaryCompact : "(全局概览不可用)",
                newPages.size(),
                newPagesInfo.toString(),
                pageInventory,
                maxCandidatesPerPage
            );
            String prompt = schemaInjector.prepend(scopeId,
                PromptTemplate.JSON_OUTPUT_CONSTRAINT + "\n\n" + basePrompt);

            String raw = chatClient.chat(prompt);
            Map<Long, Set<String>> aiSelected = parseBatchSelectedPaths(raw, newPages);
            for (Map.Entry<Long, Set<String>> entry : aiSelected.entrySet()) {
                result.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                    Set<String> merged = new HashSet<>(a);
                    merged.addAll(b);
                    return merged;
                });
            }
            log.info("Batch candidate selection: {} new pages processed, AI selections merged (with GlobalSummary + content snippets)", newPages.size());
        } catch (Exception e) {
            log.warn("batchSelectCandidates AI call failed: {}", e.getMessage());
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.CROSSREF);
        }
        return result;
    }

    private Map<Long, String> buildNewPageContentSnippets(Long scopeId, List<WikiPageDO> newPages) {
        Map<Long, String> snippets = new HashMap<>();
        for (WikiPageDO p : newPages) {
            if (p.getFilePath() == null || p.getFilePath().isBlank()) continue;
            String content = readPageContent(scopeId, p.getFilePath());
            if (content != null && !content.isBlank()) {
                snippets.put(p.getId(), truncate(content, NEW_PAGE_CONTENT_SNIPPET_CHARS));
            }
        }
        return snippets;
    }

    private Map<Long, Set<String>> parseBatchSelectedPaths(String raw, List<WikiPageDO> newPages) {
        Map<Long, Set<String>> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        try {
            String json = extractJsonObject(raw);
            if (json == null) return result;
            var root = objectMapper.readValue(json, Map.class);
            Object selectionsObj = root.get("selections");
            if (selectionsObj instanceof Map<?, ?> selections) {
                Map<String, Long> idMap = new HashMap<>();
                for (WikiPageDO p : newPages) {
                    idMap.put(String.valueOf(p.getId()), p.getId());
                }
                for (Map.Entry<?, ?> entry : selections.entrySet()) {
                    String keyStr = String.valueOf(entry.getKey());
                    Long pageId = idMap.get(keyStr);
                    if (pageId == null) continue;
                    if (entry.getValue() instanceof Map<?, ?> selMap) {
                        Object pathsObj = selMap.get("selectedPaths");
                        if (pathsObj instanceof List<?> pathList) {
                            Set<String> paths = new HashSet<>();
                            for (Object p : pathList) {
                                if (p instanceof String s && !s.isBlank()) paths.add(s);
                            }
                            if (!paths.isEmpty()) result.put(pageId, paths);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("parseBatchSelectedPaths failed: {}", e.getMessage());
        }
        return result;
    }

    // ==================== Phase 2: 深度评估（并行化）====================

    public LinkGenerationResult generateLinksForNewPage(
            Long scopeId, WikiPageDO newPageDO, List<WikiPageDO> candidatePages, Long executionId) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new LinkGenerationResult(0, 0, 0, 0, "AI 未配置，跳过交叉引用生成");
        }
        if (candidatePages == null || candidatePages.isEmpty()) {
            return new LinkGenerationResult(0, 0, 0, 0, "无候选页面，跳过交叉引用");
        }

        String newPageContent = readPageContent(scopeId, newPageDO.getFilePath());
        List<WikiPageDO> candidates = candidatePages.size() > maxCandidatesPerPage
            ? candidatePages.subList(0, maxCandidatesPerPage) : candidatePages;

        List<EvalTask> tasks = new ArrayList<>();
        for (WikiPageDO candidate : candidates) {
            if (candidate.getId().equals(newPageDO.getId())) continue;
            CompletableFuture<LinkDecision> f = CompletableFuture.supplyAsync(() ->
                evaluateAndCreateLink(scopeId, newPageDO, candidate, newPageContent, executionId),
                crossRefExecutor);
            tasks.add(new EvalTask(candidate, f));
        }

        int evaluated = 0, linked = 0, rejected = 0, failed = 0;
        StringBuilder report = new StringBuilder();

        for (EvalTask task : tasks) {
            LinkDecision decision;
            try {
                decision = task.future().join();
            } catch (Exception e) {
                log.debug("Parallel evaluation future failed for candidate '{}': {}",
                    task.candidate().getTitle(), e.getMessage());
                failed++;
                continue;
            }
            evaluated++;
            if (decision == null) {
                failed++;
            } else if (decision.shouldLink()) {
                linked++;
                report.append("- 已创建链接：「").append(newPageDO.getTitle())
                    .append("」→「").append(task.candidate().getTitle())
                    .append("」(").append(decision.linkType().getValue()).append(")\n");
            } else {
                rejected++;
            }
        }

        String summary = String.format("新页面交叉引用：评估 %d 对，创建 %d 条，拒绝 %d，失败 %d\n%s",
            evaluated, linked, rejected, failed, report);
        return new LinkGenerationResult(evaluated, linked, rejected, failed, summary);
    }

    // ==================== 核心评估（Ingest + Lint 统一）====================

    public LinkDecision evaluateAndCreateLink(Long scopeId, WikiPageDO pageA, WikiPageDO pageB,
                                               String pageAContent, Long executionId) {
        String pageBContent = readPageContent(scopeId, pageB.getFilePath());

        WikiPageLinkDO existing = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageA.getId())
                .eq(WikiPageLinkDO::getToPageId, pageB.getId())
        );
        if (existing != null) {
            return new LinkDecision(false, LinkType.RELATED, "已存在链接", 1.0, "already exists");
        }

        LinkDecision deterministicDecision = tryDeterministicLink(scopeId, pageA, pageB);
        if (deterministicDecision != null && deterministicDecision.shouldLink()) {
            createLink(scopeId, pageA.getId(), pageB.getId(), deterministicDecision.linkType(),
                deterministicDecision.linkContext(), deterministicDecision.confidence(), "lint_deterministic", executionId);
            createReverseLink(scopeId, pageA, pageB, deterministicDecision, executionId);
            log.info("Deterministic crossref link created: {}→{} (type={})",
                pageA.getTitle(), pageB.getTitle(), deterministicDecision.linkType().getValue());
            return deterministicDecision;
        }

        String prompt = buildEvaluationPrompt(scopeId, pageA.getTitle(), pageAContent,
            pageB.getTitle(), pageBContent);

        boolean acquired = llmConcurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.CROSSREF, 30_000);
        if (!acquired) {
            log.warn("LlmConcurrencyBarrier CROSSREF timeout, skipping crossref eval {}↔{}",
                pageA.getTitle(), pageB.getTitle());
            return null;
        }
        try {
            String raw = chatClient.chat(prompt);
            LinkDecision decision = parseLinkDecision(raw);
            if (decision == null) {
                log.debug("Failed to parse link decision for {}↔{}", pageA.getTitle(), pageB.getTitle());
                return null;
            }
            if (decision.shouldLink() && decision.confidence() >= confidenceThreshold) {
                createLink(scopeId, pageA.getId(), pageB.getId(), decision.linkType(),
                    decision.linkContext(), decision.confidence(), "ingest_ai", executionId);

                createReverseLink(scopeId, pageA, pageB, decision, executionId);

                return decision;
            }
            return new LinkDecision(false, decision.linkType(), decision.linkContext(),
                decision.confidence(), decision.reason());
        } catch (Exception e) {
            log.warn("evaluateAndCreateLink failed for {}↔{}: {}",
                pageA.getTitle(), pageB.getTitle(), e.getMessage());
            return null;
        } finally {
            llmConcurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.CROSSREF);
        }
    }

    private void createReverseLink(Long scopeId, WikiPageDO pageA, WikiPageDO pageB,
                                    LinkDecision decision, Long executionId) {
        WikiPageLinkDO reverseExisting = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, pageB.getId())
                .eq(WikiPageLinkDO::getToPageId, pageA.getId())
        );
        if (reverseExisting != null) return;

        LinkType reverseType = decision.linkType();
        String reverseContext = decision.linkContext() != null ? decision.linkContext() : "";
        if (!reverseContext.isBlank()) {
            reverseContext = "[反向] " + reverseContext;
        } else {
            reverseContext = "[反向] 由「" + pageA.getTitle() + "」→「" + pageB.getTitle() + "」自动推断";
        }

        createLink(scopeId, pageB.getId(), pageA.getId(), reverseType,
            reverseContext, decision.confidence(), "ingest_ai_reverse", executionId);
        log.debug("Created reverse link: {}→{} (type={})", pageB.getTitle(), pageA.getTitle(), reverseType.getValue());
    }

    // ==================== 确定性快速链接（跳过 LLM）====================

    LinkDecision tryDeterministicLink(Long scopeId, WikiPageDO pageA, WikiPageDO pageB) {
        try {
            int threshold = (int) Math.ceil(minSharedKeywordsDefault * deterministicKeywordMultiplier);

            List<WikiPageKeywordDO> kwA = wikiPageKeywordMapper.selectList(
                new LambdaQueryWrapper<WikiPageKeywordDO>()
                    .eq(WikiPageKeywordDO::getScopeId, scopeId)
                    .eq(WikiPageKeywordDO::getPageId, pageA.getId())
            );
            if (kwA.size() < threshold) return null;

            List<WikiPageKeywordDO> kwB = wikiPageKeywordMapper.selectList(
                new LambdaQueryWrapper<WikiPageKeywordDO>()
                    .eq(WikiPageKeywordDO::getScopeId, scopeId)
                    .eq(WikiPageKeywordDO::getPageId, pageB.getId())
            );
            if (kwB.size() < threshold) return null;

            Set<String> setA = kwA.stream().map(WikiPageKeywordDO::getKeyword).collect(Collectors.toSet());
            Set<String> setB = kwB.stream().map(WikiPageKeywordDO::getKeyword).collect(Collectors.toSet());

            long shared = setA.stream().filter(setB::contains).count();
            if (shared >= threshold) {
                String context = "共享 " + shared + " 个关键词（确定性阈值 " + threshold + "）："
                    + setA.stream().filter(setB::contains).limit(5).collect(Collectors.joining(", "));
                double confidence = Math.min(0.95, 0.6 + (shared - threshold) * 0.05);
                return new LinkDecision(true, LinkType.RELATED, context, confidence,
                    "deterministic: " + shared + " shared keywords >= " + threshold);
            }
        } catch (Exception e) {
            log.debug("tryDeterministicLink failed for {}↔{}: {}", pageA.getTitle(), pageB.getTitle(), e.getMessage());
        }
        return null;
    }

    // ==================== 内部工具方法 ====================

    private String buildEvaluationPrompt(Long scopeId, String pageATitle, String pageAContent,
                                          String pageBTitle, String pageBContent) {
        String truncA = truncateForPreview(pageAContent);
        String truncB = truncateForPreview(pageBContent);
        String basePrompt = PromptTemplate.JSON_OUTPUT_CONSTRAINT + "\n\n"
            + """
            你是知识库链接质量评估专家。现有两个页面，请判断是否应该建立交叉引用。

            页面 A：「""" + pageATitle + """
            」
            内容摘要：
            ---
            """ + truncA + """
            ---

            页面 B：「""" + pageBTitle + """
            」
            内容摘要：
            ---
            """ + truncB + """
            ---

            请基于以下维度综合判断：
            1. **语义相关性**：两个页面是否讨论同一主题的不同方面？
            2. **互补性**：合在一起是否能提供更完整的理解？
            3. **链接价值**：建立链接后能否提升知识发现性？
            4. **避免过度链接**：链接是否自然且有意义（非强制拼接）？

            返回 JSON 对象（不是数组），包含：
            - shouldLink: 是否应该建立链接（boolean）
            - linkType: 链接类型（related/reference/supplement/dependency/contradiction）
            - linkContext: 链接上下文说明（1-2 句话）
            - confidence: 置信度（0.0-1.0）
            - reason: 判断理由（1 句话）

            linkType 定义：
            - related: 一般关联关系
            - reference: A 引用或参考 B
            - supplement: A 补充 B 的信息
            - dependency: A 依赖 B 或 B 是 A 的前提
            - contradiction: A 与 B 存在内容矛盾

            注意：
            - 如果 shouldLink=false，仍需返回完整 JSON
            - 如果两个页面只是提到相同名词但语境完全不同，shouldLink=false
            - confidence < 0.5 时系统不会创建链接
            """;
        return scopeId != null ? schemaInjector.prepend(scopeId, basePrompt) : basePrompt;
    }

    private String truncateForPreview(String content) {
        if (content == null || content.isBlank()) return "(无内容)";
        if (content.length() > CONTENT_PREVIEW_MAX_CHARS) {
            return content.substring(0, CONTENT_PREVIEW_MAX_CHARS) + "\n...(内容截断)";
        }
        return content;
    }

    private LinkDecision parseLinkDecision(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String json = extractJsonObject(raw);
            if (json == null) return null;
            var map = objectMapper.readValue(json, Map.class);

            Boolean shouldLink = null;
            Object slObj = map.get("shouldLink");
            if (slObj instanceof Boolean b) shouldLink = b;
            else if (slObj instanceof String s) shouldLink = "true".equalsIgnoreCase(s);
            if (shouldLink == null) return null;

            String linkTypeStr = map.get("linkType") != null ? map.get("linkType").toString() : "related";
            LinkType linkType = LinkType.fromString(linkTypeStr);

            String linkContext = map.get("linkContext") != null ? map.get("linkContext").toString() : "";
            String reason = map.get("reason") != null ? map.get("reason").toString() : "";

            double confidence = 0.0;
            Object confObj = map.get("confidence");
            if (confObj instanceof Number n) confidence = n.doubleValue();
            else if (confObj instanceof String s) {
                try { confidence = Double.parseDouble(s); } catch (NumberFormatException ignore) {}
            }

            return new LinkDecision(shouldLink, linkType, linkContext, confidence, reason);
        } catch (Exception e) {
            log.debug("parseLinkDecision failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return null;
    }

    private WikiPageDO[] resolvePagePair(Long scopeId, LintFindingDO f) {
        Long relatedPageId = null;
        String relatedPagePath = null;
        if (f.getExtra() != null) {
            try {
                var extra = objectMapper.readValue(f.getExtra(), Map.class);
                if (extra.get("relatedPageId") instanceof Number) {
                    relatedPageId = ((Number) extra.get("relatedPageId")).longValue();
                }
                if (extra.get("relatedPagePath") instanceof String rp) {
                    relatedPagePath = rp;
                }
            } catch (Exception ignore) {}
        }
        WikiPageDO pageA = f.getAssetId() != null ? wikiPageMapper.selectById(f.getAssetId()) : null;
        WikiPageDO pageB = null;
        if (relatedPageId != null) {
            pageB = wikiPageMapper.selectById(relatedPageId);
        }
        if (pageB == null && relatedPagePath != null && !relatedPagePath.isBlank()) {
            pageB = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getFilePath, relatedPagePath)
                    .last("LIMIT 1")
            );
        }
        return new WikiPageDO[]{pageA, pageB};
    }

    private String readPageContent(Long scopeId, String filePath) {
        try {
            String storagePath = filePath.startsWith("pages/") ? "wiki/" + filePath : filePath;
            byte[] contentBytes = storageProvider.read(String.valueOf(scopeId), storagePath);
            if (contentBytes != null) {
                return new String(contentBytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to read page content for scopeId={}, filePath={}: {}", scopeId, filePath, e.getMessage());
        }
        return "";
    }

    private String extractExtraField(String extraJson, String field) {
        if (extraJson == null || extraJson.isBlank()) return "";
        try {
            var extra = objectMapper.readValue(extraJson, Map.class);
            Object val = extra.get(field);
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private List<WikiPageDO> resolveCandidatesByPaths(Long scopeId, Long newPageId,
                                                       Set<String> paths, int limit) {
        if (paths.isEmpty()) return List.of();
        List<WikiPageDO> candidates = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .ne(WikiPageDO::getId, newPageId)
                .in(WikiPageDO::getFilePath, paths)
                .isNotNull(WikiPageDO::getFilePath)
        );
        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
        }
        return candidates;
    }

    private List<WikiPageDO> collectNewPages(IngestContext context) {
        List<WikiPageDO> newPages = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        if (context.getSummaryPage() != null && context.getSummaryPage().getId() != null) {
            newPages.add(context.getSummaryPage());
            seenIds.add(context.getSummaryPage().getId());
        }
        for (WikiPageDO p : context.getEntityPages().values()) {
            if (p != null && p.getId() != null && !seenIds.contains(p.getId())) {
                newPages.add(p);
                seenIds.add(p.getId());
            }
        }
        for (WikiPageDO p : context.getChapterPages().values()) {
            if (p != null && p.getId() != null && !seenIds.contains(p.getId())) {
                newPages.add(p);
                seenIds.add(p.getId());
            }
        }
        for (WikiPageDO p : context.getUpdatedPages().values()) {
            if (p != null && p.getId() != null && !seenIds.contains(p.getId())) {
                newPages.add(p);
                seenIds.add(p.getId());
            }
        }
        return newPages;
    }

    // ==================== Lint 跨分类交叉引用补全（感知 Ingest 活动）====================

    public CrossRefOutcome detectAndFixCrossCategoryLinks(Long scopeId, Long executionId,
                                                           LintRulesConfig rulesConfig) {
        if (chatClient == null || !chatClient.isAvailable()) {
            return new CrossRefOutcome(0, 0, 0, 0, "AI 未配置，跳过跨分类交叉引用检测");
        }

        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(24);
        List<WikiPageDO> todayPages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .ge(WikiPageDO::getUpdatedAt, since)
                .isNotNull(WikiPageDO::getFilePath)
                .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                        WikiPageDO::getCategory, WikiPageDO::getSummary)
        );
        if (todayPages.isEmpty()) {
            return new CrossRefOutcome(0, 0, 0, 0, "过去 24 小时无 Ingest 活动，无需补全跨分类交叉引用");
        }

        int autoFixLimit = rulesConfig.getDiagnosticStandard().getAutoFixLimit();
        int fixed = 0, failed = 0, skipped = 0, rejected = 0;
        StringBuilder report = new StringBuilder();

        for (WikiPageDO todayPage : todayPages) {
            if (fixed + failed + rejected >= autoFixLimit) {
                skipped += todayPages.size() - todayPages.indexOf(todayPage);
                break;
            }

            String pageCategory = todayPage.getCategory();
            if (pageCategory == null || pageCategory.isBlank()) continue;

            Set<Long> linkedPageIds = getLinkedPageIds(scopeId, todayPage.getId());

            List<WikiPageDO> crossCategoryCandidates = findCrossCategoryCandidates(
                scopeId, todayPage, pageCategory, linkedPageIds);

            if (crossCategoryCandidates.isEmpty()) continue;

            String pageAContent = readPageContent(scopeId, todayPage.getFilePath());

            List<EvalTask> tasks = crossCategoryCandidates.stream()
                .map(candidate -> new EvalTask(candidate,
                    CompletableFuture.supplyAsync(() ->
                        evaluateAndCreateLink(scopeId, todayPage, candidate, pageAContent, executionId),
                        crossRefExecutor)))
                .toList();

            for (EvalTask task : tasks) {
                try {
                    LinkDecision decision = task.future().join();
                    if (decision != null && decision.shouldLink()) {
                        fixed++;
                        report.append("- 跨分类链接：").append(todayPage.getTitle())
                            .append(" [").append(pageCategory).append("] ↔ ")
                            .append(task.candidate().getTitle())
                            .append(" [").append(task.candidate().getCategory()).append("]\n");
                    } else if (decision != null) {
                        rejected++;
                    }
                } catch (Exception e) {
                    log.debug("Cross-category eval failed {}↔{}: {}",
                        todayPage.getTitle(), task.candidate().getTitle(), e.getMessage());
                    failed++;
                }
            }
        }

        String summary = String.format(
            "跨分类交叉引用补全（Ingest 活动感知）：扫描 %d 个今日页面，成功 %d，失败 %d，拒绝 %d，跳过 %d\n%s",
            todayPages.size(), fixed, failed, rejected, skipped, report);
        log.info("Lint cross-category crossref: {}", summary);
        return new CrossRefOutcome(fixed, failed, skipped, rejected, summary);
    }

    private Set<Long> getLinkedPageIds(Long scopeId, Long pageId) {
        List<WikiPageLinkDO> links = wikiPageLinkMapper.selectList(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .and(w -> w.eq(WikiPageLinkDO::getFromPageId, pageId)
                           .or()
                           .eq(WikiPageLinkDO::getToPageId, pageId))
        );
        Set<Long> ids = new HashSet<>();
        for (WikiPageLinkDO l : links) {
            ids.add(l.getFromPageId());
            ids.add(l.getToPageId());
        }
        ids.remove(pageId);
        return ids;
    }

    private List<WikiPageDO> findCrossCategoryCandidates(Long scopeId, WikiPageDO todayPage,
                                                          String pageCategory, Set<Long> linkedPageIds) {
        try {
            String query = todayPage.getTitle() != null ? todayPage.getTitle() : "";
            if (query.isBlank()) return List.of();

            List<SearchResultInfo> results = searchService.search(scopeId, query, null);
            if (results.isEmpty()) return List.of();

            Set<String> resultPaths = results.stream()
                .map(SearchResultInfo::getPath)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());

            if (resultPaths.isEmpty()) return List.of();

            List<WikiPageDO> candidates = wikiPageMapper.selectList(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .ne(WikiPageDO::getId, todayPage.getId())
                    .in(WikiPageDO::getFilePath, resultPaths)
                    .isNotNull(WikiPageDO::getFilePath)
                    .isNotNull(WikiPageDO::getCategory)
                    .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath,
                            WikiPageDO::getCategory, WikiPageDO::getSummary)
            );

            return candidates.stream()
                .filter(c -> !linkedPageIds.contains(c.getId()))
                .filter(c -> !isSameCategoryFamily(c.getCategory(), pageCategory))
                .limit(maxCandidatesPerPage)
                .toList();
        } catch (Exception e) {
            log.debug("findCrossCategoryCandidates failed for '{}': {}", todayPage.getTitle(), e.getMessage());
            return List.of();
        }
    }

    private boolean isSameCategoryFamily(String cat1, String cat2) {
        if (cat1 == null || cat2 == null) return false;
        if (cat1.equals(cat2)) return true;
        String parent1 = cat1.contains("/") ? cat1.substring(0, cat1.indexOf('/')) : cat1;
        String parent2 = cat2.contains("/") ? cat2.substring(0, cat2.indexOf('/')) : cat2;
        return parent1.equals(parent2);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
