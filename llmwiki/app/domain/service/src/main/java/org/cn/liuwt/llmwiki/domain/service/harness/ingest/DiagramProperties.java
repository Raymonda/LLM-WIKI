package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llmwiki.diagram")
public class DiagramProperties {

    private boolean enabled = false;
    private String model = "kimi-k2.6";
    private String apiKey = "";
    private int maxImages = 15;
    private long timeoutMs = 180000;
    private int dpi = 200;
    private int jpegQuality = 85;
    private int concurrency = 4;
    private double scoreThreshold = 5.0;
    private double largeDrawingRatio = 0.05;
    private double significantImageRatio = 0.05;
    private double payloadGateMb = 2.0;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public int getMaxImages() { return maxImages; }
    public void setMaxImages(int maxImages) { this.maxImages = maxImages; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getDpi() { return dpi; }
    public void setDpi(int dpi) { this.dpi = dpi; }
    public int getJpegQuality() { return jpegQuality; }
    public void setJpegQuality(int jpegQuality) { this.jpegQuality = jpegQuality; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
    public double getLargeDrawingRatio() { return largeDrawingRatio; }
    public void setLargeDrawingRatio(double largeDrawingRatio) { this.largeDrawingRatio = largeDrawingRatio; }
    public double getSignificantImageRatio() { return significantImageRatio; }
    public void setSignificantImageRatio(double significantImageRatio) { this.significantImageRatio = significantImageRatio; }
    public double getPayloadGateMb() { return payloadGateMb; }
    public void setPayloadGateMb(double payloadGateMb) { this.payloadGateMb = payloadGateMb; }
}