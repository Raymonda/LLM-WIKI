package org.cn.liuwt.llmwiki.domain.service.harness.tracker;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionHistoryServiceImplTest {

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private ExecutionStepMapper executionStepMapper;

    @InjectMocks
    private ExecutionHistoryServiceImpl service;

    private static final Duration NO_STEP_GRACE = Duration.ofMinutes(5);
    private static final Duration NO_HEARTBEAT_GRACE = Duration.ofMinutes(30);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ExecutionDO.class);
        TableInfoHelper.initTableInfo(assistant, ExecutionStepDO.class);
    }

    @Test
    void shouldNotTreatQueuedExecutionAsZombieWhenNeverDispatched() {
        ExecutionDO queued = execution(1L, "pending", null, LocalDateTime.now().minusMinutes(20));
        when(executionMapper.selectList(any())).thenReturn(List.of(queued));
        when(executionStepMapper.selectList(any())).thenReturn(List.of());

        List<ExecutionDO> zombies = service.findZombieExecutions(3L, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);

        assertTrue(zombies.isEmpty(), "queued pending execution must survive beyond no-step grace");
    }

    @Test
    void shouldTreatRunningExecutionAsZombieWhenNoStepBeyondDispatcherGrace() {
        ExecutionDO stuck = execution(2L, "running", "node-1", LocalDateTime.now().minusMinutes(20));
        when(executionMapper.selectList(any())).thenReturn(List.of(stuck));
        when(executionStepMapper.selectList(any())).thenReturn(List.of());

        List<ExecutionDO> zombies = service.findZombieExecutions(3L, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);

        assertEquals(1, zombies.size());
        assertEquals(2L, zombies.get(0).getId());
    }

    @Test
    void shouldNotTreatExecutionAsZombieWhenAwaitingUserConfirmation() {
        ExecutionDO awaiting = execution(3L, "awaiting_confirmation", "node-1", LocalDateTime.now().minusHours(2));
        when(executionMapper.selectList(any())).thenReturn(List.of(awaiting));

        List<ExecutionDO> zombies = service.findZombieExecutions(3L, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);

        assertTrue(zombies.isEmpty(), "awaiting_confirmation is a user decision point and must not be reclaimed");
    }

    @Test
    void shouldTreatRunningExecutionAsZombieWhenLastStepBeyondHeartbeatGrace() {
        ExecutionDO stalled = execution(4L, "running", "node-1", LocalDateTime.now().minusHours(2));
        ExecutionStepDO oldStep = new ExecutionStepDO();
        oldStep.setExecutionId(4L);
        oldStep.setStartedAt(LocalDateTime.now().minusHours(2));
        when(executionMapper.selectList(any())).thenReturn(List.of(stalled));
        when(executionStepMapper.selectList(any())).thenReturn(List.of(oldStep));

        List<ExecutionDO> zombies = service.findZombieExecutions(3L, NO_STEP_GRACE, NO_HEARTBEAT_GRACE);

        assertEquals(1, zombies.size());
        assertEquals(4L, zombies.get(0).getId());
    }

    private ExecutionDO execution(Long id, String status, String nodeId, LocalDateTime startedAt) {
        ExecutionDO exec = new ExecutionDO();
        exec.setId(id);
        exec.setType("ingest");
        exec.setStatus(status);
        exec.setNodeId(nodeId);
        exec.setScopeId(3L);
        exec.setStartedAt(startedAt);
        return exec;
    }
}
