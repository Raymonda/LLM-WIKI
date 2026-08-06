package org.cn.liuwt.llmwiki.integration.ai;

public class TokenUsageContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    public record Context(Long scopeId, String operationType) {}

    public static void set(Long scopeId, String operationType) {
        HOLDER.set(new Context(scopeId, operationType != null ? operationType : "unknown"));
    }

    public static Context get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
