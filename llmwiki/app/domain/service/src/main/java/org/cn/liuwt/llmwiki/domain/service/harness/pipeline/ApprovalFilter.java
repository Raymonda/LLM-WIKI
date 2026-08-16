package org.cn.liuwt.llmwiki.domain.service.harness.pipeline;

import org.springframework.stereotype.Component;

@Component
public class ApprovalFilter implements ToolFilter {

    @Override
    public int getOrder() {
        return 50;
    }
}
