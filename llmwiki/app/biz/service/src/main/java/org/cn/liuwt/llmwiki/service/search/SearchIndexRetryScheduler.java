package org.cn.liuwt.llmwiki.service.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SearchIndexRetryDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SearchIndexRetryMapper;
import org.cn.liuwt.llmwiki.integration.search.ElasticsearchProperties;
import org.cn.liuwt.llmwiki.integration.search.WikiPageDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SearchIndexRetryScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SearchIndexRetryScheduler.class);

    private static final int BATCH_SIZE = 50;

    @Autowired
    private SearchIndexRetryMapper retryMapper;

    @Autowired
    private ElasticsearchSearchServiceImpl esSearchService;

    @Autowired
    private ElasticsearchProperties esProperties;

    @Scheduled(fixedDelayString = "${llmwiki.elasticsearch.retry.interval-ms:60000}", initialDelay = 30000L)
    public void runRetry() {
        LocalDateTime now = LocalDateTime.now();
        List<SearchIndexRetryDO> batch = retryMapper.selectList(
            new LambdaQueryWrapper<SearchIndexRetryDO>()
                .eq(SearchIndexRetryDO::getStatus, "PENDING")
                .le(SearchIndexRetryDO::getNextRetryAt, now)
                .orderByAsc(SearchIndexRetryDO::getNextRetryAt)
                .last("LIMIT " + BATCH_SIZE)
        );
        if (batch.isEmpty()) {
            return;
        }
        LOG.info("SearchIndexRetryScheduler picked {} tasks", batch.size());
        for (SearchIndexRetryDO record : batch) {
            processOne(record);
        }
    }

    private void processOne(SearchIndexRetryDO record) {
        try {
            if ("INDEX".equals(record.getOperation())) {
                if (record.getPayload() == null) {
                    markDead(record, "Empty payload for INDEX op");
                    return;
                }
                WikiPageDocument doc = esSearchService.deserializeDocument(record.getPayload());
                esSearchService.indexDocumentDirect(doc);
            } else if ("REMOVE".equals(record.getOperation())) {
                esSearchService.removeDocumentDirect(record.getPageId());
            } else {
                markDead(record, "Unknown operation: " + record.getOperation());
                return;
            }
            record.setStatus("SUCCESS");
            record.setLastError(null);
            retryMapper.updateById(record);
        } catch (Exception e) {
            int next = (record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1;
            record.setRetryCount(next);
            record.setLastError(truncate(e.getMessage(), 1024));
            int max = record.getMaxAttempts() != null ? record.getMaxAttempts() : esProperties.getRetry().getMaxAttempts();
            if (next >= max) {
                record.setStatus("DEAD");
                LOG.error("Search retry exhausted. scopeId={}, pageId={}, op={}", record.getScopeId(), record.getPageId(), record.getOperation(), e);
            } else {
                long backoffMs = esProperties.getRetry().getIntervalMs() * (1L << Math.min(next, 5));
                record.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffMs / 1000));
            }
            retryMapper.updateById(record);
        }
    }

    private void markDead(SearchIndexRetryDO record, String reason) {
        record.setStatus("DEAD");
        record.setLastError(truncate(reason, 1024));
        retryMapper.updateById(record);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
