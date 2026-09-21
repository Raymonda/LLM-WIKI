package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.PageWriteLockRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.system.NotificationService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelatedPageBatchTest {

    @Mock
    private LlmClient chatClient;

    @Mock
    private SchemaInjector schemaInjector;

    @Mock
    private LlmConcurrencyBarrier llmBarrier;

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private ExecutionEventLogService executionEventLog;

    @Mock
    private NotificationService notificationService;

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Mock
    private LintFindingService lintFindingService;

    @Mock
    private SchemaManager schemaManager;

    private static final String EXISTING = """
        ## 基本信息

        - 总部位于上海（来源：2023 年报）
        - 注册资本 10 亿元（来源：2023 年报）""";

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
        inject(agent, "chatClient", chatClient);
        inject(agent, "schemaInjector", schemaInjector);
        inject(agent, "llmBarrier", llmBarrier);
        inject(agent, "storageProvider", storageProvider);
        inject(agent, "executionEventLog", executionEventLog);
        inject(agent, "notificationService", notificationService);
        inject(agent, "wikiPageMapper", wikiPageMapper);
        inject(agent, "wikiPageSourceMapper", wikiPageSourceMapper);
        inject(agent, "lintFindingService", lintFindingService);
        inject(agent, "schemaManager", schemaManager);
        inject(agent, "pageWriteLockRegistry", new PageWriteLockRegistry());
        return agent;
    }

    private static WikiPageDO entityPage(long id, String path, String title) {
        WikiPageDO p = new WikiPageDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setFilePath(path);
        p.setTitle(title);
        p.setPageType("entity");
        p.setLifecycleStatus("ACTIVE");
        p.setSourceCount(1);
        return p;
    }

    private static WikiPageDO invokeApply(WriterAgent agent, WikiPageDO existing, IngestContext context,
                                          WriterAgent.PrefetchedClaims prefetched) {
        try {
            java.lang.reflect.Method m = WriterAgent.class.getDeclaredMethod("applyIncrementalEntityUpdate",
                Long.class, Long.class, String.class, WikiPageDO.class, String.class, String.class,
                String.class, String.class, List.class, Map.class, IngestContext.class,
                WriterAgent.PrefetchedClaims.class);
            m.setAccessible(true);
            return (WikiPageDO) m.invoke(agent, 1L, 2L, "1", existing, existing.getFilePath(),
                "某公司 2024 年公告：注册资本变更为 12 亿元。", "分析结果",
                "{\"title\":\"2024 公告\"}", List.of(), new HashMap<String, String>(), context, prefetched);
        } catch (Exception e) {
            throw new IllegalStateException("applyIncrementalEntityUpdate failed", e);
        }
    }

    @Test
    void shouldBatchRelatedPageLlmCalls() {
        WikiPageDO p1 = entityPage(11L, "pages/a.md", "甲公司");
        WikiPageDO p2 = entityPage(12L, "pages/b.md", "乙公司");
        WikiPageDO p3 = entityPage(13L, "pages/c.md", "丙公司");
        when(wikiPageMapper.selectOne(any())).thenReturn(p1, p2, p3);
        when(storageProvider.read(eq("1"), anyString()))
            .thenReturn(EXISTING.getBytes(StandardCharsets.UTF_8));
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString(), anyString())).thenReturn(
            "[{\"pageIndex\":0,\"candidates\":[{\"section\":\"基本信息\",\"claim\":\"甲 claim\",\"source\":\"2024 公告\",\"relation\":\"new\"}]},"
                + "{\"pageIndex\":1,\"candidates\":[]},"
                + "{\"pageIndex\":2,\"candidates\":[{\"section\":\"基本信息\",\"claim\":\"丙 claim\",\"source\":\"2024 公告\",\"relation\":\"new\"}]}]");
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);

        List<WriterAgent.RelatedPrefetchRequest> requests = List.of(
            new WriterAgent.RelatedPrefetchRequest("pages/a.md", "更新", "来源内容片段"),
            new WriterAgent.RelatedPrefetchRequest("pages/b.md", "更新", "来源内容片段"),
            new WriterAgent.RelatedPrefetchRequest("pages/c.md", "更新", "来源内容片段"));
        Map<String, WriterAgent.PrefetchedClaims> result = agent.batchPrefetchRelatedClaims(
            1L, "1", requests, "分析结果", "{\"title\":\"2024 公告\"}", context);

        ArgumentCaptor<String> userJson = ArgumentCaptor.forClass(String.class);
        verify(chatClient, times(1)).chat(anyString(), userJson.capture());
        assertTrue(userJson.getValue().contains("甲公司"), "batch request must aggregate page 甲公司");
        assertTrue(userJson.getValue().contains("乙公司"), "batch request must aggregate page 乙公司");
        assertTrue(userJson.getValue().contains("丙公司"), "batch request must aggregate page 丙公司");
        verify(llmBarrier, times(1)).tryAcquire(eq(LlmConcurrencyBarrier.Bucket.ENTITY), anyLong());

        assertEquals(3, result.size());
        assertTrue(result.get("pages/a.md").rawJson().contains("甲 claim"));
        assertEquals(EXISTING, result.get("pages/a.md").contentSnapshot());
        assertEquals("[]", result.get("pages/b.md").rawJson());
        assertTrue(result.get("pages/c.md").rawJson().contains("丙 claim"));
    }

    @Test
    void shouldFallbackToPerPageWhenBatchParseFails() {
        WikiPageDO p1 = entityPage(11L, "pages/a.md", "甲公司");
        WikiPageDO p2 = entityPage(12L, "pages/b.md", "乙公司");
        when(wikiPageMapper.selectOne(any())).thenReturn(p1, p2);
        when(storageProvider.read(eq("1"), anyString()))
            .thenReturn(EXISTING.getBytes(StandardCharsets.UTF_8));
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString(), anyString())).thenReturn("这不是 JSON 数组");
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);

        List<WriterAgent.RelatedPrefetchRequest> requests = List.of(
            new WriterAgent.RelatedPrefetchRequest("pages/a.md", "更新", "来源内容片段"),
            new WriterAgent.RelatedPrefetchRequest("pages/b.md", "更新", "来源内容片段"));
        Map<String, WriterAgent.PrefetchedClaims> result = agent.batchPrefetchRelatedClaims(
            1L, "1", requests, "分析结果", "{\"title\":\"2024 公告\"}", context);

        assertTrue(result.isEmpty(), "unparseable batch response must yield an empty prefetch map");
        verify(chatClient, times(1)).chat(anyString(), anyString());

        when(wikiPageMapper.selectById(11L)).thenReturn(p1);
        when(chatClient.chat(anyString())).thenReturn(
            "[{\"section\":\"基本信息\",\"claim\":\"注册资本变更为 12 亿元\",\"source\":\"2024 公告\",\"relation\":\"new\"}]");

        WikiPageDO updated = invokeApply(agent, p1, context, null);

        assertSame(p1, updated, "per-page fallback must apply the incremental update");
        verify(chatClient, times(1)).chat(anyString());
    }

    @Test
    void shouldConsumePrefetchedClaimsWithoutLlmCall() {
        WikiPageDO existing = entityPage(11L, "pages/a.md", "甲公司");
        when(wikiPageMapper.selectById(11L)).thenReturn(existing);
        when(storageProvider.read(eq("1"), eq("wiki/pages/a.md")))
            .thenReturn(EXISTING.getBytes(StandardCharsets.UTF_8));
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        WriterAgent.PrefetchedClaims prefetched = new WriterAgent.PrefetchedClaims(
            "[{\"section\":\"基本信息\",\"claim\":\"注册资本变更为 12 亿元\",\"source\":\"2024 公告\",\"relation\":\"new\"}]",
            EXISTING);

        WikiPageDO result = invokeApply(agent, existing, context, prefetched);

        assertSame(existing, result);
        verify(chatClient, never()).chat(anyString());
        verify(chatClient, never()).chat(anyString(), anyString());
        verify(llmBarrier, never()).tryAcquire(any(), anyLong());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageProvider).write(eq("1"), eq("wiki/pages/a.md"), bytes.capture());
        String written = new String(bytes.getValue(), StandardCharsets.UTF_8);
        assertTrue(written.contains("- 注册资本 10 亿元（来源：2023 年报）"), "existing entry must survive untouched");
        assertTrue(written.contains("- 注册资本变更为 12 亿元（来源：2024 公告）"), "prefetched claim must be appended");
    }

    @Test
    void shouldIgnorePrefetchWhenContentSnapshotMismatch() {
        WikiPageDO existing = entityPage(11L, "pages/a.md", "甲公司");
        when(wikiPageMapper.selectById(11L)).thenReturn(existing);
        when(storageProvider.read(eq("1"), eq("wiki/pages/a.md")))
            .thenReturn("## 基本信息\n\n- 已被并发改写".getBytes(StandardCharsets.UTF_8));
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn(
            "[{\"section\":\"基本信息\",\"claim\":\"注册资本变更为 12 亿元\",\"source\":\"2024 公告\",\"relation\":\"new\"}]");
        WriterAgent agent = agentWithMocks();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        WriterAgent.PrefetchedClaims stale = new WriterAgent.PrefetchedClaims(
            "[{\"section\":\"基本信息\",\"claim\":\"过期 claim\",\"source\":\"2024 公告\",\"relation\":\"new\"}]",
            EXISTING);

        WikiPageDO result = invokeApply(agent, existing, context, stale);

        assertSame(existing, result);
        verify(chatClient, times(1)).chat(anyString());
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageProvider).write(eq("1"), eq("wiki/pages/a.md"), bytes.capture());
        String written = new String(bytes.getValue(), StandardCharsets.UTF_8);
        assertFalse(written.contains("过期 claim"), "stale prefetched claims must be discarded");
        assertTrue(written.contains("注册资本变更为 12 亿元"), "fresh per-page claims must be applied");
    }
}
