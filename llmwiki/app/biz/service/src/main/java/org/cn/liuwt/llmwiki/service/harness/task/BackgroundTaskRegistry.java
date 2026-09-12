package org.cn.liuwt.llmwiki.service.harness.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BackgroundTaskRegistry {

    private final Map<String, BackgroundTaskHandler> handlers = new HashMap<>();

    @Autowired
    public BackgroundTaskRegistry(List<BackgroundTaskHandler> handlerList) {
        for (BackgroundTaskHandler handler : handlerList) {
            BackgroundTaskHandler existing = handlers.putIfAbsent(handler.taskType(), handler);
            if (existing != null) {
                throw new IllegalStateException("Duplicate BackgroundTaskHandler for taskType=" + handler.taskType());
            }
        }
    }

    public BackgroundTaskHandler getHandler(String taskType) {
        return handlers.get(taskType);
    }

    public boolean hasHandler(String taskType) {
        return handlers.containsKey(taskType);
    }
}
