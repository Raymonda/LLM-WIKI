package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.tool.ReadFileTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.ReadRawSourceTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.GetSourceInfoTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.SearchWikiTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.ListPagesTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.GetRelatedPagesTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.WriteFileTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.UpdateLinksTool;
import org.cn.liuwt.llmwiki.domain.service.harness.tool.TodoTool;
import org.cn.liuwt.llmwiki.integration.ai.AiConfigChangedEvent;
import org.cn.liuwt.llmwiki.integration.ai.AiRuntimeConfig;
import org.cn.liuwt.llmwiki.integration.ai.AiSlotRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunnerTest {

    private static final String[] QUERY_CLIENT_FIELDS = {
        "queryReadOnlyClient", "queryReadWriteClient", "noToolsClient",
        "deepQueryReadOnlyClient", "deepQueryReadWriteClient", "deepNoToolsClient"
    };

    @Test
    void shouldClearClientsWhenMainModelMissing() throws Exception {
        AgentRunner runner = newRunner(mock(AiSlotRouter.class));
        runner.init();
        for (String field : QUERY_CLIENT_FIELDS) {
            assertNull(getField(runner, field), field);
        }
    }

    @Test
    void shouldRebuildClientsOnAiConfigChanged() throws Exception {
        AiSlotRouter slotRouter = mock(AiSlotRouter.class);
        AgentRunner runner = newRunner(slotRouter);
        runner.init();
        assertNull(getField(runner, "queryReadOnlyClient"));

        when(slotRouter.getModel("main")).thenReturn(mock(ChatModel.class));
        runner.onAiConfigChanged(new AiConfigChangedEvent(AiRuntimeConfig.empty(), "test"));

        assertNotNull(getField(runner, "queryReadOnlyClient"));
        assertNotNull(getField(runner, "queryReadWriteClient"));
        assertNotNull(getField(runner, "noToolsClient"));
    }

    private AgentRunner newRunner(AiSlotRouter slotRouter) throws Exception {
        AgentRunner runner = new AgentRunner();
        inject(runner, "slotRouter", slotRouter);
        inject(runner, "readFileTool", mock(ReadFileTool.class));
        inject(runner, "readRawSourceTool", mock(ReadRawSourceTool.class));
        inject(runner, "getSourceInfoTool", mock(GetSourceInfoTool.class));
        inject(runner, "searchWikiTool", mock(SearchWikiTool.class));
        inject(runner, "listPagesTool", mock(ListPagesTool.class));
        inject(runner, "getRelatedPagesTool", mock(GetRelatedPagesTool.class));
        inject(runner, "writeFileTool", mock(WriteFileTool.class));
        inject(runner, "updateLinksTool", mock(UpdateLinksTool.class));
        inject(runner, "todoTool", mock(TodoTool.class));
        return runner;
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = AgentRunner.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String field) throws Exception {
        Field f = AgentRunner.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
