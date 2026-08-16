package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoopHygieneFilter implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(LoopHygieneFilter.class);

    private final LoopHygieneProperties properties;

    private final Map<String, Map<String, AtomicInteger>> executedFingerprints = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptCounts = new ConcurrentHashMap<>();

    public LoopHygieneFilter(LoopHygieneProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 150;
    }

    @Override
    public FilterVerdict preFilter(ToolInvocation invocation) {
        if (!properties.isEnabled()) {
            return FilterVerdict.allow();
        }
        String scopeKey = scopeKeyOf(invocation);
        evictIfNeeded();

        int attempts = attemptCounts.computeIfAbsent(scopeKey, k -> new AtomicInteger()).incrementAndGet();
        if (attempts > properties.getMaxCallsPerExecution()) {
            log.warn("loop-hygiene blocked: scopeKey={} attempts={} exceeded maxCallsPerExecution={}",
                scopeKey, attempts, properties.getMaxCallsPerExecution());
            return FilterVerdict.reject("LoopHygieneFilter",
                "本会话工具调用总数已达上限 " + properties.getMaxCallsPerExecution() + "，请立即基于已有信息收敛出答案");
        }

        int repeated = countOf(scopeKey, fingerprintOf(invocation));
        if (repeated >= properties.getMaxRepeats()) {
            log.warn("loop-hygiene blocked: scopeKey={} tool={} repeated={} maxRepeats={}",
                scopeKey, invocation.toolName(), repeated, properties.getMaxRepeats());
            return FilterVerdict.reject("LoopHygieneFilter",
                "工具 " + invocation.toolName() + " 以相同参数成功执行过 " + repeated
                    + " 次，重复调用已被阻断，请调整检索策略或直接收敛答案");
        }
        return FilterVerdict.allow();
    }

    @Override
    public void postFilter(ToolInvocation invocation, ToolResult result) {
        if (!properties.isEnabled() || !result.success()) {
            return;
        }
        String scopeKey = scopeKeyOf(invocation);
        executedFingerprints.computeIfAbsent(scopeKey, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(fingerprintOf(invocation), k -> new AtomicInteger())
            .incrementAndGet();
    }

    public void reset(String scopeKey) {
        if (scopeKey == null) {
            return;
        }
        executedFingerprints.remove(scopeKey);
        attemptCounts.remove(scopeKey);
    }

    private int countOf(String scopeKey, String fingerprint) {
        AtomicInteger count = executedFingerprints.getOrDefault(scopeKey, Map.of()).get(fingerprint);
        return count != null ? count.get() : 0;
    }

    private String scopeKeyOf(ToolInvocation invocation) {
        return invocation.hasScope() ? invocation.scopeId() : "anonymous";
    }

    private String fingerprintOf(ToolInvocation invocation) {
        StringBuilder sb = new StringBuilder(invocation.toolName());
        Object[] args = invocation.args();
        if (args != null) {
            for (Object arg : args) {
                sb.append('|').append(renderArg(arg));
            }
        }
        return sb.toString();
    }

    private static String renderArg(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg instanceof String || arg instanceof Number || arg instanceof Boolean) {
            return String.valueOf(arg);
        }
        return arg.getClass().getSimpleName();
    }

    private void evictIfNeeded() {
        if (attemptCounts.size() > properties.getMaxTrackedScopes()) {
            log.warn("loop-hygiene tracked scopes exceeded {}, clearing all counters", properties.getMaxTrackedScopes());
            attemptCounts.clear();
            executedFingerprints.clear();
        }
    }
}
