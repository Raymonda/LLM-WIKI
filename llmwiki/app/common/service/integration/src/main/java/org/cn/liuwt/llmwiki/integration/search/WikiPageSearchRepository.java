package org.cn.liuwt.llmwiki.integration.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface WikiPageSearchRepository extends ElasticsearchRepository<WikiPageDocument, Long> {
}