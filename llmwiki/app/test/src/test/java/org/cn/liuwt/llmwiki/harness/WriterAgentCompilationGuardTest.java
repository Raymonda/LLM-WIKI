package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.WriterAgent;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriterAgentCompilationGuardTest {

    @Mock
    private StorageProvider storageProvider;

    @Mock
    private ExecutionEventLogService executionEventLog;

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = WriterAgent.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private static String invokeFlush(WriterAgent agent, String scopeIdStr, String pagePath, String content,
                                      String titleOrNull, Map<String, String> collector, IngestContext context) {
        try {
            java.lang.reflect.Method m = WriterAgent.class.getDeclaredMethod("flushCompiledPage",
                String.class, String.class, String.class, String.class, Map.class, IngestContext.class);
            m.setAccessible(true);
            return (String) m.invoke(agent, scopeIdStr, pagePath, content, titleOrNull, collector, context);
        } catch (Exception e) {
            throw new IllegalStateException("flushCompiledPage failed", e);
        }
    }

    @Test
    void shouldFixDuplicateParenAndRecordGuardEventWhenFlushing() {
        WriterAgent agent = new WriterAgent();
        inject(agent, "storageProvider", storageProvider);
        inject(agent, "executionEventLog", executionEventLog);
        Map<String, String> collector = new HashMap<>();
        IngestContext context = new IngestContext(1L, 2L, 100L, null);

        String result = invokeFlush(agent, "1", "pages/p.md", "托管人为泰康（泰康）。", null, collector, context);

        assertEquals("托管人为泰康。", result);
        assertEquals("托管人为泰康。", collector.get("pages/p.md"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(storageProvider).write(eq("1"), eq("wiki/pages/p.md"), bytes.capture());
        assertEquals("托管人为泰康。", new String(bytes.getValue(), StandardCharsets.UTF_8));
        verify(executionEventLog).append(eq("100"), eq("quality/guard"), anyMap());
    }

    @Test
    void shouldKeepH1DormantWhenTitleNotProvided() {
        WriterAgent agent = new WriterAgent();
        inject(agent, "storageProvider", storageProvider);
        inject(agent, "executionEventLog", executionEventLog);

        String result = invokeFlush(agent, "1", "pages/s.md", "# 标题\n\n正文", null, new HashMap<>(), null);

        assertTrue(result.startsWith("# 标题"));
        verify(storageProvider).write(eq("1"), eq("wiki/pages/s.md"), any());
        verifyNoInteractions(executionEventLog);
    }
}
