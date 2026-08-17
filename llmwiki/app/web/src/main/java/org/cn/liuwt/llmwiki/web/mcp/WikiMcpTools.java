package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Autowired
    private WikiFileServiceImpl wikiFileService;

    @Autowired
    private QueryService queryService;

    private static final Duration ASK_TIMEOUT_QUICK = Duration.ofSeconds(55);
    private static final Duration ASK_TIMEOUT_DEEP = Duration.ofSeconds(280);
    private static final String STEP_PREFIX = "__STEP__:";

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

    @Tool(description = "按 pageId 或文件路径读取一个 wiki 页面的完整 Markdown 内容，并列出支撑它的来源。pageId 与 filePath 二选一。")
    public Map<String, Object> wikiReadPage(
            @ToolParam(required = false, description = "wiki_search 返回的 pageId") Long pageId,
            @ToolParam(required = false, description = "wiki 页面文件路径（如 docs/quickstart.md），与 pageId 二选一") String filePath) {
        Long scopeId = currentScopeId();
        WikiPageModel page;
        if (pageId != null) {
            page = wikiFileService.readPageById(pageId, scopeId);
        } else if (filePath != null && !filePath.isBlank()) {
            page = wikiFileService.readPageByFilePath(filePath.trim(), scopeId);
        } else {
            throw new IllegalArgumentException("pageId 与 filePath 必须提供其一");
        }
        if (page == null) {
            throw new IllegalArgumentException("页面不存在或不在当前 scope 内: "
                    + (pageId != null ? "pageId=" + pageId : "filePath=" + filePath));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageId", page.getId());
        result.put("title", page.getTitle());
        result.put("path", page.getPath());
        result.put("category", page.getCategory());
        result.put("summary", page.getSummary());
        result.put("content", page.getContent());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (SourceDO s : wikiFileService.getPageSources(page.getId(), scopeId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sourceId", s.getId());
            item.put("name", s.getName());
            if (s.getStatus() != null) {
                item.put("status", s.getStatus());
            }
            sources.add(item);
        }
        result.put("sources", sources);
        return result;
    }

    @Tool(description = "向知识库提问，获得基于 wiki 内容的生成答案。quick（默认）较快；deep 多步检索更慢但更全面。超时会返回已生成的部分答案与 timedOut=true。")
    public Map<String, Object> wikiAsk(
            @ToolParam(description = "问题") String question,
            @ToolParam(required = false, description = "quick（默认）或 deep") String mode) {
        Long scopeId = currentScopeId();
        boolean deep = "deep".equalsIgnoreCase(mode);
        String sessionId = "mcp-" + UUID.randomUUID();
        Flux<String> stream = queryService.queryWikiStreaming(scopeId, question, sessionId, deep);
        AskResult collected = collectAnswer(stream, deep ? ASK_TIMEOUT_DEEP : ASK_TIMEOUT_QUICK);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", collected.answer());
        result.put("steps", collected.steps());
        result.put("timedOut", collected.timedOut());
        return result;
    }

    /** wikiAsk 的三态结果：答案 / 步骤名列表 / 是否因超时提前返回。 */
    record AskResult(String answer, List<String> steps, boolean timedOut) {}

    /**
     * 聚合 queryWikiStreaming 的 Flux：__STEP__: 前缀的 chunk 归入 steps，其余拼接为答案。
     * blockLast 超时抛 IllegalStateException，捕获后按"部分答案 + timedOut"返回（防御式三态独立报告）。
     */
    static AskResult collectAnswer(Flux<String> stream, Duration timeout) {
        List<String> steps = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        boolean[] timedOut = {false};
        try {
            stream.doOnNext(chunk -> {
                if (chunk.startsWith(STEP_PREFIX)) {
                    steps.add(chunk.substring(STEP_PREFIX.length()));
                } else {
                    answer.append(chunk);
                }
            }).blockLast(timeout);
        } catch (IllegalStateException e) {
            timedOut[0] = true;
        }
        return new AskResult(answer.toString(), List.copyOf(steps), timedOut[0]);
    }

    private static ServletRequestAttributes currentAttributes() {
        return (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    }
}
