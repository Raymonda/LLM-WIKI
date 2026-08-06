package org.cn.liuwt.llmwiki.domain.model.harness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaStructuredModel {

    private int schemaFormatVersion = 1;

    private String domainNarrative;

    private Taxonomy taxonomy = new Taxonomy();

    private Templates templates = new Templates();

    private Naming naming = new Naming();

    private Workflow workflow = new Workflow();

    private LintRulesConfig lintRules;

    private List<String> activeCapabilities = new ArrayList<>();

    private List<CapabilityRecord> capabilityHistory = new ArrayList<>();

    @Data
    public static class Taxonomy {
        private String narrative;
        private List<TaxonomyNode> roots = new ArrayList<>();

        public List<String> flattenPaths() {
            List<String> paths = new ArrayList<>();
            for (TaxonomyNode root : roots) {
                flattenNode(root, "", paths);
            }
            return paths;
        }

        public boolean isValidPath(String path) {
            if (path == null || path.isBlank()) return false;
            String normalized = path.trim().toLowerCase();
            List<String> allPaths = flattenPaths();
            for (String p : allPaths) {
                if (p.equalsIgnoreCase(normalized)) return true;
                if (normalized.startsWith(p + "/") || p.startsWith(normalized + "/")) return true;
            }
            return false;
        }

        private void flattenNode(TaxonomyNode node, String parentPath, List<String> paths) {
            String currentPath = parentPath.isEmpty() ? node.getId() : parentPath + "/" + node.getId();
            paths.add(currentPath);
            if (node.getChildren() != null) {
                for (TaxonomyNode child : node.getChildren()) {
                    flattenNode(child, currentPath, paths);
                }
            }
        }
    }

    @Data
    public static class TaxonomyNode {
        private String id;
        private String label;
        private String description;
        private List<TaxonomyNode> children;
    }

    @Data
    public static class Templates {
        private String narrative;
        private List<PageTemplate> pageTemplates = new ArrayList<>();

        public PageTemplate findByType(String type) {
            if (type == null) return null;
            for (PageTemplate t : pageTemplates) {
                if (type.equalsIgnoreCase(t.getType())) return t;
            }
            return null;
        }
    }

    @Data
    public static class PageTemplate {
        private String type;
        private String label;
        private List<SectionDef> sections = new ArrayList<>();

        public List<String> requiredSectionLabels() {
            List<String> labels = new ArrayList<>();
            for (SectionDef s : sections) {
                if (s.isRequired()) labels.add(s.getLabel());
            }
            return labels;
        }
    }

    @Data
    public static class SectionDef {
        private String id;
        private String label;
        private boolean required = true;
        private int order;
    }

    @Data
    public static class Naming {
        private String narrative;
        private NamingRule entity = new NamingRule();
        private NamingRule summary = new NamingRule();
        private List<String> examples = new ArrayList<>();
    }

    @Data
    public static class NamingRule {
        private String language;
        private int maxLength;
        private String pattern;
        private List<String> forbiddenPrefixes = new ArrayList<>();

        public NamingValidationResult validate(String name) {
            if (name == null || name.isBlank()) {
                return NamingValidationResult.fail("名称不能为空");
            }
            if (maxLength > 0 && name.length() > maxLength) {
                return NamingValidationResult.fail("名称「" + name + "」长度(" + name.length() + ")超过限制(" + maxLength + ")");
            }
            if ("zh-CN".equals(language) && name.matches("^[a-zA-Z0-9\\s._-]+$")) {
                return NamingValidationResult.fail("名称「" + name + "」不符合中文命名要求");
            }
            if (pattern != null && !pattern.isBlank()) {
                try {
                    if (!name.matches(pattern)) {
                        return NamingValidationResult.fail("名称「" + name + "」不符合命名模式：" + pattern);
                    }
                } catch (Exception ignored) {
                }
            }
            for (String prefix : forbiddenPrefixes) {
                if (name.startsWith(prefix)) {
                    return NamingValidationResult.fail("名称「" + name + "」不允许以「" + prefix + "」开头");
                }
            }
            return NamingValidationResult.ok();
        }
    }

    @Data
    public static class NamingValidationResult {
        private boolean valid;
        private String message;

        public static NamingValidationResult ok() {
            NamingValidationResult r = new NamingValidationResult();
            r.valid = true;
            return r;
        }

        public static NamingValidationResult fail(String message) {
            NamingValidationResult r = new NamingValidationResult();
            r.valid = false;
            r.message = message;
            return r;
        }
    }

    @Data
    public static class Workflow {
        private String narrative;
        private String defaultApproval = "AUTO";
        private List<String> confirmTriggers = new ArrayList<>();
    }

    @Data
    public static class CapabilityMeta {
        private String id;
        private String label;
        private String description;
        private String icon;
        private Set<Integer> affectedSections;
        private List<String> tags = new ArrayList<>();
        private List<String> sampleDocTypes = new ArrayList<>();
        private int fusionPriority;
    }

    @Data
    public static class CapabilityRecord {
        private String capability;
        private String activatedAt;
        private String source;
    }
}
