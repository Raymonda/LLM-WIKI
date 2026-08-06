package org.cn.liuwt.llmwiki.integration.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ContextBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetManager.class);

    private static final int CHARS_PER_CN_TOKEN = 2;
    private static final int CHARS_PER_EN_TOKEN = 4;

    @Value("${llmwiki.llm.budget.ingest.max-input-tokens:120000}")
    private int ingestMaxTokens;

    @Value("${llmwiki.llm.budget.query.max-input-tokens:32000}")
    private int queryMaxTokens;

    @Value("${llmwiki.llm.budget.lint.max-input-tokens:64000}")
    private int lintMaxTokens;

    @Value("${llmwiki.llm.budget.default.max-input-tokens:64000}")
    private int defaultMaxTokens;

    public record ContextBudgetResult(
        String systemPrompt,
        String userMessage,
        int estimatedTokens,
        boolean truncated
    ) {}

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int chineseCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') chineseCount++;
        }
        int nonChinese = text.length() - chineseCount;
        return chineseCount / CHARS_PER_CN_TOKEN + nonChinese / CHARS_PER_EN_TOKEN;
    }

    public int estimateInputTokens(String systemPrompt, String userMessage) {
        return estimateTokens(systemPrompt) + estimateTokens(userMessage);
    }

    public int getConfiguredMaxTokens(String operationType) {
        if (operationType == null) return defaultMaxTokens;
        return switch (operationType) {
            case "ingest" -> ingestMaxTokens;
            case "query" -> queryMaxTokens;
            case "lint" -> lintMaxTokens;
            default -> defaultMaxTokens;
        };
    }

    public int getMaxTokensForCurrentContext() {
        TokenUsageContext.Context ctx = TokenUsageContext.get();
        if (ctx != null && ctx.operationType() != null) {
            return getConfiguredMaxTokens(ctx.operationType());
        }
        return defaultMaxTokens;
    }

    public ContextBudgetResult enforceBudget(String systemPrompt, String userMessage, int maxInputTokens) {
        int estimated = estimateInputTokens(systemPrompt, userMessage);
        if (estimated <= maxInputTokens) {
            return new ContextBudgetResult(systemPrompt, userMessage, estimated, false);
        }

        int systemTokens = estimateTokens(systemPrompt);
        int budgetForUser = maxInputTokens - systemTokens;
        if (budgetForUser <= 0) {
            budgetForUser = maxInputTokens / 2;
        }

        String truncatedUser = truncateToTokens(userMessage, budgetForUser);
        int truncatedEstimated = systemTokens + estimateTokens(truncatedUser);
        log.warn("ContextBudgetManager: input exceeded budget (estimated={}, max={}), truncated userMessage (new estimated={})",
            estimated, maxInputTokens, truncatedEstimated);
        return new ContextBudgetResult(systemPrompt, truncatedUser, truncatedEstimated, true);
    }

    public String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        int currentTokens = estimateTokens(text);
        if (currentTokens <= maxTokens) return text;

        int budgetChars = maxTokens * 3;
        if (budgetChars > text.length()) budgetChars = text.length();

        int cutPos = text.lastIndexOf('\n', budgetChars);
        if (cutPos <= 0 || cutPos > budgetChars) cutPos = budgetChars;

        return text.substring(0, cutPos) + "\n\n...(内容过长，已截断以适应上下文预算)";
    }
}
