package org.cn.liuwt.llmwiki.service.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.integration.search.ElasticsearchProperties;
import org.cn.liuwt.llmwiki.integration.search.WikiPageDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates Elasticsearch reachability at startup and triggers a full reindex when the
 * ES index is empty but the database already has wiki pages (handles first migration
 * from the legacy MySQL-based search).
 */
@Component
public class SearchIndexBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SearchIndexBootstrap.class);

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Autowired
    private ElasticsearchProperties esProperties;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private ElasticsearchSearchServiceImpl searchService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        pingOrFail();
        cleanupLegacyChaptersIndex();
        ensureIndex();
        reindexIfEmpty();
    }

    private void cleanupLegacyChaptersIndex() {
        try {
            boolean exists = elasticsearchClient.indices()
                .exists(e -> e.index("llmwiki-chapters"))
                .value();
            if (exists) {
                elasticsearchClient.indices().delete(d -> d.index("llmwiki-chapters"));
                LOG.info("Deleted legacy ES index llmwiki-chapters (dual-source search has been removed)");
            }
        } catch (Exception e) {
            LOG.warn("Failed to cleanup legacy llmwiki-chapters index: {}", e.getMessage());
        }
    }

    private void pingOrFail() {
        try {
            boolean ok = elasticsearchClient.ping().value();
            if (!ok) {
                throw new IllegalStateException("Elasticsearch ping returned false: " + esProperties.getUris());
            }
            LOG.info("Elasticsearch reachable at {}", esProperties.getUris());
        } catch (Exception e) {
            throw new IllegalStateException("Elasticsearch is unreachable at " + esProperties.getUris()
                + ". Start it (e.g. `docker compose up -d elasticsearch`) or override llmwiki.elasticsearch.uris.", e);
        }
    }

    private void ensureIndex() {
        IndexOperations pageOps = elasticsearchTemplate.indexOps(WikiPageDocument.class);
        if (!pageOps.exists()) {
            pageOps.createWithMapping();
            LOG.info("Created ES index for {}", esProperties.getIndexName());
        }
    }

    private void reindexIfEmpty() {
        long esCount;
        try {
            esCount = elasticsearchTemplate.count(Query.findAll(), WikiPageDocument.class);
        } catch (Exception e) {
            LOG.warn("Failed to count ES documents, skip auto-rebuild: {}", e.getMessage());
            return;
        }
        if (esCount > 0) {
            LOG.info("ES index already populated (count={}), skip auto-rebuild", esCount);
            return;
        }

        Long dbCount = wikiPageMapper.selectCount(null);
        if (dbCount == null || dbCount == 0) {
            LOG.info("No wiki pages in DB, skip auto-rebuild");
            return;
        }

        LOG.warn("ES index is empty but DB has {} wiki pages. Triggering full reindex across all scopes.", dbCount);
        Set<Long> scopeIds = collectDistinctScopeIds();
        for (Long scopeId : scopeIds) {
            try {
                searchService.rebuildIndex(scopeId);
            } catch (Exception e) {
                LOG.error("Auto-rebuild failed for scopeId={}, continue with next scope", scopeId, e);
            }
        }
        LOG.info("Auto-rebuild finished for {} scopes", scopeIds.size());
    }

    private Set<Long> collectDistinctScopeIds() {
        Set<Long> scopeIds = new HashSet<>();
        QueryWrapper<WikiPageDO> wrapper = new QueryWrapper<>();
        wrapper.select("DISTINCT scope_id");
        List<WikiPageDO> rows = wikiPageMapper.selectList(wrapper);
        for (WikiPageDO row : rows) {
            if (row.getScopeId() != null) {
                scopeIds.add(row.getScopeId());
            }
        }
        return scopeIds;
    }
}
