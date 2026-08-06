package org.cn.liuwt.llmwiki.web.aspect;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageDailyService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.TokenUsageMonitor;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.ai.TokenUsageContext;
import jakarta.annotation.PostConstruct;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class TokenUsageAspect {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageAspect.class);

    @Autowired
    private TokenUsageMonitor tokenUsageMonitor;

    @Autowired
    private TokenUsageDailyService dailyService;

    @PostConstruct
    public void init() {
        // 注册流式调用记录器：streamChat 完成后通过此回调记录 token
        LlmClient.setStreamUsageRecorder((scopeId, operationType, estimatedOutputTokens) -> {
            try {
                tokenUsageMonitor.recordUsage(scopeId, estimatedOutputTokens);
                dailyService.recordDaily(scopeId, operationType, 0, estimatedOutputTokens);
                log.debug("Stream token usage recorded: scope={}, type={}, estOut={}", scopeId, operationType, estimatedOutputTokens);
            } catch (Exception e) {
                log.warn("Failed to record stream token usage: {}", e.getMessage());
            }
        });
    }

    @Around("execution(public * org.cn.liuwt.llmwiki.integration.ai.LlmClient.chat*(..)) || " +
            "execution(public * org.cn.liuwt.llmwiki.integration.ai.LlmClient.chatMultimodal*(..)) || " +
            "execution(public * org.cn.liuwt.llmwiki.integration.ai.LlmClient.streamChat*(..))")
    public Object recordTokenUsage(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();

        try {
            TokenUsageContext.Context ctx = TokenUsageContext.get();
            LlmClient.TokenCallUsage usage = LlmClient.getAndClearLastUsage();

            if (ctx != null && ctx.scopeId() != null && usage != null && usage.total() > 0) {
                tokenUsageMonitor.recordUsage(ctx.scopeId(), usage.total());
                dailyService.recordDaily(ctx.scopeId(), ctx.operationType(), usage.inputTokens(), usage.outputTokens());
                log.debug("Token usage recorded: scope={}, type={}, in={}, out={}",
                    ctx.scopeId(), ctx.operationType(), usage.inputTokens(), usage.outputTokens());
            } else if (ctx != null && ctx.scopeId() != null) {
                log.warn("Token usage NOT recorded (usage is null or zero): scope={}, type={}, method={}",
                    ctx.scopeId(), ctx.operationType(), pjp.getSignature().getName());
            }
        } catch (Exception e) {
            log.warn("Failed to record token usage via AOP: {}", e.getMessage());
        }

        return result;
    }
}
