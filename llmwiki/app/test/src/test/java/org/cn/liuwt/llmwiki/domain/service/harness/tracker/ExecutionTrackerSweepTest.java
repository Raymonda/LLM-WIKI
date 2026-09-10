package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionTrackerSweepTest {

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private ExecutionStepMapper executionStepMapper;

    @Mock
    private DistributedEventPublisher eventPublisher;

    @InjectMocks
    private ExecutionTrackerImpl tracker;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionStepDO.class);
    }

    @Test
    void shouldKeepQueuedPendingIngestExecutionWhenSweepingOrphans() {
        ExecutionDO queuedIngest = new ExecutionDO();
        queuedIngest.setId(1L);
        queuedIngest.setType("ingest");
        queuedIngest.setStatus("pending");
        ExecutionDO interruptedRun = new ExecutionDO();
        interruptedRun.setId(2L);
        interruptedRun.setType("ingest");
        interruptedRun.setStatus("running");
        ExecutionDO pendingLint = new ExecutionDO();
        pendingLint.setId(3L);
        pendingLint.setType("lint");
        pendingLint.setStatus("pending");
        when(executionMapper.selectList(any())).thenReturn(List.of(queuedIngest, interruptedRun, pendingLint));

        tracker.sweepOrphanedExecutions();

        ArgumentCaptor<ExecutionDO> captor = ArgumentCaptor.forClass(ExecutionDO.class);
        verify(executionMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        List<ExecutionDO> swept = captor.getAllValues();
        assertTrue(swept.stream().noneMatch(e -> e.getId().equals(1L)), "queued pending ingest must survive");
        assertEquals("failed", swept.stream().filter(e -> e.getId().equals(2L)).findFirst().orElseThrow().getStatus());
        assertEquals("failed", swept.stream().filter(e -> e.getId().equals(3L)).findFirst().orElseThrow().getStatus());
    }

    @Test
    void shouldScopeStepSweepToSweptExecutions() {
        ExecutionDO interruptedRun = new ExecutionDO();
        interruptedRun.setId(2L);
        interruptedRun.setType("ingest");
        interruptedRun.setStatus("running");
        when(executionMapper.selectList(any())).thenReturn(List.of(interruptedRun));

        tracker.sweepOrphanedExecutions();

        ArgumentCaptor<Wrapper<ExecutionStepDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(executionStepMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("execution_id"), "step sweep must filter by execution_id");
    }
}
