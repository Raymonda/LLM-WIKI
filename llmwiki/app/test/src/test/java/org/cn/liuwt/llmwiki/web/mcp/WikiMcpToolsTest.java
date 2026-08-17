package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMcpToolsTest {

    @Mock
    private SearchService searchService;

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
}
