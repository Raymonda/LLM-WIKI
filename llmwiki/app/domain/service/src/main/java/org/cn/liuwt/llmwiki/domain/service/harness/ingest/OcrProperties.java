package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llmwiki.ocr")
public class OcrProperties {

    private boolean enabled = true;
    private String model = "qwen-vl-ocr";
    private double scanThreshold = 0.3;
    private int maxPages = 20;
    private long timeoutMs = 120000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getScanThreshold() { return scanThreshold; }
    public void setScanThreshold(double scanThreshold) { this.scanThreshold = scanThreshold; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}