package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventTypes;
import org.cn.liuwt.llmwiki.integration.ai.AiSlotRouter;
import org.cn.liuwt.llmwiki.integration.ai.ContextBudgetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    private static final String SPILL_EXECUTION_ID = "compaction";

    private final ContextBudgetManager budgetManager;
    private final AiSlotRouter slotRouter;
    private final SpillService spillService;
    private final CompactionProperties properties;
    private final ExecutionEventLogService eventLog;

    public CompactionService(ContextBudgetManager budgetManager, AiSlotRouter slotRouter,
                             SpillService spillService, CompactionProperties properties,
                             ExecutionEventLogService eventLog) {
        this.budgetManager = budgetManager;
        this.slotRouter = slotRouter;
        this.spillService = spillService;
        this.properties = properties;
        this.eventLog = eventLog;
    }

    public String compressField(String executionId, Long scopeId, String fieldName, String text) {
        if (!properties.isEnabled() || text == null || text.isEmpty()) {
            return text;
        }
        int estimated = budgetManager.estimateTokens(text);
        if (estimated <= properties.getMaxFieldTokens()) {
            return text;
        }
        String eventId = executionId != null && !executionId.isBlank()
                ? executionId
                : (scopeId != null ? "scope-" + scopeId : null);
        if (properties.isSummarize()) {
            String summary = summarizeQuietly(fieldName, text);
            if (summary != null && !summary.isEmpty()
                    && budgetManager.estimateTokens(summary) <= properties.getMaxFieldTokens()) {
                log.info("Field compacted by summary: field={}, scopeId={}, beforeTokens={}, afterTokens={}",
                        fieldName, scopeId, estimated, budgetManager.estimateTokens(summary));
                recordTriggered(eventId, fieldName, "summarized", estimated, budgetManager.estimateTokens(summary));
                return summary;
            }
        }
        return prune(eventId, scopeId, fieldName, text);
    }

    private void recordTriggered(String executionId, String fieldName, String action, int beforeTokens, int afterTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("field", fieldName);
        payload.put("action", action);
        payload.put("beforeTokens", beforeTokens);
        payload.put("afterTokens", afterTokens);
        eventLog.append(executionId, ExecutionEventTypes.COMPACTION_TRIGGERED, payload);
    }

    private String summarizeQuietly(String fieldName, String text) {
        ChatModel summaryModel = slotRouter.getModel(properties.getSummarySlot());
        if (summaryModel == null) {
            return null;
        }
        try {
            String instruction = "将以下内容压缩为不超过 " + properties.getMaxFieldTokens()
                    + " tokens 的摘要，完整保留关键事实、数据、结论与页面引用，输出纯文本摘要：\n\n" + text;
            String summary = summaryModel.call(new Prompt(instruction)).getResult().getOutput().getText();
            log.info("Field summary generated via slot {}: field={}, summaryTokens={}",
                    properties.getSummarySlot(), fieldName, summary != null ? budgetManager.estimateTokens(summary) : -1);
            return summary;
        } catch (Exception e) {
            log.warn("Field summary failed, falling back to prune: field={}, error={}", fieldName, e.getMessage());
            return null;
        }
    }

    private String prune(String executionId, Long scopeId, String fieldName, String text) {
        int cutPos = cutPosition(text, properties.getMaxFieldTokens());
        if (cutPos >= text.length()) {
            return text;
        }
        String kept = text.substring(0, cutPos);
        String removed = text.substring(cutPos);
        if (properties.isSpillPruned() && scopeId != null) {
            SpillService.SpillResult spill = spillService.spillIfNeeded(scopeId, SPILL_EXECUTION_ID, removed);
            if (spill.spilled()) {
                kept = kept + "\n\n" + spill.content();
            }
        } else {
            kept = kept + "\n\n...(内容过长，已剪枝)";
        }
        log.info("Field compacted by pruning: field={}, scopeId={}, beforeTokens={}, afterTokens={}",
                fieldName, scopeId, budgetManager.estimateTokens(text), budgetManager.estimateTokens(kept));
        recordTriggered(executionId, fieldName, "pruned", budgetManager.estimateTokens(text), budgetManager.estimateTokens(kept));
        return kept;
    }

    private static int cutPosition(String text, int maxTokens) {
        int budgetChars = Math.min(maxTokens * 3, text.length());
        int cutPos = text.lastIndexOf('\n', budgetChars);
        if (cutPos <= 0 || cutPos > budgetChars) {
            cutPos = budgetChars;
        }
        return cutPos;
    }
}
