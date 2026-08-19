package org.cn.liuwt.llmwiki.domain.service.harness;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llmwiki.harness.compaction")
public class CompactionProperties {

    private boolean enabled = false;

    private boolean summarize = true;

    private String summarySlot = "summary";

    private int maxFieldTokens = 16000;

    private boolean spillPruned = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSummarize() {
        return summarize;
    }

    public void setSummarize(boolean summarize) {
        this.summarize = summarize;
    }

    public String getSummarySlot() {
        return summarySlot;
    }

    public void setSummarySlot(String summarySlot) {
        this.summarySlot = summarySlot;
    }

    public int getMaxFieldTokens() {
        return maxFieldTokens;
    }

    public void setMaxFieldTokens(int maxFieldTokens) {
        this.maxFieldTokens = maxFieldTokens;
    }

    public boolean isSpillPruned() {
        return spillPruned;
    }

    public void setSpillPruned(boolean spillPruned) {
        this.spillPruned = spillPruned;
    }
}
