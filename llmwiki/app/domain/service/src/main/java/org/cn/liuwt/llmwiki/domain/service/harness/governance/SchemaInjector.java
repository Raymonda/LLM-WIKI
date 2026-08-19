package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.service.harness.LanguageDirective;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaInjector {

    private static final Logger log = LoggerFactory.getLogger(SchemaInjector.class);

    private static final String HEADER_OPEN = "【本知识库 Schema 骨架 —— 所有行为必须遵循】\n";
    private static final String HEADER_CLOSE = "\n【Schema 骨架结束】\n\n---\n\n";

    private static final String FALLBACK_PROMPT = HEADER_OPEN
        + "当前 scope 尚未配置 Schema（未完成冷启动），按保守默认行为执行：\n"
        + "1. 分类体系使用二级结构（领域/主题）\n"
        + "2. 页面必须包含来源引用\n"
        + "3. 发现任何新规则或新结构时，标记为 Schema 候选补丁并提醒用户完成冷启动\n"
        + "4. 不做可能影响 Schema 骨架的激进决策"
        + HEADER_CLOSE;

    private static final Pattern CHANGE_LOG_PATTERN = Pattern.compile(
        "^##\\s*7[.、\\s]*变更日志.*?(?=^##\\s|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    private static final Pattern SECTION_PATTERN = Pattern.compile(
        "(^##\\s*\\d+[.、\\s].*?\\n.*?)(?=^##\\s*\\d+[.、\\s]|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    private static final Pattern SECTION_NUMBER_PATTERN = Pattern.compile("^##\\s*(\\d+)[.、\\s]");

    public enum Section {
        DOMAIN(1), TAXONOMY(2), TEMPLATES(3), NAMING(4), WORKFLOW(5), HEALTH_RULES(6);

        final int number;
        Section(int number) { this.number = number; }
    }

    private static final EnumSet<Section> WRITER_SECTIONS = EnumSet.of(
        Section.DOMAIN, Section.TAXONOMY, Section.TEMPLATES, Section.NAMING);
    private static final EnumSet<Section> ANALYZER_SECTIONS = EnumSet.of(
        Section.DOMAIN, Section.TAXONOMY);
    // 编辑助手只需领域背景、分类体系与命名规范；TEMPLATES 段会被模型误当作正文复制，必须排除
    private static final EnumSet<Section> EDIT_SECTIONS = EnumSet.of(
        Section.DOMAIN, Section.TAXONOMY, Section.NAMING);
    private static final EnumSet<Section> LINT_SECTIONS = EnumSet.of(
        Section.DOMAIN, Section.HEALTH_RULES);
    private static final EnumSet<Section> QUERY_SECTIONS = EnumSet.of(
        Section.DOMAIN, Section.TAXONOMY);
    private static final EnumSet<Section> ALL_SECTIONS = EnumSet.allOf(Section.class);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private LanguageDirective languageDirective;

    @Autowired
    private ScopeService scopeService;

    private final ConcurrentMap<Long, String> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Map<Integer, String>> sectionCache = new ConcurrentHashMap<>();

    public String prepend(Long scopeId, String prompt) {
        if (prompt == null) {
            prompt = "";
        }
        return getLanguagePrefix(scopeId) + buildSchemaHeader(scopeId) + prompt;
    }

    public String prepend(Long scopeId, String prompt, EnumSet<Section> sections) {
        if (prompt == null) {
            prompt = "";
        }
        String langPrefix = getLanguagePrefix(scopeId);
        if (sections == null || sections.size() == ALL_SECTIONS.size()) {
            return langPrefix + buildSchemaHeader(scopeId) + prompt;
        }
        return langPrefix + buildSelectiveHeader(scopeId, sections) + prompt;
    }

    public String prependForWriter(Long scopeId, String prompt) {
        return prepend(scopeId, prompt, WRITER_SECTIONS);
    }

    public String prependForEdit(Long scopeId, String prompt) {
        return prepend(scopeId, prompt, EDIT_SECTIONS);
    }

    public String prependForAnalyzer(Long scopeId, String prompt) {
        return prepend(scopeId, prompt, ANALYZER_SECTIONS);
    }

    public String prependForLint(Long scopeId, String prompt) {
        return prepend(scopeId, prompt, LINT_SECTIONS);
    }

    public String prependForQuery(Long scopeId, String prompt) {
        return prepend(scopeId, prompt, QUERY_SECTIONS);
    }

    private String getLanguagePrefix(Long scopeId) {
        if (scopeId == null) return "";
        try {
            return languageDirective.resolve(scopeService.getLanguage(scopeId));
        } catch (Exception e) {
            return "";
        }
    }

    public String buildSchemaHeader(Long scopeId) {
        if (scopeId == null) {
            return FALLBACK_PROMPT;
        }
        return cache.computeIfAbsent(scopeId, this::loadHeader);
    }

    public void invalidate(Long scopeId) {
        if (scopeId == null) {
            cache.clear();
            sectionCache.clear();
        } else {
            cache.remove(scopeId);
            sectionCache.remove(scopeId);
        }
    }

    private String buildSelectiveHeader(Long scopeId, EnumSet<Section> sections) {
        if (scopeId == null) {
            return FALLBACK_PROMPT;
        }

        Map<Integer, String> sectionMap = sectionCache.computeIfAbsent(scopeId, this::loadSections);
        if (sectionMap.isEmpty()) {
            return FALLBACK_PROMPT;
        }

        StringBuilder sb = new StringBuilder(HEADER_OPEN);
        for (Section section : sections) {
            String content = sectionMap.get(section.number);
            if (content != null) {
                sb.append(content).append("\n\n");
            }
        }
        sb.append(HEADER_CLOSE);
        return sb.toString();
    }

    private String loadHeader(Long scopeId) {
        try {
            SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
            if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
                return FALLBACK_PROMPT;
            }
            String slimSchema = stripChangeLog(schema.getConfigValue().trim());
            return HEADER_OPEN + slimSchema + HEADER_CLOSE;
        } catch (Exception e) {
            log.warn("Load Schema header failed, fallback to default. scopeId={}, err={}", scopeId, e.getMessage());
            return FALLBACK_PROMPT;
        }
    }

    private Map<Integer, String> loadSections(Long scopeId) {
        Map<Integer, String> result = new ConcurrentHashMap<>();
        try {
            SchemaConfigDO schema = schemaManager.getSchema(scopeId, SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
            if (schema == null || schema.getConfigValue() == null || schema.getConfigValue().isBlank()) {
                return result;
            }
            String content = stripChangeLog(schema.getConfigValue().trim());
            Matcher m = SECTION_PATTERN.matcher(content);
            while (m.find()) {
                String sectionText = m.group(1).trim();
                Matcher numMatcher = SECTION_NUMBER_PATTERN.matcher(sectionText);
                if (numMatcher.find()) {
                    int num = Integer.parseInt(numMatcher.group(1));
                    if (num >= 1 && num <= 6) {
                        result.put(num, sectionText);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Load Schema sections failed, scopeId={}, err={}", scopeId, e.getMessage());
        }
        return result;
    }

    private String stripChangeLog(String schemaMarkdown) {
        String stripped = CHANGE_LOG_PATTERN.matcher(schemaMarkdown).replaceAll("");
        stripped = stripped.trim();
        if (stripped.isEmpty()) {
            return schemaMarkdown;
        }
        return stripped;
    }
}