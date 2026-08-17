package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.service.query.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMcpToolsTest {

    @Mock
    private SearchService searchService;

    @Mock
    private WikiFileServiceImpl wikiFileService;

    @Mock
    private QueryService queryService;

    @InjectMocks
    private WikiMcpTools tools;

    @BeforeEach
    void setUpRequestContext() {
        // 模拟 ApiKeyAuthFilter（Task 3）写好的认证上下文
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 7L);
        request.setAttribute("scopeId", 100L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private SearchResultInfo hit(long id, String title, String path, String lifecycleStatus) {
        SearchResultInfo r = new SearchResultInfo();
        r.setId(id);
        r.setTitle(title);
        r.setPath(path);
        r.setCategory("guide");
        r.setSummary("summary-" + id);
        r.setScore(0.87);
        r.setLifecycleStatus(lifecycleStatus);
        return r;
    }

    @Test
    void shouldReturnHitsWithStableKeysWhenWikiSearchCalled() {
        when(searchService.search(100L, "入门", null))
                .thenReturn(List.of(hit(5L, "快速开始", "docs/quickstart.md", "ACTIVE")));

        List<Map<String, Object>> hits = tools.wikiSearch("入门", null, null);

        assertEquals(1, hits.size());
        assertEquals(5L, hits.get(0).get("pageId"));
        assertEquals("快速开始", hits.get(0).get("title"));
        assertEquals("docs/quickstart.md", hits.get(0).get("path"));
        assertEquals("summary-5", hits.get(0).get("summary"));
    }

    @Test
    void shouldFilterDeprecatedPagesWhenWikiSearchCalled() {
        when(searchService.search(100L, "旧接口", null)).thenReturn(List.of(
                hit(1L, "已废弃页", "docs/old.md", "DEPRECATED"),
                hit(2L, "现行页", "docs/new.md", "ACTIVE")));

        List<Map<String, Object>> hits = tools.wikiSearch("旧接口", null, null);

        assertEquals(1, hits.size());
        assertEquals(2L, hits.get(0).get("pageId"));
    }

    @Test
    void shouldCapLimitWhenWikiSearchCalledWithLargeLimit() {
        when(searchService.search(100L, "批量", null)).thenReturn(List.of(
                hit(1L, "a", "a.md", "ACTIVE"),
                hit(2L, "b", "b.md", "ACTIVE"),
                hit(3L, "c", "c.md", "ACTIVE")));

        assertEquals(2, tools.wikiSearch("批量", null, 2).size());
        // limit 超过最大值 10 时截到 10，实际返回数仍受结果集大小限制
        assertEquals(3, tools.wikiSearch("批量", null, 100).size());
    }

    @Test
    void shouldSearchWithExplicitCategoryWhenProvided() {
        when(searchService.search(100L, "架构", "concept"))
                .thenReturn(List.of(hit(9L, "架构页", "docs/arch.md", "ACTIVE")));

        List<Map<String, Object>> hits = tools.wikiSearch("架构", "concept", null);

        assertEquals(1, hits.size());
        assertEquals(9L, hits.get(0).get("pageId"));
    }

    private WikiPageModel page(long id, String path) {
        WikiPageModel p = new WikiPageModel();
        p.setId(id);
        p.setTitle("页面-" + id);
        p.setPath(path);
        p.setCategory("guide");
        p.setSummary("摘要");
        p.setContent("# 正文");
        return p;
    }

    @Test
    void shouldReturnPageAndSourcesWhenWikiReadPageCalledWithId() {
        when(wikiFileService.readPageById(5L, 100L)).thenReturn(page(5L, "docs/quickstart.md"));
        SourceDO source = new SourceDO();
        source.setId(42L);
        source.setName("notes.md");
        source.setStatus("processed");
        when(wikiFileService.getPageSources(5L, 100L)).thenReturn(List.of(source));

        Map<String, Object> result = tools.wikiReadPage(5L, null);

        assertEquals("docs/quickstart.md", result.get("path"));
        assertEquals("# 正文", result.get("content"));
        assertEquals(1, ((List<?>) result.get("sources")).size());
    }

    @Test
    void shouldReturnPageWhenWikiReadPageCalledWithPath() {
        when(wikiFileService.readPageByFilePath("docs/quickstart.md", 100L))
                .thenReturn(page(5L, "docs/quickstart.md"));
        when(wikiFileService.getPageSources(5L, 100L)).thenReturn(List.of());

        Map<String, Object> result = tools.wikiReadPage(null, "docs/quickstart.md");

        assertEquals(5L, result.get("pageId"));
    }

    @Test
    void shouldThrowWhenWikiReadPageCalledWithoutIdAndPath() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> tools.wikiReadPage(null, "  "));
        assertTrue(ex.getMessage().contains("pageId"));
    }

    @Test
    void shouldThrowWhenWikiReadPageMisses() {
        when(wikiFileService.readPageById(99L, 100L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> tools.wikiReadPage(99L, null));
    }

    @Test
    void shouldAggregateAnswerAndStepsWhenWikiAskCalled() {
        when(queryService.queryWikiStreaming(eq(100L), eq("什么是X？"), anyString(), eq(false)))
                .thenReturn(Flux.just("__STEP__:retrieve", "X 是 ", "一种设计模式"));

        Map<String, Object> result = tools.wikiAsk("什么是X？", null);

        assertEquals("X 是 一种设计模式", result.get("answer"));
        assertEquals(List.of("retrieve"), result.get("steps"));
        assertEquals(false, result.get("timedOut"));
    }

    @Test
    void shouldPassDeepModeThroughWhenWikiAskCalledWithDeep() {
        when(queryService.queryWikiStreaming(eq(100L), anyString(), anyString(), eq(true)))
                .thenReturn(Flux.just("深度答案"));

        Map<String, Object> result = tools.wikiAsk("复杂问题", "deep");

        assertEquals("深度答案", result.get("answer"));
    }

    @Test
    void shouldReportTimeoutWhenStreamNeverCompletes() {
        WikiMcpTools.AskResult result = WikiMcpTools.collectAnswer(Flux.never(), Duration.ofMillis(50));

        assertTrue(result.timedOut());
        assertEquals("", result.answer());
        assertEquals(List.of(), result.steps());
    }

    @Test
    void shouldNotTimeoutWhenStreamCompletes() {
        WikiMcpTools.AskResult result =
                WikiMcpTools.collectAnswer(Flux.just("a", "__STEP__:s", "b"), Duration.ofSeconds(5));

        assertFalse(result.timedOut());
        assertEquals("ab", result.answer());
        assertEquals(List.of("s"), result.steps());
    }
}
