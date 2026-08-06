package org.cn.liuwt.llmwiki.integration.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llmwiki.elasticsearch")
public class ElasticsearchProperties {

    private String uris = "http://localhost:9200";
    private String indexName = "llmwiki-pages";
    private String username;
    private String password;
    private Retry retry = new Retry();

    public String getUris() { return uris; }
    public void setUris(String uris) { this.uris = uris; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public static class Retry {
        private long intervalMs = 60000L;
        private int maxAttempts = 5;

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }
}
