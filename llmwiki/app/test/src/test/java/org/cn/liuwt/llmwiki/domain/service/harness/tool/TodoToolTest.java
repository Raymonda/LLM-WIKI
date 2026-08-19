package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.domain.model.harness.TodoStep;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoToolTest {

    private static ToolContext contextOf(String sessionId) {
        return new ToolContext(Map.of("sessionId", sessionId));
    }

    @Test
    void todoWrite_persistsAndRenders() {
        TodoTool tool = new TodoTool();
        String writeResult = tool.todoWrite(
            "[{\"content\":\"搜索合规文档\",\"status\":\"done\"},{\"content\":\"阅读页面\",\"status\":\"in_progress\"}]",
            contextOf("s1"));

        assertTrue(writeResult.contains("2 项"));
        assertTrue(writeResult.contains("搜索合规文档"));

        String readResult = tool.todoRead(contextOf("s1"));
        assertTrue(readResult.contains("1/2"));
        assertTrue(readResult.contains("阅读页面"));
    }

    @Test
    void todoWrite_invalidJson_returnsError() {
        TodoTool tool = new TodoTool();
        String result = tool.todoWrite("不是JSON", contextOf("s1"));
        assertTrue(result.startsWith("错误"));
    }

    @Test
    void todoWrite_invalidStatus_returnsError() {
        TodoTool tool = new TodoTool();
        String result = tool.todoWrite(
            "[{\"content\":\"步骤\",\"status\":\"finished\"}]", contextOf("s1"));
        assertTrue(result.contains("非法 status"));
    }

    @Test
    void todoWrite_blankStatusNormalizesToPending() {
        TodoTool tool = new TodoTool();
        String result = tool.todoWrite(
            "[{\"content\":\"步骤\"}]", contextOf("s1"));
        assertTrue(result.contains("1 项"));
        TodoTool.Progress progress = tool.progressOf("s1");
        assertEquals(0, progress.done());
        assertEquals(1, progress.total());
    }

    @Test
    void todoWrite_missingSessionContext_returnsError() {
        TodoTool tool = new TodoTool();
        String result = tool.todoWrite("[{\"content\":\"步骤\"}]", null);
        assertTrue(result.startsWith("错误"));
    }

    @Test
    void todoRead_noPlan_returnsHint() {
        TodoTool tool = new TodoTool();
        String result = tool.todoRead(contextOf("s-unknown"));
        assertTrue(result.contains("尚无任务清单"));
    }

    @Test
    void progressOf_reportsDoneTotal() {
        TodoTool tool = new TodoTool();
        tool.todoWrite(
            "[{\"content\":\"a\",\"status\":\"done\"},{\"content\":\"b\",\"status\":\"done\"},{\"content\":\"c\"}]",
            contextOf("s2"));

        TodoTool.Progress progress = tool.progressOf("s2");
        assertEquals(2, progress.done());
        assertEquals(3, progress.total());
    }

    @Test
    void progressOf_unknownSession_zero() {
        TodoTool tool = new TodoTool();
        TodoTool.Progress progress = tool.progressOf("nope");
        assertEquals(0, progress.done());
        assertEquals(0, progress.total());
    }

    @Test
    void clear_removesPlan() {
        TodoTool tool = new TodoTool();
        tool.todoWrite("[{\"content\":\"a\"}]", contextOf("s3"));
        tool.clear("s3");
        assertTrue(tool.todoRead(contextOf("s3")).contains("尚无任务清单"));
    }

    @Test
    void todoStep_nullDependenciesNormalized() {
        TodoStep step = assertDoesNotThrow(() -> new TodoStep("内容", null, null));
        assertEquals(List.of(), step.dependencies());
        assertEquals(TodoStep.PENDING, step.status());
    }

    @Test
    void todoStep_blankContentRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TodoStep(" ", TodoStep.PENDING, null));
    }
}
