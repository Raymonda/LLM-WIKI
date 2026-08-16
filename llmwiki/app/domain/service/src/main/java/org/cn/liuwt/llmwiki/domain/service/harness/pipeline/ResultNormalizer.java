package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResultNormalizer implements ToolFilter {

    private static final Logger log = LoggerFactory.getLogger(ResultNormalizer.class);

    @Override
    public int getOrder() {
        return PRE_ORDER_BASE + 400;
    }

    @Override
    public void postFilter(ToolInvocation invocation, ToolResult result) {
        if (result.error() != null) {
            log.warn("Tool result normalized: tool={}, scopeId={}, errorType={}, message={}",
                    invocation.toolName(), invocation.scopeId(),
                    result.error().getClass().getSimpleName(), result.error().getMessage());
        }
    }
}
