package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.cn.liuwt.llmwiki.domain.service.harness.eventlog.TestEventLogs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPipelineAspectTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void pipelineOn_traversalPath_rejectedByGuard() {
        context = context("true");
        SampleTool tool = context.getBean(SampleTool.class);

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> tool.readPage("1", "wiki/../../etc/passwd"));

        assertTrue(ex.getMessage().contains("ScopePathGuard"));
    }

    @Test
    void pipelineOn_writeToRaw_rejectedByGuard() {
        context = context("true");
        SampleTool tool = context.getBean(SampleTool.class);

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> tool.writePage("1", "raw/source.pdf", "x"));

        assertTrue(ex.getMessage().contains("raw"));
    }

    @Test
    void pipelineOn_normalCall_sameResultAsOff() {
        context = context("true");
        SampleTool tool = context.getBean(SampleTool.class);

        assertEquals("content-1-wiki/pages/a.md", tool.readPage("1", "wiki/pages/a.md"));
        assertTrue(tool.writePage("1", "wiki/pages/a.md", "hello"));
    }

    @Test
    void pipelineOff_traversalPath_notIntercepted() {
        context = context("false");
        SampleTool tool = context.getBean(SampleTool.class);

        assertEquals("content-1-wiki/../../etc/passwd", tool.readPage("1", "wiki/../../etc/passwd"));
    }

    @Test
    void pipelineOff_aspectBeanNotRegistered() {
        context = context("false");

        assertEquals(0, context.getBeansOfType(ToolPipelineAspect.class).size());
    }

    private static AnnotationConfigApplicationContext context(String enabled) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("llmwiki.harness.tool-pipeline.enabled", enabled)));
        ctx.register(AspectEnableConfig.class, ToolPipelineProperties.class, ToolExecutionPipeline.class,
                ApprovalFilter.class, ScopePathGuardFilter.class, ConcurrencyGuardFilter.class,
                TimeoutGuard.class, MetricsCollector.class, ResultNormalizer.class,
                ToolPipelineAspect.class, SampleTool.class, DisabledEventLogConfig.class);
        ctx.refresh();
        return ctx;
    }

    @org.springframework.context.annotation.Configuration
    static class DisabledEventLogConfig {

        @org.springframework.context.annotation.Bean
        org.cn.liuwt.llmwiki.domain.service.harness.eventlog.ExecutionEventLogService executionEventLogService() {
            return TestEventLogs.disabled();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy
    static class AspectEnableConfig {
    }

    @org.springframework.stereotype.Component
    static class SampleTool {

        @Tool(description = "测试读取页面")
        public String readPage(
                @ToolParam(description = "知识库范围 ID") String scopeId,
                @ToolParam(description = "文件路径") String path) {
            return "content-" + scopeId + "-" + path;
        }

        @Tool(description = "测试写入页面")
        public boolean writePage(
                @ToolParam(description = "知识库范围 ID") String scopeId,
                @ToolParam(description = "文件路径") String path,
                @ToolParam(description = "内容") String content) {
            return true;
        }
    }
}
