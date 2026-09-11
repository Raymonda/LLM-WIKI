package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.IngestContext;
import org.cn.liuwt.llmwiki.domain.service.harness.ingest.WriterAgent;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriterAgentSourceLockTest {

    @Mock
    private WikiPageMapper wikiPageMapper;

    @Mock
    private StorageProvider storageProvider;

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = WriterAgent.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    private static WikiPageDO page(String pageType) {
        WikiPageDO p = new WikiPageDO();
        p.setId(10L);
        p.setScopeId(1L);
        p.setFilePath("wiki/pages/s.md");
        p.setTitle("某来源页");
        p.setPageType(pageType);
        p.setLifecycleStatus("ACTIVE");
        return p;
    }

    private static WikiPageDO invokeUpdate(WriterAgent agent, String affectedPath) {
        try {
            java.lang.reflect.Method m = WriterAgent.class.getDeclaredMethod("updateRelatedPage",
                Long.class, Long.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class,
                Map.class, List.class, IngestContext.class);
            m.setAccessible(true);
            return (WikiPageDO) m.invoke(agent, 1L, 2L, "1", affectedPath, "更新",
                "新来源内容", "分析结果", "{}", null, new HashMap<String, String>(), List.of(), null);
        } catch (Exception e) {
            throw new IllegalStateException("updateRelatedPage failed", e);
        }
    }

    private WriterAgent agentLockedOn(String pageType) {
        WriterAgent agent = new WriterAgent();
        inject(agent, "wikiPageMapper", wikiPageMapper);
        inject(agent, "storageProvider", storageProvider);
        when(wikiPageMapper.selectOne(any())).thenReturn(page(pageType));
        return agent;
    }

    @Test
    void shouldSkipUpdateWhenTargetIsSummarySourcePage() {
        WriterAgent agent = agentLockedOn("summary");

        WikiPageDO result = invokeUpdate(agent, "pages/s.md");

        assertNull(result, "cross-source update of a summary page must be skipped");
        verifyNoInteractions(storageProvider);
    }

    @Test
    void shouldSkipUpdateWhenTargetIsReferenceSourcePage() {
        WriterAgent agent = agentLockedOn("reference");

        WikiPageDO result = invokeUpdate(agent, "pages/s.md");

        assertNull(result);
        verifyNoInteractions(storageProvider);
    }
}
