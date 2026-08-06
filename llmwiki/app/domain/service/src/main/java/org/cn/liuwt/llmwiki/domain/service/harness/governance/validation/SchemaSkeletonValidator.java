package org.cn.liuwt.llmwiki.domain.service.harness.governance.validation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Schema 7 段固定骨架校验器。
 * 对应 AGENTS.md《Schema 共治宪法·规则 1》：section 1-6 定义规则，section 7 为 append-only 变更日志。
 * 破坏骨架的写入一律 reject。
 */
@Component
public class SchemaSkeletonValidator {

    public static final String WIKI_SCHEMA_KEY = "wiki_schema";

    /**
     * 7 段必需的 H2 标题（按顺序）。名称前缀"## 1." ~ "## 7."是硬性要求。
     */
    public static final List<String> REQUIRED_SECTIONS = Arrays.asList(
        "## 1. 领域定位",
        "## 2. 分类体系",
        "## 3. 页面模板",
        "## 4. 命名与引用约定",
        "## 5. 摄入工作流",
        "## 6. 健康检查规则",
        "## 7. 变更日志"
    );

    public ValidationResult validate(String configKey, String configValue) {
        if (!WIKI_SCHEMA_KEY.equals(configKey)) {
            return ValidationResult.ok();
        }
        if (configValue == null || configValue.isBlank()) {
            return ValidationResult.fail("Schema 内容为空");
        }
        List<String> h2Lines = extractH2Headings(configValue);
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED_SECTIONS) {
            if (!h2Lines.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            return ValidationResult.fail("缺少必需的 Schema section：" + String.join("、", missing));
        }
        int prevIdx = -1;
        for (String required : REQUIRED_SECTIONS) {
            int idx = h2Lines.indexOf(required);
            if (idx <= prevIdx) {
                return ValidationResult.fail("Schema section 顺序错误，必须依次为 1~7：" + required);
            }
            prevIdx = idx;
        }
        return ValidationResult.ok();
    }

    private List<String> extractH2Headings(String markdown) {
        List<String> result = new ArrayList<>();
        for (String raw : markdown.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("## ") && !line.startsWith("### ")) {
                result.add(line);
            }
        }
        return result;
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
