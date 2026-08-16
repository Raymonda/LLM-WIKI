package org.cn.liuwt.llmwiki.domain.service.harness.eventlog;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llmwiki.harness.event-log")
public class EventLogProperties {

    private boolean enabled = false;

    private int replayLimit = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getReplayLimit() {
        return replayLimit;
    }

    public void setReplayLimit(int replayLimit) {
        this.replayLimit = replayLimit;
    }
}
