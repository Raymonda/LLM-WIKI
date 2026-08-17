package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel.ExecutionStepModel;
import org.cn.liuwt.llmwiki.domain.service.harness.LinkWritingService;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.domain.service.harness.PipelineOrchestrator;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.search.SearchService;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SaveIdempotencyTest {

    private PipelineOrchestrator orchestrator;
    private LlmClient chatClient;
    private WikiPageMapper wikiPageMapper;
    private StorageProvider storageProvider;
    private ExecutionTracker executionTracker;
    private WikiPageSourceMapper wikiPageSourceMapper;

    @BeforeEach
    void setUp() {
        orchestrator = new PipelineOrchestrator();
        chatClient = mock(LlmClient.class);
        wikiPageMapper = mock(WikiPageMapper.class);
        storageProvider = mock(StorageProvider.class);
        executionTracker = mock(ExecutionTracker.class);
        SchemaInjector schemaInjector = mock(SchemaInjector.class);

        ExecutionModel execution = new ExecutionModel();
        execution.setId(1L);
        ExecutionStepModel step = new ExecutionStepModel();
        step.setId(1L);

        when(chatClient.isAvailable()).thenReturn(true);
        when(schemaInjector.prependForQuery(any(), anyString())).thenReturn("schema-prompt");
        when(executionTracker.createExecution(anyString(), any(), any(), any())).thenReturn(execution);
        when(executionTracker.createStep(anyLong(), anyString(), anyInt(), anyString())).thenReturn(step);

        ReflectionTestUtils.setField(orchestrator, "chatClient", chatClient);
        ReflectionTestUtils.setField(orchestrator, "schemaInjector", schemaInjector);
        ReflectionTestUtils.setField(orchestrator, "executionTracker", executionTracker);
        ReflectionTestUtils.setField(orchestrator, "storageProvider", storageProvider);
        ReflectionTestUtils.setField(orchestrator, "wikiPageMapper", wikiPageMapper);
        wikiPageSourceMapper = mock(WikiPageSourceMapper.class);
        ReflectionTestUtils.setField(orchestrator, "wikiPageSourceMapper", wikiPageSourceMapper);
        ReflectionTestUtils.setField(orchestrator, "wikiPageLinkMapper", mock(WikiPageLinkMapper.class));
        ReflectionTestUtils.setField(orchestrator, "searchService", mock(SearchService.class));
        ReflectionTestUtils.setField(orchestrator, "globalSummaryService", mock(GlobalSummaryService.class));
        ReflectionTestUtils.setField(orchestrator, "lintFindingService", mock(LintFindingService.class));
        ReflectionTestUtils.setField(orchestrator, "linkWritingService", mock(LinkWritingService.class));
        ReflectionTestUtils.setField(orchestrator, "conflictDomainService", mock(ConflictDomainService.class));
        ReflectionTestUtils.setField(orchestrator, "llmConcurrencyBarrier", mock(LlmConcurrencyBarrier.class));
        ReflectionTestUtils.setField(orchestrator, "objectMapper", new ObjectMapper());
    }

    @Test
    void shouldReturnExistingPageWhenSameTitleAlreadyExists() {
        String formatted = "{\"title\":\"重复标题\",\"summary\":\"摘要\",\"category\":\"问答沉淀\","
            + "\"content\":\"# 重复标题\\n\\n内容\"}";
        when(chatClient.chat(anyString(), anyString())).thenReturn(formatted);

        WikiPageDO existing = new WikiPageDO();
        existing.setId(197L);
        existing.setTitle("重复标题");
        existing.setFilePath("pages/重复标题.md");
        when(wikiPageMapper.selectOne(any())).thenReturn(existing);

        WikiPageDO result = orchestrator.runSaveQueryResultPipeline(7L, "问题", "回答", "s1");

        assertSame(existing, result);
        verify(wikiPageMapper, never()).insert(any(WikiPageDO.class));
        verify(storageProvider, never()).write(anyString(), anyString(), any(byte[].class));
        verify(executionTracker).completeExecution(eq(1L), anyInt());
    }

    @Test
    void shouldCreateNewPageWhenTitleNotExists() {
        String formatted = "{\"title\":\"全新标题\",\"summary\":\"摘要\",\"category\":\"问答沉淀\","
            + "\"content\":\"# 全新标题\\n\\n内容\"}";
        when(chatClient.chat(anyString(), anyString())).thenReturn(formatted);
        when(wikiPageMapper.selectOne(any())).thenReturn(null);
        when(wikiPageMapper.selectList(any())).thenReturn(List.of());

        WikiPageDO result = orchestrator.runSaveQueryResultPipeline(7L, "问题", "回答", "s1");

        assertNotNull(result);
        assertEquals("全新标题", result.getTitle());
        verify(wikiPageMapper).insert(any(WikiPageDO.class));
        verify(storageProvider).write(anyString(), anyString(), any(byte[].class));
        verify(wikiPageSourceMapper, never()).insert(any(WikiPageSourceDO.class));
    }

    @Test
    void shouldReturnExistingPageWhenInsertHitsDuplicateKey() {
        String formatted = "{\"title\":\"并发标题\",\"summary\":\"摘要\",\"category\":\"问答沉淀\","
            + "\"content\":\"# 并发标题\\n\\n内容\"}";
        when(chatClient.chat(anyString(), anyString())).thenReturn(formatted);
        WikiPageDO winner = new WikiPageDO();
        winner.setId(201L);
        winner.setTitle("并发标题");
        winner.setFilePath("pages/并发标题.md");
        when(wikiPageMapper.selectOne(any())).thenReturn(null, winner);
        when(wikiPageMapper.selectList(any())).thenReturn(List.of());
        doThrow(new DuplicateKeyException("duplicate")).when(wikiPageMapper).insert(any(WikiPageDO.class));

        WikiPageDO result = orchestrator.runSaveQueryResultPipeline(7L, "问题", "回答", "s1");

        assertSame(winner, result);
        verify(wikiPageMapper).insert(any(WikiPageDO.class));
        verify(executionTracker).completeExecution(eq(1L), anyInt());
    }
}
