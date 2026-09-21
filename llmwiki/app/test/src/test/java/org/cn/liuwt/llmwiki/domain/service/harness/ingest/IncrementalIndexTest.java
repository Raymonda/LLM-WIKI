package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncrementalIndexTest {

    @Mock
    private SearchService searchService;

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private StorageProvider storageProvider;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), WikiPageDO.class);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = WriterAgent.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private WriterAgent agentWithMocks() {
        WriterAgent agent = new WriterAgent();
        inject(agent, "searchService", searchService);
        inject(agent, "wikiPageMapper", wikiPageMapper);
        inject(agent, "storageProvider", storageProvider);
        inject(agent, "indexBatchSize", 5);
        return agent;
    }

    private static WikiPageDO page(long id, String path) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle("Page " + id);
        p.setPageType("entity");
        p.setLifecycleStatus("ACTIVE");
        return p;
    }

    @Test
    void shouldFlushIndexInBatchesOfConfiguredSize() {
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        List<WikiPageDO> pages = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            WikiPageDO p = page(i, "pages/p" + i + ".md");
            pages.add(p);
            context.getEntityPages().put(p.getFilePath(), p);
            agent.queueProgressiveIndex(1L, p, context);
        }

        verify(searchService, times(2)).bulkIndexPages(eq(1L), anyList());

        agent.reSyncToIndex(context);

        ArgumentCaptor<List<WikiPageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchService, times(3)).bulkIndexPages(eq(1L), captor.capture());
        List<List<WikiPageDO>> calls = captor.getAllValues();
        assertEquals(5, calls.get(0).size(), "first flush must be a full batch of 5");
        assertEquals(5, calls.get(1).size(), "second flush must be a full batch of 5");
        assertEquals(2, calls.get(2).size(), "final flush must carry the remaining 2 pages");
        assertEquals(12, context.getIndexedPageIds().size());
        assertTrue(context.isBulkIndexed());
    }

    @Test
    void shouldIndexSummaryPageFirst() {
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        agent.queueProgressiveIndex(1L, page(1, "pages/e1.md"), context);
        agent.queueProgressiveIndex(1L, page(2, "pages/e2.md"), context);
        verify(searchService, never()).bulkIndexPages(anyLong(), anyList());

        WikiPageDO summary = page(99L, "pages/summary.md");
        context.setSummaryPage(summary);
        agent.indexSummaryPageImmediately(1L, summary, context);

        ArgumentCaptor<List<WikiPageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchService, times(1)).bulkIndexPages(eq(1L), captor.capture());
        List<WikiPageDO> firstBatch = captor.getValue();
        assertEquals(3, firstBatch.size(), "summary flush must drain all pending pages");
        assertTrue(firstBatch.stream().anyMatch(p -> p.getId() == 99L),
            "summary page must be in the first flush batch");
    }

    @Test
    void shouldContinueWhenSingleBatchFails() {
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        doNothing()
            .doThrow(new RuntimeException("ES bulk failure"))
            .doNothing()
            .when(searchService).bulkIndexPages(anyLong(), anyList());

        for (long i = 1; i <= 15; i++) {
            WikiPageDO p = page(i, "pages/p" + i + ".md");
            context.getEntityPages().put(p.getFilePath(), p);
            agent.queueProgressiveIndex(1L, p, context);
        }

        verify(searchService, times(3)).bulkIndexPages(eq(1L), anyList());
        assertEquals(10, context.getIndexedPageIds().size(),
            "pages of the failed batch must not be marked as indexed");

        agent.reSyncToIndex(context);

        ArgumentCaptor<List<WikiPageDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(searchService, times(4)).bulkIndexPages(eq(1L), captor.capture());
        assertEquals(5, captor.getAllValues().get(3).size(),
            "reconciliation must retry exactly the 5 pages of the failed batch");
        assertTrue(context.isBulkIndexed());
    }

    @Test
    void shouldReconcileAtEndAndRetryMissingOnce() {
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        for (long i = 1; i <= 6; i++) {
            context.getEntityPages().put("pages/p" + i + ".md", page(i, "pages/p" + i + ".md"));
        }
        for (long i = 1; i <= 5; i++) {
            agent.queueProgressiveIndex(1L, context.getEntityPages().get("pages/p" + i + ".md"), context);
        }
        verify(searchService, times(1)).bulkIndexPages(eq(1L), anyList());

        doThrow(new RuntimeException("ES down")).when(searchService).bulkIndexPages(anyLong(), anyList());
        when(storageProvider.exists(eq("1"), eq("wiki/pages/p6.md"))).thenReturn(true);
        when(storageProvider.read(eq("1"), eq("wiki/pages/p6.md")))
            .thenReturn("# P6".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> agent.reSyncToIndex(context),
            "indexing failure must not change the execution outcome");

        verify(searchService, times(2)).bulkIndexPages(eq(1L), anyList());
        verify(searchService, times(1)).indexPage(eq(1L), eq(6L), any(), eq("pages/p6.md"),
            any(), any(), any(), any(), any(), any());
        assertTrue(context.isBulkIndexed());
    }
}
