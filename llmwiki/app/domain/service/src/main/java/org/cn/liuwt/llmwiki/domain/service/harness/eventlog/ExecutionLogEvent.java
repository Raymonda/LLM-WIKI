package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionEventDO;
import org.springframework.context.ApplicationEvent;

public class ExecutionLogEvent extends ApplicationEvent {

    private final ExecutionEventDO event;

    public ExecutionLogEvent(Object source, ExecutionEventDO event) {
        super(source);
        this.event = event;
    }

    public ExecutionEventDO getEvent() {
        return event;
    }
}
