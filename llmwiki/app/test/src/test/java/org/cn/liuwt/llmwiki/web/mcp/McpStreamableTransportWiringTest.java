package org.cn.liuwt.llmwiki.web.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpStreamableTransportWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("mcpServerObjectMapper", ObjectMapper.class, ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(McpServerStreamableHttpWebMvcAutoConfiguration.class));

    @Test
    void shouldRegisterStreamableTransportWhenProtocolIsStreamable() {
        runner.withPropertyValues(
                        "spring.ai.mcp.server.protocol=STREAMABLE",
                        "spring.ai.mcp.server.type=SYNC",
                        "spring.ai.mcp.server.name=llmwiki")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(WebMvcStreamableServerTransportProvider.class);
                    assertThat(ctx).hasBean("webMvcStreamableServerRouterFunction");
                });
    }

    @Test
    void shouldNotRegisterStreamableTransportUnderDefaultProtocol() {
        runner.withPropertyValues(
                        "spring.ai.mcp.server.type=SYNC",
                        "spring.ai.mcp.server.name=llmwiki")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WebMvcStreamableServerTransportProvider.class));
    }
}
