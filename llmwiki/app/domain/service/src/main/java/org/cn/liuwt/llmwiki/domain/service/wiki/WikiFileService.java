package org.cn.liuwt.llmwiki.domain.service.wiki;

import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import java.util.List;
import java.util.Map;

public interface WikiFileService {
    WikiPageModel readPage(String path);
    void writePage(String path, String content);
    List<WikiPageModel> listPages();
    String searchPages(String query);
    Map<String, Long> resolveWikiLinks(String content, Long scopeId);
}