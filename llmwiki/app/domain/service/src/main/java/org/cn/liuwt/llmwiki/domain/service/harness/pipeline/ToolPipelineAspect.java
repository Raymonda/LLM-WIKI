package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@ConditionalOnProperty(name = "llmwiki.harness.tool-pipeline.enabled", havingValue = "true")
public class ToolPipelineAspect {

    private final ToolExecutionPipeline pipeline;

    public ToolPipelineAspect(ToolExecutionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Tool tool = method.getAnnotation(Tool.class);
        String toolName = tool != null && !tool.name().isBlank() ? tool.name() : method.getName();
        ToolInvocation invocation = ToolInvocation.from(toolName, method, pjp.getArgs());
        return pipeline.invoke(invocation, pjp::proceed);
    }
}
