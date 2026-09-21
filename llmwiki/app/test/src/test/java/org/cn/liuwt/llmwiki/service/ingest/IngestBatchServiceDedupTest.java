package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.IngestBatchDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ScopeDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ScopeMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateInfo;
import org.cn.liuwt.llmwiki.service.harness.mq.ExecutionNodeRegistry;
import org.cn.liuwt.llmwiki.service.harness.mq.IngestDispatcher;
import org.cn.liuwt.llmwiki.service.harness.mq.MqHealthService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestBatchServiceDedupTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private ExecutionStepMapper executionStepMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private ScopeMapper scopeMapper;
    @Mock private SourceMapper sourceMapper;
    @Mock private ExecutionTracker executionTracker;
    @Mock private IngestService ingestService;
    @Mock private IngestDispatcher ingestDispatcher;
    @Mock private ExecutionNodeRegistry registry;
    @Mock private MqHealthService mqHealthService;
    @Mock private SourceService sourceService;

    @InjectMocks
    private IngestBatchService ingestBatchService;

    private static final Long SCOPE_ID = 100L;
    private static final Long USER_ID = 7L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SourceDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, IngestBatchDO.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestBatchService, "maxBatchSize", 200);
    }

    private SourceDO activeSource(Long id, String name, String contentHash) {
        SourceDO source = new SourceDO();
        source.setId(id);
        source.setScopeId(SCOPE_ID);
        source.setName(name);
        source.setStatus("processed");
        source.setLifecycleStatus("ACTIVE");
        source.setContentHash(contentHash);
        return source;
    }

    private void stubCreateBatchCommon(Map<Long, SourceDO> sourceMap) {
        when(sourceMapper.selectBatchIds(anyList())).thenReturn(List.copyOf(sourceMap.values()));
        when(executionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(batchMapper.insert(any(IngestBatchDO.class))).thenAnswer(inv -> {
            IngestBatchDO batch = inv.getArgument(0);
            batch.setId(999L);
            return 1;
        });
        lenient().when(executionTracker.createExecution(eq("ingest"), eq(SCOPE_ID), anyLong(), isNull()))
            .thenAnswer(inv -> {
                var model = new org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel();
                model.setId(System.nanoTime());
                return model;
            });
    }

    @Test
    void shouldSkipDuplicateWhenContentHashMatchesProcessed() {
        SourceDO newSource = activeSource(10L, "new-doc.pdf", "hash_abc");
        SourceDO existingProcessed = activeSource(5L, "old-doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, newSource));

        SourceModel dupModel = new SourceModel();
        dupModel.setId(5L);
        dupModel.setName("old-doc.pdf");
        when(sourceService.findDuplicateSource(SCOPE_ID, "hash_abc")).thenReturn(dupModel);

        IngestBatchCreateInfo result = ingestBatchService.createBatch(
            SCOPE_ID, USER_ID, List.of(10L), null, null, null);

        assertEquals(0, result.acceptedCount());
        assertEquals(1, result.skippedDuplicateCount());
        assertEquals(1, result.skipped().size());
        assertEquals(10L, result.skipped().get(0).sourceId());
        assertEquals(5L, result.skipped().get(0).duplicateOfSourceId());
        assertEquals("duplicate_of_processed", result.skipped().get(0).reason());
    }

    @Test
    void shouldNotSkipDuplicateWhenForceReingest() {
        SourceDO newSource = activeSource(10L, "new-doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, newSource));

        IngestBatchCreateInfo result = ingestBatchService.createBatch(
            SCOPE_ID, USER_ID, List.of(10L), null, null, true);

        assertEquals(1, result.acceptedCount());
        assertEquals(0, result.skippedDuplicateCount());
        verify(sourceService, never()).findDuplicateSource(any(), any());
    }

    @Test
    void shouldNotSkipWhenDuplicateIsSelf() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));

        SourceModel selfModel = new SourceModel();
        selfModel.setId(10L);
        selfModel.setName("doc.pdf");
        when(sourceService.findDuplicateSource(SCOPE_ID, "hash_abc")).thenReturn(selfModel);

        IngestBatchCreateInfo result = ingestBatchService.createBatch(
            SCOPE_ID, USER_ID, List.of(10L), null, null, null);

        assertEquals(1, result.acceptedCount());
        assertEquals(0, result.skippedDuplicateCount());
    }

    @Test
    void shouldRejectDeprecatedSourceAsBefore() {
        SourceDO deprecated = activeSource(10L, "old.pdf", "hash_abc");
        deprecated.setLifecycleStatus("DEPRECATED");
        stubCreateBatchCommon(Map.of(10L, deprecated));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, null, null));
        assertEquals(ErrorCode.INGEST_BATCH_EMPTY.getCode(), ex.getCode());
        assertTrue(ex.getArgs().length > 0 && String.valueOf(ex.getArgs()[0]).contains("已废弃"));
    }

    @Test
    void shouldSetBatchModeFromRequest() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));
        when(sourceService.findDuplicateSource(any(), any())).thenReturn(null);

        ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, "auto", null);

        verify(batchMapper).insert(argThat((IngestBatchDO batch) -> batch != null && "auto".equals(batch.getMode())));
    }

    @Test
    void shouldDefaultBatchModeToReview() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));
        when(sourceService.findDuplicateSource(any(), any())).thenReturn(null);

        ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, null, null);

        verify(batchMapper).insert(argThat((IngestBatchDO batch) -> batch != null && "review".equals(batch.getMode())));
    }

    @Test
    void shouldFallbackToReviewForInvalidMode() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));
        when(sourceService.findDuplicateSource(any(), any())).thenReturn(null);

        ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, "turbo", null);

        verify(batchMapper).insert(argThat((IngestBatchDO batch) -> batch != null && "review".equals(batch.getMode())));
    }

    @Test
    void shouldInheritScopeAutoModeWhenRequestModeIsNull() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));
        when(sourceService.findDuplicateSource(any(), any())).thenReturn(null);
        ScopeDO scope = new ScopeDO();
        scope.setId(SCOPE_ID);
        scope.setIngestMode("auto");
        when(scopeMapper.selectById(SCOPE_ID)).thenReturn(scope);

        ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, null, null);

        verify(batchMapper).insert(argThat((IngestBatchDO batch) -> batch != null && "auto".equals(batch.getMode())));
    }

    @Test
    void shouldPreferExplicitReviewOverScopeAutoMode() {
        SourceDO source = activeSource(10L, "doc.pdf", "hash_abc");
        stubCreateBatchCommon(Map.of(10L, source));
        when(sourceService.findDuplicateSource(any(), any())).thenReturn(null);

        ingestBatchService.createBatch(SCOPE_ID, USER_ID, List.of(10L), null, "review", null);

        verify(batchMapper).insert(argThat((IngestBatchDO batch) -> batch != null && "review".equals(batch.getMode())));
    }
}
