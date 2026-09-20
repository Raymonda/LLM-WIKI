package org.cn.liuwt.llmwiki.integration.ai;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AiRuntimeConfigHolder {

    private final AtomicReference<AiRuntimeConfig> ref = new AtomicReference<>(AiRuntimeConfig.empty());

    public AiRuntimeConfig get() { return ref.get(); }

    public void update(AiRuntimeConfig cfg) {
        ref.set(cfg == null ? AiRuntimeConfig.empty() : cfg);
    }
}
