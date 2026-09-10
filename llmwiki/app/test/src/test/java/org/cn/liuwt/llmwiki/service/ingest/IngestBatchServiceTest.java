package org.cn.liuwt.llmwiki.service.ingest;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.IngestBatchMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.facade.model.IngestBatchCreateResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestBatchServiceTest {

    @Mock private ExecutionMapper executionMapper;
    @Mock private IngestBatchMapper batchMapper;
    @Mock private SourceMapper sourceMapper;
    @Mock private ExecutionTracker executionTracker;

    @InjectMocks
    private IngestBatchService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
    }

    @Test
    void shouldRejectBatchWhenExceedingMaxSize() {
        ReflectionTestUtils.setField(service, "maxBatchSize", 2);
        List<Long> sourceIds = List.of(1L, 2L, 3L);

        assertThrows(BusinessException.class, () -> service.createBatch(10L, 7L, sourceIds, null));
    }

    @Test
    void shouldSkipSourcesAndReportWarningsWhenSourceHasActiveExecution() {
        ReflectionTestUtils.setField(service, "maxBatchSize", 50);
        SourceDO ok = new SourceDO();
        ok.setId(1L);
        ok.setScopeId(10L);
        ok.setName("a.md");
        SourceDO busy = new SourceDO();
        busy.setId(2L);
        busy.setScopeId(10L);
        busy.setName("b.pdf");
        when(sourceMapper.selectBatchIds(any())).thenReturn(List.of(ok, busy));
        ExecutionDO inFlight = new ExecutionDO();
        inFlight.setId(100L);
        inFlight.setType("ingest");
        inFlight.setStatus("awaiting_confirmation");
        inFlight.setSourceId(2L);
        inFlight.setScopeId(10L);
        when(executionMapper.selectList(any())).thenReturn(List.of(inFlight));
        ExecutionModel created = new ExecutionModel();
        created.setId(500L);
        when(executionTracker.createExecution(eq("ingest"), eq(10L), eq(1L), isNull())).thenReturn(created);

        IngestBatchCreateResponse response = service.createBatch(10L, 7L, List.of(1L, 2L), "指引领");

        assertEquals(1, response.getExecutionIds().size());
        assertEquals(1, response.getWarnings().size());
    }
}
