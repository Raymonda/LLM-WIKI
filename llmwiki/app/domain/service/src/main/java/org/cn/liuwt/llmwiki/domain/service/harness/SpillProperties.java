package org.cn.liuwt.llmwiki.domain.service.harness;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llmwiki.harness.spill")
public class SpillProperties {

    private long maxInlineBytes = 65536;

    public long getMaxInlineBytes() {
        return maxInlineBytes;
    }

    public void setMaxInlineBytes(long maxInlineBytes) {
        this.maxInlineBytes = maxInlineBytes;
    }
}
