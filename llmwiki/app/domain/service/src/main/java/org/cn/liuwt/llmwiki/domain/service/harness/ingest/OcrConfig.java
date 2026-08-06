package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OcrProperties.class, DiagramProperties.class})
public class OcrConfig {
}