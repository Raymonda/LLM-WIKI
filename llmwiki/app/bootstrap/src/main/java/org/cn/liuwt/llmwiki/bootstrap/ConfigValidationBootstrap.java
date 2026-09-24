package org.cn.liuwt.llmwiki.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.AiRuntimeConfigService;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigValidationBootstrap implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigValidationBootstrap.class);

    private static final int STORAGE_SAMPLE_SIZE = 20;

    private final Environment environment;
    private final AiRuntimeConfigService aiRuntimeConfigService;
    private final SourceMapper sourceMapper;
    private final StorageProvider storageProvider;
    private final boolean storageConsistencyCheckEnabled;

    public ConfigValidationBootstrap(Environment environment,
                                     AiRuntimeConfigService aiRuntimeConfigService,
                                     SourceMapper sourceMapper,
                                     StorageProvider storageProvider,
                                     @Value("${llmwiki.storage.consistency-check:true}") boolean storageConsistencyCheckEnabled) {
        this.environment = environment;
        this.aiRuntimeConfigService = aiRuntimeConfigService;
        this.sourceMapper = sourceMapper;
        this.storageProvider = storageProvider;
        this.storageConsistencyCheckEnabled = storageConsistencyCheckEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        warnIfNoActiveProfile();
        boolean dbConfigured;
        try {
            dbConfigured = aiRuntimeConfigService.isConfiguredInDb();
        } catch (Exception e) {
            LOGGER.warn("Failed to check DB AI runtime config, treating as not configured", e);
            dbConfigured = false;
        }
        if (!dbConfigured) {
            LOGGER.warn("AI 模型未配置：请在 系统设置 → 通用设置 完成配置（应用正常启动，配置后即时生效）");
        }
        List<String> violations = StartupConfigValidator.validate(
                environment.getProperty("llmwiki.jwt.secret"));
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!violations.isEmpty()) {
            if (prod) {
                throw new IllegalStateException("生产环境关键配置校验失败（误配置大声失败）:\n- " + String.join("\n- ", violations));
            }
            for (String violation : violations) {
                LOGGER.warn("Non-prod config warning: {}", violation);
            }
        }
        checkStorageConsistency();
        LOGGER.info("Startup config validation passed");
    }

    private void warnIfNoActiveProfile() {
        if (environment.getActiveProfiles().length > 0) {
            return;
        }
        LOGGER.warn("");
        LOGGER.warn("================ NO ACTIVE PROFILE ================");
        LOGGER.warn("  未激活任何 Spring profile（--spring.profiles.active=...）。");
        LOGGER.warn("  默认配置面向本机直连（localhost MySQL/ES），且 wiki-data");
        LOGGER.warn("  默认使用相对路径 ./wiki-data（锚定启动目录，易漂移）。");
        LOGGER.warn("  建议: 日常开发 dev，本地脚本启动 local，生产部署 prod。");
        LOGGER.warn("====================================================");
        LOGGER.warn("");
    }

    private void checkStorageConsistency() {
        if (!storageConsistencyCheckEnabled) {
            LOGGER.info("Storage consistency check disabled (llmwiki.storage.consistency-check=false)");
            return;
        }
        long total;
        List<SourceDO> sample;
        try {
            total = sourceMapper.selectCount(null);
            if (total == 0) {
                return;
            }
            sample = sourceMapper.selectList(
                    Wrappers.<SourceDO>lambdaQuery().last("LIMIT " + STORAGE_SAMPLE_SIZE));
        } catch (Exception e) {
            LOGGER.warn("Storage consistency check skipped: cannot query source table ({})", e.getMessage());
            return;
        }
        if (sample.isEmpty()) {
            return;
        }
        int missing = 0;
        int probeErrors = 0;
        String firstMissingAbsPath = null;
        for (SourceDO source : sample) {
            boolean exists;
            try {
                exists = storageProvider.exists(String.valueOf(source.getScopeId()), source.getFilePath());
            } catch (Exception e) {
                probeErrors++;
                LOGGER.warn("Storage consistency probe failed for source {}: {}", source.getId(), e.getMessage());
                continue;
            }
            if (!exists) {
                missing++;
                if (firstMissingAbsPath == null) {
                    try {
                        firstMissingAbsPath = storageProvider.getUrl(String.valueOf(source.getScopeId()), source.getFilePath());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (probeErrors == sample.size()) {
            LOGGER.warn("Storage consistency check skipped: all {} probes failed (storage backend unreachable?)", probeErrors);
            return;
        }
        if (missing * 2 <= sample.size()) {
            if (missing > 0) {
                LOGGER.warn("Storage consistency: {}/{} sampled source files missing (<=50%, tolerated). First missing: {}",
                        missing, sample.size(), firstMissingAbsPath);
            } else {
                LOGGER.info("Storage consistency check passed ({} sources, sampled {})", total, sample.size());
            }
            return;
        }
        LOGGER.warn("");
        LOGGER.warn("================ WIKI-DATA 一致性告警 ================");
        LOGGER.warn("  Wiki 数据存储与数据库记录不一致，疑似 wiki-data 路径漂移或数据损坏。");
        LOGGER.warn("  数据库 source 记录数: {}，抽样 {} 条，其中 {} 条的 raw 文件在当前存储根下不存在。",
                total, sample.size(), missing);
        LOGGER.warn("  首个缺失文件解析到的绝对路径: {}", firstMissingAbsPath);
        LOGGER.warn("  常见原因: 未设置 WIKI_DATA_PATH（默认相对路径 ./wiki-data 锚定启动目录），本次启动目录与写入时不同。");
        LOGGER.warn("  应用将继续启动；受影响来源的重编译/追溯会失败，请核对 WIKI_DATA_PATH 指向。");
        LOGGER.warn("  若确认数据无误或已完成迁移，可用 -Dllmwiki.storage.consistency-check=false 关闭本检查。");
        LOGGER.warn("======================================================");
        LOGGER.warn("");
    }
}
