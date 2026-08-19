package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llmwiki.harness.loop-hygiene")
public class LoopHygieneProperties {

    private boolean enabled = false;

    private int maxRepeats = 3;

    private int maxCallsPerExecution = 200;

    private int maxTrackedScopes = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRepeats() {
        return maxRepeats;
    }

    public void setMaxRepeats(int maxRepeats) {
        this.maxRepeats = maxRepeats;
    }

    public int getMaxCallsPerExecution() {
        return maxCallsPerExecution;
    }

    public void setMaxCallsPerExecution(int maxCallsPerExecution) {
        this.maxCallsPerExecution = maxCallsPerExecution;
    }

    public int getMaxTrackedScopes() {
        return maxTrackedScopes;
    }

    public void setMaxTrackedScopes(int maxTrackedScopes) {
        this.maxTrackedScopes = maxTrackedScopes;
    }
}
