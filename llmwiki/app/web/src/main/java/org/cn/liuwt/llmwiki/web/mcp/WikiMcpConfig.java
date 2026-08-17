package org.cn.liuwt.llmwiki.web.mcp;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 WikiMcpTools 的 @Tool 方法挂到 spring-ai MCP server（streamable-http /mcp）。
 */
@Configuration
public class WikiMcpConfig {

    @Bean
    public MethodToolCallbackProvider wikiMcpToolProvider(WikiMcpTools wikiMcpTools) {
        return MethodToolCallbackProvider.builder().toolObjects(wikiMcpTools).build();
    }
}
