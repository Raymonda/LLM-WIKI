package org.cn.liuwt.llmwiki.integration.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AiRuntimeConfigHolderTest {

    @Test
    void shouldReturnEmptyWhenNeverUpdated() {
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        assertTrue(holder.get().isEmpty());
    }

    @Test
    void shouldSwapSnapshotAtomicallyWhenUpdated() {
        AiRuntimeConfigHolder holder = new AiRuntimeConfigHolder();
        AiRuntimeConfig first = holder.get();
        holder.update(new AiRuntimeConfig(
            Map.of("p1", new AiRuntimeConfig.ProviderEntry("https://a", "k", true)), Map.of()));
        assertNotSame(first, holder.get());
        assertTrue(holder.get().providers().containsKey("p1"));
        holder.update(null);
        assertTrue(holder.get().isEmpty());
    }
}
