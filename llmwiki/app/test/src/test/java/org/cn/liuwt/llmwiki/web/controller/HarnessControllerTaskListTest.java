package org.cn.liuwt.llmwiki.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessControllerTaskListTest {

    private final HarnessController controller = new HarnessController();

    @Test
    void shouldExpandActiveAliasToThreeRunningStatuses() {
        List<String> statuses = ReflectionTestUtils.invokeMethod(controller, "parseStatusFilter", "active");

        assertEquals(List.of("pending", "running", "paused"), statuses);
    }

    @Test
    void shouldSplitCommaSeparatedStatusValues() {
        List<String> statuses = ReflectionTestUtils.invokeMethod(controller, "parseStatusFilter", "failed, cancelled");

        assertEquals(List.of("failed", "cancelled"), statuses);
    }

    @Test
    void shouldReturnNullWhenStatusFilterBlank() {
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseStatusFilter", " "));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parseStatusFilter", (String) null));
    }

    @Test
    void shouldExtractTitleFromPayloadJson() {
        assertEquals("九坤持仓分析", ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle",
            "{\"title\":\"九坤持仓分析\",\"question\":\"Q\"}"));
    }

    @Test
    void shouldTruncateLongPayloadTitle() {
        String title = "长".repeat(100);

        String result = ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle",
            "{\"title\":\"" + title + "\"}");

        assertEquals(83, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void shouldReturnNullWhenPayloadTitleAbsentOrInvalid() {
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle", "not-json"));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle", "{}"));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle", "{\"question\":\"Q\"}"));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "parsePayloadTitle", (String) null));
    }
}
