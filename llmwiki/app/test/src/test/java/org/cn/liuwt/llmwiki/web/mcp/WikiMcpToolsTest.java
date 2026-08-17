package org.cn.liuwt.llmwiki.web.mcp;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.WikiPageModel;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.domain.service.wiki.WikiFileServiceImpl;
import org.cn.liuwt.llmwiki.facade.model.DuplicateInfo;
import org.cn.liuwt.llmwiki.facade.model.SearchResultInfo;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.ingest.IngestOrchestrationService;
import org.cn.liuwt.llmwiki.service.ingest.IngestService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiMcpToolsTest {

    @Mock
    private SearchService searchService;

    @Mock
    private WikiFileServiceImpl wikiFileService;

    @Mock
    private QueryService queryService;

    @Mock
    private SourceService sourceService;

    @Mock
    private IngestOrchestrationService ingestOrchestrationService;

    @Mock
    private IngestService ingestService;

    @Mock
    private ExecutionNodeRegistry executionNodeRegistry;

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

    private ExecutionModel execution(long id, Long scopeId, String status) {
        ExecutionModel e = new ExecutionModel();
        e.setId(id);
        e.setScopeId(scopeId);
        e.setStatus(status);
        return e;
    }

    @Test
    void shouldStartIngestAndReturnIdsWhenWikiIngestTextCalled() {
        org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel source = new org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel();
        source.setId(42L);
        when(sourceService.uploadTextSource("笔记", "# 内容", 100L, 7L)).thenReturn(source);
        when(ingestOrchestrationService.startIngest(100L, 42L, "补充指引"))
                .thenReturn(execution(123L, 100L, "pending"));

        Map<String, Object> result = tools.wikiIngestText("笔记", "# 内容", "补充指引");

        assertEquals(123L, result.get("executionId"));
        assertEquals(42L, result.get("sourceId"));
        assertEquals("pending", result.get("status"));
        assertNull(result.get("duplicateWarning"));
    }

    @Test
    void shouldWarnDuplicateWhenUploadMatchesExistingSource() {
        org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel source = new org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel();
        source.setId(42L);
        DuplicateInfo duplicateInfo = new DuplicateInfo();
        duplicateInfo.setMessage("此内容与已有来源「旧文档」相同");
        source.setDuplicateInfo(duplicateInfo);
        when(sourceService.uploadTextSource("笔记", "# 内容", 100L, 7L)).thenReturn(source);
        when(ingestOrchestrationService.startIngest(100L, 42L, null))
                .thenReturn(execution(124L, 100L, "pending"));

        Map<String, Object> result = tools.wikiIngestText("笔记", "# 内容", null);

        assertEquals("此内容与已有来源「旧文档」相同", result.get("duplicateWarning"));
    }

    @Test
    void shouldReportStatusAndCurrentStepWhenWikiIngestStatusCalled() {
        ExecutionModel e = execution(123L, 100L, "running");
        ExecutionModel.ExecutionStepModel done = new ExecutionModel.ExecutionStepModel();
        done.setStepName("UPLOAD");
        done.setStatus("completed");
        ExecutionModel.ExecutionStepModel active = new ExecutionModel.ExecutionStepModel();
        active.setStepName("ANALYZE");
        active.setStatus("running");
        e.setSteps(List.of(done, active));
        when(ingestService.getProgress(123L)).thenReturn(e);

        Map<String, Object> result = tools.wikiIngestStatus(123L);

        assertEquals("running", result.get("status"));
        assertEquals("ANALYZE", result.get("currentStep"));
        assertEquals(List.of("UPLOAD:completed", "ANALYZE:running"), result.get("steps"));
    }

    @Test
    void shouldThrowWhenIngestStatusAskedForForeignScope() {
        when(ingestService.getProgress(123L)).thenReturn(execution(123L, 999L, "running"));

        assertThrows(IllegalArgumentException.class, () -> tools.wikiIngestStatus(123L));
    }

    @Test
    void shouldCancelRunningExecutionWhenWikiCancelIngestCalled() {
        when(ingestService.getProgress(123L)).thenReturn(execution(123L, 100L, "running"));

        Map<String, Object> result = tools.wikiCancelIngest(123L);

        assertEquals("cancelled", result.get("status"));
        verify(ingestService).cancelExecution(123L, 100L);
        verify(executionNodeRegistry).cancelAndRemoveFuture(123L);
    }

    @Test
    void shouldThrowWhenCancelRequestedForFinishedExecution() {
        when(ingestService.getProgress(123L)).thenReturn(execution(123L, 100L, "completed"));

        assertThrows(IllegalStateException.class, () -> tools.wikiCancelIngest(123L));
        verify(ingestService, never()).cancelExecution(123L, 100L);
    }
}
