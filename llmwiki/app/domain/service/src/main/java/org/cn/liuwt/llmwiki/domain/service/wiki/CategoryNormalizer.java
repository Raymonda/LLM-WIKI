package org.cn.liuwt.llmwiki.domain.service.wiki;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SchemaConfigMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 分类归一化器：将 LLM 输出的分类（可能含英文、中英混合、自创分类）归一化为规范中文分类。
 *
 * 归一化策略：
 * 1. 优先匹配 Schema Section 2 定义的有效分类
 * 2. 匹配当前 scope 已有的高频分类（知识库内一致性）
 * 3. 英文→中文映射表兜底
 * 4. 强制转中文小写，去除空白噪音
 */
@Component
public class CategoryNormalizer {

    private static final Logger log = LoggerFactory.getLogger(CategoryNormalizer.class);

    private static final Pattern ENGLISH_CHARS = Pattern.compile("[a-zA-Z]");
    private static final Pattern CHINESE_CHARS = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern SECTION_PATTERN = Pattern.compile(
        "^##\\s*2[.、\\s].*?\\n(.*?)(?=^##\\s|\\Z)",
        Pattern.MULTILINE | Pattern.DOTALL
    );

    /**
     * 常见英文/混合分类 → 中文映射表
     */
    private static final Map<String, String> EN_TO_ZH_MAP = buildEnglishToChineseMap();

    @Autowired
    private SchemaConfigMapper schemaConfigMapper;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    /**
     * 归一化分类。
     *
     * @param scopeId  当前 scope
     * @param category LLM 输出的原始分类（可能为 null）
     * @return 归一化后的分类字符串
     */
    public String normalize(Long scopeId, String category) {
        if (category == null || category.isBlank()) {
            return "未分类";
        }

        String trimmed = category.trim();

        if (!containsEnglish(trimmed)) {
            return sanitizeChinese(trimmed);
        }

        Set<String> schemaCategories = extractSchemaCategories(scopeId);
        Set<String> existingCategories = extractExistingCategories(scopeId);

        String lower = trimmed.toLowerCase();
        if (EN_TO_ZH_MAP.containsKey(lower)) {
            String mapped = EN_TO_ZH_MAP.get(lower);
            String schemaMatch = findBestSchemaMatch(mapped, schemaCategories);
            return schemaMatch != null ? schemaMatch : mapped;
        }

        String partsMapped = mapPartsToChinese(trimmed);
        String schemaMatch = findBestSchemaMatch(partsMapped, schemaCategories);
        if (schemaMatch != null) return schemaMatch;

        String existingMatch = findBestSchemaMatch(partsMapped, existingCategories);
        if (existingMatch != null) return existingMatch;

        return sanitizeChinese(partsMapped);
    }

    /**
     * 批量归一化 metadataJson 中的 category 字段。
     * 直接修改传入的 JSON 字符串并返回修正后的版本。
     */
    public String normalizeInMetadataJson(Long scopeId, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return metadataJson;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(metadataJson);
            if (!root.has("category") || !root.get("category").isTextual()) return metadataJson;

            String originalCategory = root.get("category").asText();
            String normalized = normalize(scopeId, originalCategory);

            if (normalized.equals(originalCategory)) return metadataJson;

            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("category", normalized);
            log.info("Category normalized: '{}' -> '{}' (scopeId={})", originalCategory, normalized, scopeId);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.debug("normalizeInMetadataJson failed: {}", e.getMessage());
            return metadataJson;
        }
    }

    private boolean containsEnglish(String text) {
        return ENGLISH_CHARS.matcher(text).find();
    }

    private boolean containsChinese(String text) {
        return CHINESE_CHARS.matcher(text).find();
    }

    /**
     * 对分类路径的各段做英文→中文映射。
     * 例如 "技术/Architecture" → "技术/架构设计"
     */
    private String mapPartsToChinese(String category) {
        String[] parts = category.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("/");
            String part = parts[i].trim();
            String lowerPart = part.toLowerCase();
            if (EN_TO_ZH_MAP.containsKey(lowerPart)) {
                sb.append(EN_TO_ZH_MAP.get(lowerPart));
            } else if (!containsChinese(part) && containsEnglish(part)) {
                sb.append(part);
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * 从 Schema 的有效分类中找最佳匹配。
     * 匹配策略：精确匹配 > 一级分类匹配 > 子串相似度
     */
    private String findBestSchemaMatch(String category, Set<String> validCategories) {
        if (validCategories == null || validCategories.isEmpty()) return null;

        String normalized = sanitizeChinese(category);
        String normalizedLower = normalized.toLowerCase();

        for (String valid : validCategories) {
            if (valid.toLowerCase().equals(normalizedLower)) {
                return valid;
            }
        }

        String[] inputParts = normalizedLower.split("/");
        for (String valid : validCategories) {
            String[] validParts = valid.toLowerCase().split("/");
            if (inputParts.length > 0 && validParts.length > 0
                && inputParts[0].equals(validParts[0])) {
                return valid;
            }
        }

        for (String valid : validCategories) {
            if (normalizedLower.contains(valid.toLowerCase())
                || valid.toLowerCase().contains(normalizedLower)) {
                return valid;
            }
        }

        return null;
    }

    private Set<String> extractSchemaCategories(Long scopeId) {
        try {
            SchemaConfigDO schema = schemaConfigMapper.selectOne(
                new LambdaQueryWrapper<SchemaConfigDO>()
                    .eq(SchemaConfigDO::getScopeId, scopeId)
                    .eq(SchemaConfigDO::getConfigKey, SchemaSkeletonValidator.WIKI_SCHEMA_KEY)
            );
            if (schema == null || schema.getConfigValue() == null) return Set.of();

            Matcher m = SECTION_PATTERN.matcher(schema.getConfigValue());
            if (!m.find()) return Set.of();

            String sectionContent = m.group(1);
            return parseCategoriesFromSection(sectionContent);
        } catch (Exception e) {
            log.debug("extractSchemaCategories failed for scopeId={}: {}", scopeId, e.getMessage());
            return Set.of();
        }
    }

    private Set<String> extractExistingCategories(Long scopeId) {
        try {
            List<WikiPageDO> pages = wikiPageMapper.selectList(
                new LambdaQueryWrapper<WikiPageDO>()
                    .select(WikiPageDO::getCategory)
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .isNotNull(WikiPageDO::getCategory)
                    .ne(WikiPageDO::getCategory, "")
            );
            return pages.stream()
                .map(p -> sanitizeChinese(p.getCategory()))
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("extractExistingCategories failed for scopeId={}: {}", scopeId, e.getMessage());
            return Set.of();
        }
    }

    private Set<String> parseCategoriesFromSection(String sectionContent) {
        Set<String> categories = new LinkedHashSet<>();
        String[] lines = sectionContent.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                String item = trimmed.substring(2).trim();
                categories.add(sanitizeChinese(item));
                continue;
            }

            if (trimmed.startsWith("### ")) {
                String heading = trimmed.substring(4).trim();
                categories.add(sanitizeChinese(heading));
                continue;
            }

            int slashIdx = trimmed.indexOf("/");
            if (slashIdx > 0 && trimmed.length() < 60) {
                categories.add(sanitizeChinese(trimmed));
            }
        }

        return categories;
    }

    private String sanitizeChinese(String category) {
        if (category == null) return "";
        return category.trim()
            .replaceAll("\\s+/\\s+", "/")
            .replaceAll("\\s+", "")
            .replaceAll("^/+", "")
            .replaceAll("/+$", "");
    }

    private static Map<String, String> buildEnglishToChineseMap() {
        Map<String, String> map = new LinkedHashMap<>();

        map.put("architecture", "架构设计");
        map.put("architecture design", "架构设计");
        map.put("design", "架构设计");
        map.put("technology", "技术实践");
        map.put("tech", "技术实践");
        map.put("technical", "技术实践");
        map.put("engineering", "工程实践");
        map.put("devops", "运维实践");
        map.put("operations", "运维实践");
        map.put("infrastructure", "基础设施");

        map.put("business", "业务领域");
        map.put("domain", "业务领域");
        map.put("governance", "企业治理");
        map.put("corporate governance", "企业治理");
        map.put("compliance", "合规管理");
        map.put("risk", "风险管理");
        map.put("risk management", "风险管理");
        map.put("audit", "审计管理");

        map.put("organization", "组织管理");
        map.put("org", "组织管理");
        map.put("department", "部门职能");
        map.put("hr", "人力资源");
        map.put("human resources", "人力资源");
        map.put("finance", "财务管理");
        map.put("management", "管理实践");

        map.put("methodology", "方法论");
        map.put("agile", "敏捷开发");
        map.put("project management", "项目管理");
        map.put("process", "流程管理");
        map.put("workflow", "流程管理");

        map.put("product", "产品设计");
        map.put("ux", "用户体验");
        map.put("ui", "用户体验");
        map.put("user experience", "用户体验");

        map.put("security", "安全管理");
        map.put("data", "数据管理");
        map.put("database", "数据管理");
        map.put("ai", "人工智能");
        map.put("machine learning", "人工智能");
        map.put("ml", "人工智能");
        map.put("testing", "质量保障");
        map.put("qa", "质量保障");
        map.put("quality", "质量保障");

        map.put("overview", "系统介绍");
        map.put("introduction", "系统介绍");
        map.put("guide", "操作指南");
        map.put("tutorial", "操作指南");
        map.put("reference", "参考资料");

        map.put("summary", "资料摘要");
        map.put("analysis", "分析研究");
        map.put("research", "分析研究");
        map.put("report", "研究报告");

        return Collections.unmodifiableMap(map);
    }
}
