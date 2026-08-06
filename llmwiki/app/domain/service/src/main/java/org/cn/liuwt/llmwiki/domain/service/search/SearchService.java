package org.cn.liuwt.llmwiki.domain.service.search;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.facade.model.WikiPageInfo;

import java.util.List;

public interface SearchService {

    List<SearchResultInfo> search(Long scopeId, String query, String category);

    default List<SearchResultInfo> searchMultiScope(List<Long> scopeIds, String query, String category) {
        List<SearchResultInfo> all = new java.util.ArrayList<>();
        for (Long scopeId : scopeIds) {
            all.addAll(search(scopeId, query, category));
        }
        all.sort((a, b) -> Double.compare(
                b.getScore() != null ? b.getScore() : 0.0,
                a.getScore() != null ? a.getScore() : 0.0));
        return all;
    }

    List<WikiPageInfo> suggest(Long scopeId, String prefix);

    void indexPage(Long scopeId, Long pageId, String title, String filePath, String category,
                   String summary, String content, String healthStatus, String visibility,
                   String lifecycleStatus);

    void bulkIndexPages(Long scopeId, List<WikiPageDO> pages);

    void removePage(Long scopeId, Long pageId);

    void rebuildIndex(Long scopeId);
}