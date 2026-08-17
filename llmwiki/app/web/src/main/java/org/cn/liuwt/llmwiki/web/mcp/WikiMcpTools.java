package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具面（streamable-http /mcp，SYNC 模式）。
 * scope 上下文来自认证 filter 写入的 request attributes：
 * JwtAuthenticationFilter（JWT）或 ApiKeyAuthFilter（API Key）。
 * 红线：scope 一律从认证上下文解析，绝不作为工具参数暴露。
 */
@Component
public class WikiMcpTools {

    @Autowired
    private SearchService searchService;

    @Tool(description = "在当前 scope 的知识库中检索 wiki 页面。返回 pageId、标题、路径、摘要与相关度评分；先用本工具定位，再用 wiki_read_page 精读。")
    public List<Map<String, Object>> wikiSearch(
            @ToolParam(description = "检索关键词或问题") String query,
            @ToolParam(required = false, description = "分类过滤（如 guide/concept），不填则全部分类") String category,
            @ToolParam(required = false, description = "返回条数上限，默认 5，最大 10") Integer limit) {
        Long scopeId = currentScopeId();
        int max = limit == null ? 5 : Math.min(Math.max(limit, 1), 10);
        return searchService.search(scopeId, query, category).stream()
                .filter(r -> !"DEPRECATED".equals(r.getLifecycleStatus()))
                .limit(max)
                .map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pageId", r.getId());
                    item.put("title", r.getTitle());
                    item.put("path", r.getPath());
                    item.put("category", r.getCategory());
                    item.put("summary", r.getSummary());
                    item.put("score", r.getScore());
                    if (r.getSourceScopeName() != null) {
                        item.put("sourceScopeName", r.getSourceScopeName());
                    }
                    return item;
                })
                .toList();
    }

    Long currentScopeId() {
        return (Long) currentAttributes().getRequest().getAttribute("scopeId");
    }

    Long currentUserId() {
        return (Long) currentAttributes().getRequest().getAttribute("userId");
    }

    private static ServletRequestAttributes currentAttributes() {
        return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    }
}
