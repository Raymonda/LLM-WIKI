package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.PageWriteLockRegistry;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaManager;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.WriterAgent;
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
class WriterAgentIncrementalUpdateTest {

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

    private static WikiPageDO entityPage() {
        WikiPageDO p = new WikiPageDO();
        p.setId(77L);
        p.setScopeId(1L);
        p.setFilePath("pages/test-entity.md");
        p.setTitle("测试实体");
        p.setPageType("entity");
        p.setLifecycleStatus("ACTIVE");
        p.setSourceCount(1);
        return p;
    }

    private static WikiPageDO invokeApply(WriterAgent agent, WikiPageDO existing, IngestContext context) {
        try {
            java.lang.reflect.Method m = WriterAgent.class.getDeclaredMethod("applyIncrementalEntityUpdate",
                Long.class, Long.class, String.class, WikiPageDO.class, String.class, String.class,
                String.class, String.class, List.class, Map.class, IngestContext.class);
            m.setAccessible(true);
            return (WikiPageDO) m.invoke(agent, 1L, 2L, "1", existing, existing.getFilePath(),
                "某公司 2024 年公告：注册资本变更为 12 亿元。", "分析结果",
                "{\"title\":\"2024 公告\"}", List.of(), new HashMap<String, String>(), context);
        } catch (Exception e) {
            throw new IllegalStateException("applyIncrementalEntityUpdate failed", e);
        }
    }

    private void stubLockedPageRead(WikiPageDO existing) {
        when(wikiPageMapper.selectById(77L)).thenReturn(existing);
        when(storageProvider.read(eq("1"), eq("wiki/pages/test-entity.md")))
            .thenReturn(EXISTING.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldApplyLineLevelUpdateWhenCandidatesValid() {
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn(
            "[{\"section\":\"基本信息\",\"claim\":\"注册资本变更为 12 亿元\",\"source\":\"2024 公告\","
                + "\"relation\":\"conflict_with:1\"}]");
        WriterAgent agent = agentWithMocks();
        WikiPageDO existing = entityPage();
        stubLockedPageRead(existing);

        WikiPageDO result = invokeApply(agent, existing, new IngestContext(1L, 2L, 100L, null));

        assertSame(existing, result);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageProvider).write(eq("1"), eq("wiki/pages/test-entity.md"), bytes.capture());
        String written = new String(bytes.getValue(), StandardCharsets.UTF_8);
        assertTrue(written.contains("- 注册资本 10 亿元（来源：2023 年报）"), "existing entry must survive untouched");
        assertTrue(written.contains("- 注册资本变更为 12 亿元（来源：2024 公告）"), "conflicting claim must be appended as a new entry");
        assertEquals("conflict-warning", existing.getHealthStatus());
        assertEquals(2, existing.getSourceCount());
        verify(wikiPageMapper).update(isNull(), any());
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldDegradeLoudlyWhenCandidateJsonFatal() {
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn("这不是 JSON");
        WriterAgent agent = agentWithMocks();
        WikiPageDO existing = entityPage();
        stubLockedPageRead(existing);

        WikiPageDO result = invokeApply(agent, existing, new IngestContext(1L, 2L, 100L, null));

        assertNull(result, "degraded update must return null and leave the page unchanged");
        verify(storageProvider, never()).write(anyString(), anyString(), any());
        verify(executionEventLog).append(eq("100"), eq("error"), anyMap());
        verify(notificationService).createNotification(eq(1L), eq("ingest_entity_update_skipped"),
            eq("实体页增量更新降级"), contains("未执行按条增量更新"), eq(1L), eq(77L), eq(100L));
    }

    @Test
    void shouldNotNotifyWhenNotificationsSuppressed() {
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn("这不是 JSON");
        WriterAgent agent = agentWithMocks();
        WikiPageDO existing = entityPage();
        stubLockedPageRead(existing);
        IngestContext context = new IngestContext(1L, 2L, 100L, null);
        context.setSuppressNotifications(true);

        WikiPageDO result = invokeApply(agent, existing, context);

        assertNull(result);
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldRaiseSamePageConflictCardWhenEntriesConflict() {
        when(schemaInjector.prependForWriter(anyLong(), anyString())).thenReturn("PROMPT");
        when(llmBarrier.tryAcquire(any(), anyLong())).thenReturn(true);
        when(chatClient.chat(anyString())).thenReturn(
            "[{\"section\":\"基本信息\",\"claim\":\"注册资本变更为 12 亿元\",\"source\":\"2024 公告\","
                + "\"relation\":\"conflict_with:1\"}]");
        when(lintFindingService.upsertConflictFinding(any(), any(), any())).thenReturn(7L);
        WriterAgent agent = agentWithMocks();
        WikiPageDO existing = entityPage();
        stubLockedPageRead(existing);

        invokeApply(agent, existing, new IngestContext(1L, 2L, 100L, null));

        ArgumentCaptor<LintFindingService.ConflictCard> captor =
            ArgumentCaptor.forClass(LintFindingService.ConflictCard.class);
        verify(lintFindingService).upsertConflictFinding(eq(1L), eq(100L), captor.capture());
        LintFindingService.ConflictCard card = captor.getValue();
        assertEquals("fact_conflict", card.conflictType());
        assertEquals("ingest_fact_conflict", card.source());
        assertEquals(77L, card.fromPageId());
        assertEquals(77L, card.relatedPageId());
        assertEquals("pages/test-entity.md", card.fromPagePath());
        assertEquals("注册资本 10 亿元", card.claimA());
        assertEquals("注册资本变更为 12 亿元", card.claimB());
        assertTrue(card.detail().contains("注册资本 10 亿元"), "detail must carry the existing claim");
        assertTrue(card.detail().contains("2024 公告"), "detail must carry the new claim source");
    }
}
