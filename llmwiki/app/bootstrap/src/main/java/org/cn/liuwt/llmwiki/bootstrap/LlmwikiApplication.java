package org.cn.liuwt.llmwiki.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    exclude = {org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class},
    scanBasePackages = "org.cn.liuwt.llmwiki"
)
@MapperScan("org.cn.liuwt.llmwiki.common.dal.mapper")
@EnableElasticsearchRepositories(basePackages = "org.cn.liuwt.llmwiki.integration.search")
@EnableScheduling
@EnableAsync
public class LlmwikiApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmwikiApplication.class);

    public static void main(String[] args) {
        try {
            SpringApplication.run(LlmwikiApplication.class, args);
            LOGGER.info("LLM Wiki Application Started");
        } catch (Throwable e) {
            LOGGER.error("LLM Wiki Application Start Failed", e);
            throw e;
        }
    }
}