package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llmwiki.harness.tool-pipeline")
public class ToolPipelineProperties {

    private boolean enabled = false;

    private long timeoutMs = 30000;

    private long slowToolWarnMs = 10000;

    private int scopeMaxConcurrentTools = 25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getSlowToolWarnMs() {
        return slowToolWarnMs;
    }

    public void setSlowToolWarnMs(long slowToolWarnMs) {
        this.slowToolWarnMs = slowToolWarnMs;
    }

    public int getScopeMaxConcurrentTools() {
        return scopeMaxConcurrentTools;
    }

    public void setScopeMaxConcurrentTools(int scopeMaxConcurrentTools) {
        this.scopeMaxConcurrentTools = scopeMaxConcurrentTools;
    }
}
