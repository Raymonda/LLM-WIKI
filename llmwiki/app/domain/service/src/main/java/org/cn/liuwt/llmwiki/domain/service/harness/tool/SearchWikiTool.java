package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SearchWikiTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    @Autowired
    private SearchService searchService;

    @Tool(description = "搜索知识库。按关键词全文搜索 Wiki 知识页面（含摘要页、实体页、参考页），可选按分类筛选。返回匹配页面的标题、路径、摘要和相关度片段。使用 Elasticsearch 中文分词检索")
    public List<PageResult> searchWiki(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "搜索关键词") String query,
        @ToolParam(description = "分类筛选（可选，如 '架构设计'）") String category
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);
        List<SearchResultInfo> searchResults = searchService.search(scopeIdLong, query, category);
        List<SearchResultInfo> filteredResults = searchResults.stream()
            .filter(r -> !"DEPRECATED".equals(r.getLifecycleStatus()))
            .collect(Collectors.toList());
        log.info("tool=searchWiki scopeId={} query={} category={} rawResults={} filteredResults={}",
            scopeId, query, category, searchResults.size(), filteredResults.size());

        List<PageResult> results = new ArrayList<>();
        for (SearchResultInfo info : filteredResults) {
            results.add(PageResult.from(info));
        }
        return results;
    }

    public static class PageResult {
        public long id;
        public String title;
        public String path;
        public String category;
        public String summary;
        public String snippet;

        static PageResult from(SearchResultInfo info) {
            PageResult result = new PageResult();
            result.id = info.getId() != null ? info.getId() : 0;
            result.title = info.getTitle();
            result.path = toReadPath(info.getPath());
            result.category = info.getCategory();
            result.summary = info.getSummary();
            result.snippet = buildSnippet(info);
            return result;
        }

        private static String buildSnippet(SearchResultInfo info) {
            StringBuilder sb = new StringBuilder();
            if (info.getHighlightedContent() != null && !info.getHighlightedContent().isEmpty()) {
                int limit = Math.min(3, info.getHighlightedContent().size());
                for (int i = 0; i < limit; i++) {
                    if (i > 0) sb.append(" <br/> ");
                    sb.append(info.getHighlightedContent().get(i));
                }
            } else if (info.getHighlightedSummary() != null && !info.getHighlightedSummary().isEmpty()) {
                sb.append(info.getHighlightedSummary().get(0));
            }
            String result = sb.toString();
            return result.length() > 1500 ? result.substring(0, 1500) + "..." : result;
        }

        private static String toReadPath(String dbPath) {
            if (dbPath != null && dbPath.startsWith("pages/")) {
                return "wiki/" + dbPath;
            }
            return dbPath;
        }
    }
}