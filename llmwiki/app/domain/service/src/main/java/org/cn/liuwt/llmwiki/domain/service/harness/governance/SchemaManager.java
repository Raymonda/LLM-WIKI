package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigVersionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaConfigMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaConfigVersionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.ExecutionModel;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaStructuredModel;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.harness.tracker.ExecutionTracker;
import org.cn.liuwt.llmwiki.facade.model.SchemaMigrationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    public static final String SOURCE_BOOTSTRAP = "BOOTSTRAP";
    public static final String SOURCE_PATCH = "PATCH";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_ROLLBACK = "ROLLBACK";

    @Autowired
    private SchemaConfigMapper schemaConfigMapper;

    @Autowired
    private SchemaConfigVersionMapper schemaConfigVersionMapper;

    @Autowired
    private SchemaSkeletonValidator skeletonValidator;

    @Autowired
    @Lazy
    private SchemaInjector schemaInjector;

    @Autowired
    @Lazy
    private SchemaSection6Parser schemaSection6Parser;

    @Autowired
    private SchemaStructuredParser schemaStructuredParser;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    @Lazy
    private ExecutionTracker executionTracker;

    public SchemaConfigDO getSchema(Long scopeId, String configKey) {
        return schemaConfigMapper.selectOne(
            new LambdaQueryWrapper<SchemaConfigDO>()
                .eq(SchemaConfigDO::getScopeId, scopeId)
                .eq(SchemaConfigDO::getConfigKey, configKey)
        );
    }

    public List<Long> listAllScopeIdsWithSchema() {
        List<SchemaConfigDO> rows = schemaConfigMapper.selectList(
            new LambdaQueryWrapper<SchemaConfigDO>()
                .eq(SchemaConfigDO::getConfigKey, SchemaSkeletonValidator.WIKI_SCHEMA_KEY)
                .isNotNull(SchemaConfigDO::getConfigValue)
                .ne(SchemaConfigDO::getConfigValue, "")
                .select(SchemaConfigDO::getScopeId)
        );
        return rows.stream()
            .map(SchemaConfigDO::getScopeId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    }

    public List<SchemaConfigDO> listSchemas(Long scopeId) {
        return schemaConfigMapper.selectList(
            new LambdaQueryWrapper<SchemaConfigDO>()
                .eq(SchemaConfigDO::getScopeId, scopeId)
                .orderByAsc(SchemaConfigDO::getConfigGroup)
                .orderByAsc(SchemaConfigDO::getConfigKey)
        );
    }

    public SchemaConfigDO saveSchema(Long scopeId, String configKey, String configValue, String configGroup, String description) {
        return saveSchema(scopeId, configKey, configValue, configGroup, description, SOURCE_MANUAL, null, null);
    }

    /**
     * 保存 Schema，同时追加一条版本历史。宪法规则 5：历史永不删除，串联 parent_version_id。
     *
     * @param sourceType   BOOTSTRAP / PATCH / MANUAL
     * @param sourceOpId   触发变更的操作 id（patchId 或 executionId），MANUAL 时为 null
     * @param userId       触发变更的用户 id，可为 null
     */
    public SchemaConfigDO saveSchema(Long scopeId, String configKey, String configValue,
                                     String configGroup, String description,
                                     String sourceType, Long sourceOpId, Long userId) {
        return saveSchema(scopeId, configKey, configValue, null, configGroup, description, sourceType, sourceOpId, userId);
    }

    public SchemaConfigDO saveSchema(Long scopeId, String configKey, String configValue,
                                     String configValueStructured,
                                     String configGroup, String description,
                                     String sourceType, Long sourceOpId, Long userId) {
        SchemaSkeletonValidator.ValidationResult result = skeletonValidator.validate(configKey, configValue);
        if (!result.isValid()) {
            throw new BusinessException(ErrorCode.SCHEMA_SKELETON_INVALID, result.getMessage());
        }

        String structuredJson = configValueStructured;
        if (structuredJson == null && SchemaSkeletonValidator.WIKI_SCHEMA_KEY.equals(configKey)) {
            structuredJson = deriveStructuredJson(configValue);
        }

        SchemaConfigVersionDO latest = findLatestVersion(scopeId, configKey);
        SchemaConfigVersionDO version = new SchemaConfigVersionDO();
        version.setScopeId(scopeId);
        version.setConfigKey(configKey);
        version.setConfigValue(configValue);
        version.setConfigValueStructured(structuredJson);
        version.setParentVersionId(latest == null ? null : latest.getId());
        version.setVersionNumber(latest == null ? 1 : latest.getVersionNumber() + 1);
        version.setSourceType(sourceType == null ? SOURCE_MANUAL : sourceType);
        version.setSourceOpId(sourceOpId);
        version.setCreatedBy(userId);
        schemaConfigVersionMapper.insert(version);

        SchemaConfigDO existing = getSchema(scopeId, configKey);
        if (existing != null) {
            existing.setConfigValue(configValue);
            existing.setConfigValueStructured(structuredJson);
            existing.setConfigGroup(configGroup);
            existing.setDescription(description);
            schemaConfigMapper.updateById(existing);
            invalidateInjectorCache(scopeId);
            recordSchemaChangeExecution(scopeId, configKey, version, sourceType, sourceOpId, userId);
            log.info("Schema 已更新并追加版本 scope={} key={} version={} source={} structured={}",
                scopeId, configKey, version.getVersionNumber(), version.getSourceType(),
                structuredJson != null ? "yes" : "no");
            return existing;
        }

        SchemaConfigDO newConfig = new SchemaConfigDO();
        newConfig.setScopeId(scopeId);
        newConfig.setConfigKey(configKey);
        newConfig.setConfigValue(configValue);
        newConfig.setConfigValueStructured(structuredJson);
        newConfig.setConfigGroup(configGroup);
        newConfig.setDescription(description);
        schemaConfigMapper.insert(newConfig);
        invalidateInjectorCache(scopeId);
        recordSchemaChangeExecution(scopeId, configKey, version, sourceType, sourceOpId, userId);
        log.info("Schema 已创建首版 scope={} key={} version=1 source={} structured={}",
            scopeId, configKey, version.getSourceType(), structuredJson != null ? "yes" : "no");
        return newConfig;
    }

    private String deriveStructuredJson(String configValue) {
        try {
            var model = schemaStructuredParser.parse(configValue);
            return schemaStructuredParser.toJson(model);
        } catch (Exception e) {
            log.warn("从 Markdown 派生结构化 JSON 失败，structured 列将为 null: {}", e.getMessage());
            return null;
        }
    }

    public SchemaStructuredModel getStructuredModel(Long scopeId) {
        SchemaConfigDO schema = getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        if (schema == null) return null;

        if (schema.getConfigValueStructured() != null && !schema.getConfigValueStructured().isBlank()) {
            SchemaStructuredModel model = schemaStructuredParser.fromJson(schema.getConfigValueStructured());
            if (model != null) return model;
        }

        if (schema.getConfigValue() != null && !schema.getConfigValue().isBlank()) {
            return schemaStructuredParser.parse(schema.getConfigValue());
        }
        return null;
    }

    public void migrateExistingSchemas() {
        List<Long> scopeIds = listAllScopeIdsWithSchema();
        int migrated = 0;
        for (Long scopeId : scopeIds) {
            SchemaConfigDO schema = getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
            if (schema == null || schema.getConfigValueStructured() != null) continue;
            try {
                String json = deriveStructuredJson(schema.getConfigValue());
                if (json != null) {
                    schema.setConfigValueStructured(json);
                    schemaConfigMapper.updateById(schema);
                    migrated++;
                }
            } catch (Exception e) {
                log.warn("迁移存量 Schema 结构化数据失败 scopeId={}: {}", scopeId, e.getMessage());
            }
        }
        if (migrated > 0) {
            log.info("存量 Schema 结构化迁移完成，共迁移 {} 个 scope", migrated);
        }
    }

    /**
     * 获取指定 scope+configKey 的最新版本记录（即 version_number 最大的那条）。
     * 返回 null 表示该 scope 尚未冷启动。
     */
    public SchemaConfigVersionDO findLatestVersion(Long scopeId, String configKey) {
        return schemaConfigVersionMapper.selectOne(
            new LambdaQueryWrapper<SchemaConfigVersionDO>()
                .eq(SchemaConfigVersionDO::getScopeId, scopeId)
                .eq(SchemaConfigVersionDO::getConfigKey, configKey)
                .orderByDesc(SchemaConfigVersionDO::getVersionNumber)
                .last("LIMIT 1")
        );
    }

    /**
     * Ingest/Lint 写页面时取当前 scope 最新 Schema 版本 id，写入 wiki_page.schema_version 做溯源。
     * 返回 null 表示 scope 未完成冷启动（按宪法规则 4 不应进入 Ingest 流程，此时页面 schema_version 也允许为 null）。
     */
    public Long getCurrentVersionId(Long scopeId, String configKey) {
        SchemaConfigVersionDO latest = findLatestVersion(scopeId, configKey);
        return latest == null ? null : latest.getId();
    }

    public List<SchemaConfigVersionDO> listVersions(Long scopeId, String configKey) {
        return schemaConfigVersionMapper.selectList(
            new LambdaQueryWrapper<SchemaConfigVersionDO>()
                .eq(SchemaConfigVersionDO::getScopeId, scopeId)
                .eq(SchemaConfigVersionDO::getConfigKey, configKey)
                .orderByDesc(SchemaConfigVersionDO::getVersionNumber)
        );
    }

    public SchemaConfigVersionDO getVersion(Long versionId) {
        return schemaConfigVersionMapper.selectById(versionId);
    }

    /**
     * 宪法规则 5：Schema 升级后已有页面不重写，由 Lint 产出"遗留页面迁移报告"。
     * 扫描当前 scope 下所有未打版本戳（schemaVersion IS NULL，多为 V13 之前入库）与
     * 遵循非当前版本的 wiki 页面，返回摘要 + 最多 50 条示例用于 UI 展示。
     */
    public SchemaMigrationReport buildMigrationReport(Long scopeId, String configKey) {
        SchemaMigrationReport report = new SchemaMigrationReport();
        SchemaConfigVersionDO latest = findLatestVersion(scopeId, configKey);
        report.setCurrentVersionId(latest == null ? null : latest.getId());
        report.setCurrentVersionNumber(latest == null ? null : latest.getVersionNumber());

        if (scopeId == null) {
            report.setUntaggedCount(0);
            report.setOutdatedCount(0);
            report.setLegacyPages(List.of());
            return report;
        }

        int untagged = 0;
        int outdated = 0;
        List<WikiPageDO> outdatedList = new ArrayList<>();
        Long currentVersionId = report.getCurrentVersionId();

        List<WikiPageDO> pages = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .orderByDesc(WikiPageDO::getUpdatedAt)
        );
        for (WikiPageDO p : pages) {
            Long v = p.getSchemaVersion();
            if (v == null) {
                untagged++;
                if (outdatedList.size() < 50) outdatedList.add(p);
            } else if (currentVersionId != null && !currentVersionId.equals(v)) {
                outdated++;
                if (outdatedList.size() < 50) outdatedList.add(p);
            }
        }

        Map<Long, Integer> versionNumberCache = new HashMap<>();
        List<SchemaMigrationReport.LegacyPage> legacies = new ArrayList<>(outdatedList.size());
        for (WikiPageDO p : outdatedList) {
            SchemaMigrationReport.LegacyPage lp = new SchemaMigrationReport.LegacyPage();
            lp.setPageId(p.getId());
            lp.setTitle(p.getTitle());
            lp.setCategory(p.getCategory());
            lp.setSchemaVersion(p.getSchemaVersion());
            lp.setVersionNumber(resolveVersionNumber(p.getSchemaVersion(), versionNumberCache));
            lp.setUpdatedAt(p.getUpdatedAt());
            legacies.add(lp);
        }

        report.setUntaggedCount(untagged);
        report.setOutdatedCount(outdated);
        report.setLegacyPages(legacies);
        return report;
    }

    private Integer resolveVersionNumber(Long versionId, Map<Long, Integer> cache) {
        if (versionId == null) return null;
        if (cache.containsKey(versionId)) return cache.get(versionId);
        SchemaConfigVersionDO v = schemaConfigVersionMapper.selectById(versionId);
        Integer n = v == null ? null : v.getVersionNumber();
        cache.put(versionId, n);
        return n;
    }

    private void invalidateInjectorCache(Long scopeId) {
        if (schemaInjector != null) {
            schemaInjector.invalidate(scopeId);
        }
        if (schemaSection6Parser != null) {
            schemaSection6Parser.invalidate(scopeId);
        }
    }

    /**
     * generateFileCache 已移除：文件系统上的 Schema 副本（schema/CLAUDE.md, schema/AGENTS.md）
     * 无任何读取路径，属于死副本。DB schema_config 表是唯一 source of truth。
     * 如需外部工具读取，可通过 API 按需导出。
     */

    private void recordSchemaChangeExecution(Long scopeId, String configKey,
                                              SchemaConfigVersionDO version, String sourceType,
                                              Long sourceOpId, Long userId) {
        if (executionTracker == null) {
            return;
        }
        try {
            String summary = buildSchemaChangeSummary(version, sourceType);
            ExecutionModel exec = executionTracker.createExecution("schema_change", scopeId, null, version.getId());
            executionTracker.updateExecutionStatus(exec.getId(), "running");
            ExecutionModel.ExecutionStepModel step = executionTracker.createStep(
                exec.getId(), "SCHEMA_CHANGE", 1, "auto");
            executionTracker.updateStepStatus(step.getId(), "running");
            executionTracker.completeStep(step.getId(), summary, 0, 0);
            executionTracker.completeExecution(exec.getId(), 0);
        } catch (Exception e) {
            log.warn("记录 Schema 变更执行记录失败 scope={} key={} version={}", scopeId, configKey, version.getVersionNumber(), e);
        }
    }

    private String buildSchemaChangeSummary(SchemaConfigVersionDO version, String sourceType) {
        String sourceLabel = switch (sourceType) {
            case SOURCE_BOOTSTRAP -> "冷启动引导";
            case SOURCE_PATCH -> "AI 补丁应用";
            case SOURCE_ROLLBACK -> "版本回滚";
            default -> "手动变更";
        };
        return String.format("{\"configKey\":\"%s\",\"versionNumber\":%d,\"sourceType\":\"%s\",\"sourceLabel\":\"%s\"}",
            version.getConfigKey(), version.getVersionNumber(), sourceType, sourceLabel);
    }
}
