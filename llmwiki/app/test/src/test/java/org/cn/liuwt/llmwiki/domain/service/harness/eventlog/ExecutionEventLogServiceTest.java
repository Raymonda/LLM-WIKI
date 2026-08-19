package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionEventDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEventLogServiceTest {

    private ExecutionEventMapper eventMapper;
    private List<Object> publishedEvents;
    private EventLogProperties properties;
    private ExecutionEventLogService service;

    @BeforeEach
    void setUp() {
        eventMapper = Mockito.mock(ExecutionEventMapper.class);
        publishedEvents = new ArrayList<>();
        properties = new EventLogProperties();
        properties.setEnabled(true);
        service = new ExecutionEventLogService(eventMapper, publishedEvents::add, properties);
    }

    @Test
    void append_disabled_skippedWithoutDbAccess() {
        properties.setEnabled(false);

        assertFalse(service.append("exec-1", ExecutionEventTypes.TURN_START, Map.of("k", "v")));
        Mockito.verifyNoInteractions(eventMapper);
    }

    @Test
    void append_nullOrBlankExecutionId_skipped() {
        assertFalse(service.append(null, ExecutionEventTypes.TURN_START, Map.of()));
        assertFalse(service.append("  ", ExecutionEventTypes.TURN_START, Map.of()));
        Mockito.verifyNoInteractions(eventMapper);
    }

    @Test
    void append_writesEventAndPublishesSpringEvent() {
        when(eventMapper.insert(any(ExecutionEventDO.class))).thenReturn(1);

        boolean appended = service.append("exec-1", ExecutionEventTypes.TOOL_CALL,
                Map.of("tool", "searchWiki", "scopeId", "5"));

        assertTrue(appended);
        ArgumentCaptor<ExecutionEventDO> captor = ArgumentCaptor.forClass(ExecutionEventDO.class);
        verify(eventMapper).insert(captor.capture());
        ExecutionEventDO saved = captor.getValue();
        assertEquals("exec-1", saved.getExecutionId());
        assertEquals(1, saved.getSeq());
        assertEquals("tool/call", saved.getEventType());
        assertTrue(saved.getPayloadJson().contains("searchWiki"));
        assertEquals(1, publishedEvents.size());
        ExecutionLogEvent logEvent = assertInstanceOf(ExecutionLogEvent.class, publishedEvents.get(0));
        assertEquals("exec-1", logEvent.getEvent().getExecutionId());
    }

    @Test
    void append_seqIncrementsAndResumesFromDbMax() {
        ExecutionEventDO latest = new ExecutionEventDO();
        latest.setSeq(41);
        when(eventMapper.selectOne(any())).thenReturn(latest, (ExecutionEventDO) null);
        when(eventMapper.insert(any(ExecutionEventDO.class))).thenReturn(1);

        service.append("exec-1", ExecutionEventTypes.TURN_START, null);
        service.append("exec-1", ExecutionEventTypes.TURN_END, null);
        service.append("exec-2", ExecutionEventTypes.TURN_START, null);

        ArgumentCaptor<ExecutionEventDO> captor = ArgumentCaptor.forClass(ExecutionEventDO.class);
        verify(eventMapper, Mockito.times(3)).insert(captor.capture());
        List<ExecutionEventDO> saved = captor.getAllValues();
        assertEquals(42, saved.get(0).getSeq());
        assertEquals(43, saved.get(1).getSeq());
        assertEquals(1, saved.get(2).getSeq());
    }

    @Test
    void append_mapperThrows_bestEffortNoException() {
        when(eventMapper.insert(any(ExecutionEventDO.class))).thenThrow(new IllegalStateException("db down"));

        assertFalse(service.append("exec-1", ExecutionEventTypes.ERROR, Map.of("m", "x")));
    }

    @Test
    void append_nullPayload_storesNullJson() {
        when(eventMapper.insert(any(ExecutionEventDO.class))).thenReturn(1);

        assertTrue(service.append("exec-1", ExecutionEventTypes.TURN_END, null));

        ArgumentCaptor<ExecutionEventDO> captor = ArgumentCaptor.forClass(ExecutionEventDO.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getPayloadJson());
    }

    @Test
    void replayAfter_delegatesToMapper() {
        List<ExecutionEventDO> events = List.of(new ExecutionEventDO());
        when(eventMapper.selectList(any())).thenReturn(events);

        assertEquals(events, service.replayAfter("exec-1", 10L));
    }
}
