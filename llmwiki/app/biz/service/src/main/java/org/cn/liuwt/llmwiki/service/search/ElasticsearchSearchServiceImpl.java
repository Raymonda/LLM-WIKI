package org.cn.liuwt.llmwiki.service.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SearchIndexRetryDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SearchIndexRetryMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.facade.model.WikiPageInfo;
import org.cn.liuwt.llmwiki.integration.search.ElasticsearchProperties;
import org.cn.liuwt.llmwiki.integration.search.WikiPageDocument;
import org.cn.liuwt.llmwiki.integration.search.WikiPageSearchRepository;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

@Service
public class ElasticsearchSearchServiceImpl implements SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchSearchServiceImpl.class);

    @Autowired
    private WikiPageSearchRepository searchRepository;

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private ElasticsearchProperties esProperties;

    @Autowired
    private SearchIndexRetryMapper retryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int ES_BATCH_SIZE = 5000;
    private static final int MAX_SEARCH_RESULTS = 100;
    private static final int REBUILD_PARALLELISM = 32;

    @Override
    public void bulkIndexPages(Long scopeId, List<WikiPageDO> pages) {
        if (pages == null || pages.isEmpty()) return;
        String scopeIdStr = String.valueOf(scopeId);
        List<WikiPageDocument> allDocs = convertToDocuments(scopeIdStr, pages);
        indexInBatches(scopeId, allDocs);
    }

    private List<WikiPageDocument> convertToDocuments(String scopeIdStr, List<WikiPageDO> pages) {
        List<WikiPageDocument> docs = new ArrayList<>(pages.size());
        for (WikiPageDO page : pages) {
            WikiPageDocument doc = new WikiPageDocument();
            doc.setId(page.getId());
            doc.setTitle(page.getTitle());
            doc.setFilePath(page.getFilePath());
            doc.setCategory(page.getCategory());
            doc.setSummary(page.getSummary());
            doc.setScopeId(page.getScopeId());
            doc.setHealthStatus(page.getHealthStatus());
            doc.setVisibility(page.getVisibility());
            doc.setPageType(page.getPageType());
            doc.setLifecycleStatus(page.getLifecycleStatus());
            doc.setUpdatedAt(page.getUpdatedAt());
            doc.setDeprecatedAt(page.getDeprecatedAt());
            if (storageProvider.exists(scopeIdStr, "wiki/" + page.getFilePath())) {
                byte[] contentBytes = storageProvider.read(scopeIdStr, "wiki/" + page.getFilePath());
                if (contentBytes != null) {
                    doc.setContent(new String(contentBytes, StandardCharsets.UTF_8));
                }
            }
            docs.add(doc);
        }
        return docs;
    }

    private List<WikiPageDocument> convertToDocumentsParallel(String scopeIdStr, List<WikiPageDO> pages) {
        List<WikiPageDocument> docs = new ArrayList<>(pages.size());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ForkJoinPool customPool = new ForkJoinPool(REBUILD_PARALLELISM);
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            final WikiPageDO page = pages.get(i);
            WikiPageDocument doc = new WikiPageDocument();
            doc.setId(page.getId());
            doc.setTitle(page.getTitle());
            doc.setFilePath(page.getFilePath());
            doc.setCategory(page.getCategory());
            doc.setSummary(page.getSummary());
            doc.setScopeId(page.getScopeId());
            doc.setHealthStatus(page.getHealthStatus());
            doc.setVisibility(page.getVisibility());
            doc.setPageType(page.getPageType());
            doc.setLifecycleStatus(page.getLifecycleStatus());
            doc.setUpdatedAt(page.getUpdatedAt());
            doc.setDeprecatedAt(page.getDeprecatedAt());
            docs.add(doc);
            if (page.getFilePath() != null) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String storagePath = "wiki/" + page.getFilePath();
                    if (storageProvider.exists(scopeIdStr, storagePath)) {
                        byte[] contentBytes = storageProvider.read(scopeIdStr, storagePath);
                        if (contentBytes != null) {
                            doc.setContent(new String(contentBytes, StandardCharsets.UTF_8));
                        }
                    }
                }, customPool);
                futures.add(future);
            }
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            LOG.warn("Parallel file read had some failures, continuing with partial content: {}", e.getMessage());
        } finally {
            customPool.shutdown();
        }
        return docs;
    }

    private void indexInBatches(Long scopeId, List<WikiPageDocument> allDocs) {
        int total = allDocs.size();
        int batches = (total + ES_BATCH_SIZE - 1) / ES_BATCH_SIZE;
        for (int i = 0; i < batches; i++) {
            int from = i * ES_BATCH_SIZE;
            int to = Math.min(from + ES_BATCH_SIZE, total);
            List<WikiPageDocument> batch = allDocs.subList(from, to);
            try {
                searchRepository.saveAll(batch);
                LOG.info("ES bulk batch {}/{}, indexed {} pages for scopeId={}", i + 1, batches, batch.size(), scopeId);
            } catch (Exception e) {
                LOG.warn("ES bulk batch {}/{} failed, falling back to per-page retry. scopeId={}, batchSize={}, err={}",
                    i + 1, batches, scopeId, batch.size(), e.getMessage());
                for (WikiPageDocument doc : batch) {
                    try {
                        searchRepository.save(doc);
                    } catch (Exception perEx) {
                        enqueueRetry(scopeId, doc.getId(), "INDEX", doc, perEx);
                    }
                }
            }
        }
        LOG.info("ES bulkIndexPages complete: {} pages in {} batches for scopeId={}", total, batches, scopeId);
    }

    @Override
    public List<SearchResultInfo> search(Long scopeId, String query, String category) {
        List<SearchResultInfo> results = searchWikiPages(scopeId, query, category);
        results.sort((a, b) -> Double.compare(
            b.getScore() != null ? b.getScore() : 0.0,
            a.getScore() != null ? a.getScore() : 0.0));
        return results;
    }

    private List<SearchResultInfo> searchWikiPages(Long scopeId, String query, String category) {
        List<HighlightField> highlightFields = new ArrayList<>();
        highlightFields.add(new HighlightField("title"));
        highlightFields.add(new HighlightField("summary"));
        highlightFields.add(new HighlightField("content"));

        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(q -> q.bool(b -> {
                b.must(m -> m.term(t -> t.field("scopeId").value(scopeId)));
                b.must(m -> m.multiMatch(mm -> mm
                    .fields("title", "summary", "content")
                    .query(query)
                    .type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.MostFields)
                ));
                // Exclude DEPRECATED/MERGED/DELETED/MERGING pages from search results
                b.mustNot(m -> m.terms(t -> t
                    .field("lifecycleStatus")
                    .terms(ts -> ts.value(List.of(
                        co.elastic.clients.elasticsearch._types.FieldValue.of("DEPRECATED"),
                        co.elastic.clients.elasticsearch._types.FieldValue.of("MERGED"),
                        co.elastic.clients.elasticsearch._types.FieldValue.of("DELETED"),
                        co.elastic.clients.elasticsearch._types.FieldValue.of("MERGING")
                    )))
                ));
                if (category != null && !category.isEmpty()) {
                    b.must(m -> m.term(t -> t.field("category").value(category)));
                }
                return b;
            }))
            .withHighlightQuery(new HighlightQuery(
                new Highlight(highlightFields),
                WikiPageDocument.class
            ))
            .withMaxResults(MAX_SEARCH_RESULTS)
            .build();

        try {
            SearchHits<WikiPageDocument> searchHits = elasticsearchTemplate.search(nativeQuery, WikiPageDocument.class);

            List<SearchResultInfo> results = new ArrayList<>();
            for (org.springframework.data.elasticsearch.core.SearchHit<WikiPageDocument> hit : searchHits) {
                WikiPageDocument doc = hit.getContent();
                SearchResultInfo info = new SearchResultInfo();
                info.setId(doc.getId());
                info.setTitle(doc.getTitle());
                info.setPath(doc.getFilePath());
                info.setCategory(doc.getCategory());
                info.setSummary(doc.getSummary());
                info.setHealthStatus(doc.getHealthStatus());
                info.setScore((double) hit.getScore());
                info.setPageType(doc.getPageType());
                info.setSourceScopeId(doc.getScopeId());
                info.setLifecycleStatus(doc.getLifecycleStatus());
                info.setDeprecatedAt(doc.getDeprecatedAt());
                info.setResultType(SearchResultInfo.ResultType.WIKI_PAGE);

                if (hit.getHighlightFields().containsKey("title")) {
                    info.setHighlightedTitle(hit.getHighlightFields().get("title"));
                }
                if (hit.getHighlightFields().containsKey("summary")) {
                    info.setHighlightedSummary(hit.getHighlightFields().get("summary"));
                }
                if (hit.getHighlightFields().containsKey("content")) {
                    info.setHighlightedContent(hit.getHighlightFields().get("content"));
                }

                results.add(info);
            }

            if (results.isEmpty()) {
                LOG.debug("ES wiki search returned 0 results: scopeId={}, query={}, category={}", scopeId, query, category);
            }

            return results;
        } catch (Exception e) {
            LOG.error("ES wiki search failed: scopeId={}, query={}, category={}, error={}", scopeId, query, category, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<WikiPageInfo> suggest(Long scopeId, String prefix) {
        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(q -> q.bool(b -> {
                b.must(m -> m.term(t -> t.field("scopeId").value(scopeId)));
                b.must(m -> m.prefix(p -> p.field("title").value(prefix)));
                return b;
            }))
            .withMaxResults(10)
            .build();

        SearchHits<WikiPageDocument> searchHits = elasticsearchTemplate.search(nativeQuery, WikiPageDocument.class);

        List<WikiPageInfo> results = new ArrayList<>();
        for (org.springframework.data.elasticsearch.core.SearchHit<WikiPageDocument> hit : searchHits) {
            WikiPageDocument doc = hit.getContent();
            WikiPageInfo info = new WikiPageInfo();
            info.setId(doc.getId());
            info.setTitle(doc.getTitle());
            info.setPath(doc.getFilePath());
            info.setCategory(doc.getCategory());
            info.setSummary(doc.getSummary());
            info.setHealthStatus(doc.getHealthStatus());
            results.add(info);
        }
        return results;
    }

    @Override
    public void indexPage(Long scopeId, Long pageId, String title, String filePath, String category,
                          String summary, String content, String healthStatus, String visibility,
                          String lifecycleStatus) {
        WikiPageDocument doc = buildDocument(scopeId, pageId, title, filePath, category, summary, content, healthStatus, visibility, lifecycleStatus);
        try {
            searchRepository.save(doc);
        } catch (Exception e) {
            LOG.warn("ES indexPage failed, enqueue retry. scopeId={}, pageId={}, err={}", scopeId, pageId, e.getMessage());
            enqueueRetry(scopeId, pageId, "INDEX", doc, e);
        }
    }

    @Override
    public void removePage(Long scopeId, Long pageId) {
        try {
            searchRepository.deleteById(pageId);
        } catch (Exception e) {
            LOG.warn("ES removePage failed, enqueue retry. scopeId={}, pageId={}, err={}", scopeId, pageId, e.getMessage());
            enqueueRetry(scopeId, pageId, "REMOVE", null, e);
        }
    }

    @Override
    public void rebuildIndex(Long scopeId) {
        List<WikiPageDO> pages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
        );

        String scopeIdStr = String.valueOf(scopeId);
        List<WikiPageDocument> allDocs = convertToDocumentsParallel(scopeIdStr, pages);
        if (!allDocs.isEmpty()) {
            indexInBatches(scopeId, allDocs);
        }
        LOG.info("Rebuilt ES index for scopeId={}, pageCount={}", scopeId, allDocs.size());
    }

    /**
     * Used by retry scheduler to replay an operation without re-enqueuing on failure.
     */
    public void indexDocumentDirect(WikiPageDocument doc) {
        searchRepository.save(doc);
    }

    public void removeDocumentDirect(Long pageId) {
        searchRepository.deleteById(pageId);
    }

    public WikiPageDocument deserializeDocument(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, WikiPageDocument.class);
    }

    private WikiPageDocument buildDocument(Long scopeId, Long pageId, String title, String filePath, String category,
                                           String summary, String content, String healthStatus, String visibility,
                                           String lifecycleStatus) {
        WikiPageDocument doc = new WikiPageDocument();
        doc.setId(pageId);
        doc.setTitle(title);
        doc.setFilePath(filePath);
        doc.setCategory(category);
        doc.setSummary(summary);
        doc.setContent(content);
        doc.setScopeId(scopeId);
        doc.setHealthStatus(healthStatus);
        doc.setVisibility(visibility);
        doc.setLifecycleStatus(lifecycleStatus);
        WikiPageDO pageDO = wikiPageMapper.selectById(pageId);
        if (pageDO != null) {
            doc.setUpdatedAt(pageDO.getUpdatedAt());
            doc.setPageType(pageDO.getPageType());
            doc.setDeprecatedAt(pageDO.getDeprecatedAt());
        }
        return doc;
    }

    private void enqueueRetry(Long scopeId, Long pageId, String operation, WikiPageDocument doc, Exception cause) {
        try {
            SearchIndexRetryDO record = new SearchIndexRetryDO();
            record.setScopeId(scopeId);
            record.setPageId(pageId);
            record.setOperation(operation);
            if (doc != null) {
                try {
                    record.setPayload(objectMapper.writeValueAsString(doc));
                } catch (JsonProcessingException je) {
                    LOG.error("Failed to serialize retry payload, scopeId={}, pageId={}", scopeId, pageId, je);
                    record.setPayload(null);
                }
            }
            record.setRetryCount(0);
            record.setMaxAttempts(esProperties.getRetry().getMaxAttempts());
            record.setStatus("PENDING");
            record.setLastError(truncate(cause.getMessage(), 1024));
            record.setNextRetryAt(LocalDateTime.now().plusSeconds(esProperties.getRetry().getIntervalMs() / 1000));
            retryMapper.insert(record);
        } catch (Exception persistEx) {
            LOG.error("Failed to enqueue search retry record, scopeId={}, pageId={}, op={}", scopeId, pageId, operation, persistEx);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
