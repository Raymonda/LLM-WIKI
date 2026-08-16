package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.integration.ai.AiSlotRouter;
import org.cn.liuwt.llmwiki.integration.ai.ContextBudgetManager;
import org.cn.liuwt.llmwiki.integration.storage.LocalStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CompactionServiceTest {

    @TempDir
    Path tempDir;

    private ContextBudgetManager budgetManager;
    private AiSlotRouter slotRouter;
    private ChatModel summaryModel;
    private SpillService spillService;
    private CompactionProperties properties;
    private CompactionService service;

    @BeforeEach
    void setUp() {
        budgetManager = new ContextBudgetManager();
        slotRouter = Mockito.mock(AiSlotRouter.class);
        summaryModel = Mockito.mock(ChatModel.class);
        LocalStorageProvider storageProvider = new LocalStorageProvider();
        storageProvider.setBasePath(tempDir.toString());
        SpillProperties spillProperties = new SpillProperties();
        spillProperties.setMaxInlineBytes(64);
        spillService = new SpillService(storageProvider, spillProperties);
        properties = new CompactionProperties();
        properties.setEnabled(true);
        properties.setMaxFieldTokens(100);
    }

    private CompactionService build() {
        return new CompactionService(budgetManager, slotRouter, spillService, properties,
                Mockito.mock(org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService.class));
    }

    @Test
    void compressField_disabled_returnsOriginal() {
        properties.setEnabled(false);
        String large = largeText();

        assertEquals(large, build().compressField(null, 1L, "factContext", large));
    }

    @Test
    void compressField_withinBudget_returnsOriginal() {
        when(slotRouter.getModel("summary")).thenReturn(null);

        String small = "short context";

        assertEquals(small, build().compressField(null, 1L, "factContext", small));
    }

    @Test
    void compressField_overBudgetWithoutSummaryModel_prunesAndSpillsRemovedPart() {
        when(slotRouter.getModel("summary")).thenReturn(null);
        String large = largeText();

        String compacted = build().compressField(null, 1L, "factContext", large);

        assertTrue(compacted.length() < large.length());
        assertTrue(compacted.contains("SPILL"));
        String spillId = extractSpillId(compacted);
        String spilled = spillService.readSpill(1L, "compaction", spillId);
        assertTrue(spilled.length() > 0);
        assertTrue(large.endsWith(spilled));
    }

    @Test
    void compressField_overBudgetWithSummaryModel_returnsSummary() {
        when(slotRouter.getModel("summary")).thenReturn(summaryModel);
        when(summaryModel.call(any(Prompt.class))).thenReturn(chatResponse("compact summary"));

        String compacted = build().compressField(null, 1L, "layer1", largeText());

        assertEquals("compact summary", compacted);
    }

    @Test
    void compressField_summaryFails_fallsBackToPrune() {
        when(slotRouter.getModel("summary")).thenReturn(summaryModel);
        when(summaryModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("provider down"));

        String compacted = build().compressField(null, 1L, "layer1", largeText());

        assertTrue(compacted.contains("SPILL"));
        assertTrue(compacted.length() < largeText().length());
    }

    @Test
    void compressField_summaryTooLong_fallsBackToPrune() {
        when(slotRouter.getModel("summary")).thenReturn(summaryModel);
        when(summaryModel.call(any(Prompt.class))).thenReturn(chatResponse("s ".repeat(2000)));

        String compacted = build().compressField(null, 1L, "layer1", largeText());

        assertNotEquals("s ".repeat(2000), compacted);
        assertTrue(compacted.contains("SPILL"));
    }

    @Test
    void compressField_summarizeDisabled_prunesDirectly() {
        properties.setSummarize(false);
        String large = largeText();

        String compacted = build().compressField(null, 1L, "factContext", large);

        assertTrue(compacted.length() < large.length());
    }

    private static String largeText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("line-").append(i).append(" of oversized retrieval context\n");
        }
        return sb.toString();
    }

    private static ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static String extractSpillId(String locator) {
        int start = locator.indexOf("spillId=") + "spillId=".length();
        int end = locator.indexOf(' ', start);
        return locator.substring(start, end);
    }
}
