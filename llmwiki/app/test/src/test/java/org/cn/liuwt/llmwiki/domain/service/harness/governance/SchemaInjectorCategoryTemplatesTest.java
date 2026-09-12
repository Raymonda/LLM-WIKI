package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaConfigDO;
import org.cn.liuwt.llmwiki.domain.service.harness.LanguageDirective;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaStructuredParser;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaSkeletonValidator;
import org.cn.liuwt.llmwiki.domain.service.system.ScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaInjectorCategoryTemplatesTest {

    private static final long SCOPE_ID = 1L;

    private static final String SCHEMA_MD = String.join("\n",
        "## 1. 领域定位",
        "",
        "投资研究知识库",
        "",
        "## 2. 分类体系",
        "",
        "### 研究分析",
        "- 策略报告：覆盖策略研究文档",
        "",
        "## 3. 页面模板",
        "",
        "- **模板选择规则**：按分类匹配唯一模板",
        "",
        "### 策略报告",
        "",
        "1. 市场回顾（必需）",
        "2. 来源引用（必需）",
        "",
        "### 读书笔记",
        "",
        "1. 书籍信息（必需）",
        "2. 来源引用（必需）",
        "",
        "### 摘要页",
        "",
        "1. 摘要内容（必需）",
        "2. 来源引用（必需）",
        "",
        "## 4. 命名与引用约定",
        "",
        "- 语言要求：中文",
        "",
        "## 5. 工作流",
        "",
        "- 默认审批：AUTO",
        "",
        "## 6. 健康规则",
        "",
        "- 孤儿检测",
        "",
        "## 7. 变更日志",
        "",
        "- 2026-09-12 测试");

    @Mock
    private SchemaManager schemaManager;

    @Mock
    private LanguageDirective languageDirective;

    @Mock
    private ScopeService scopeService;

    private SchemaInjector newInjector() {
        SchemaInjector injector = new SchemaInjector();
        inject(injector, "schemaManager", schemaManager);
        inject(injector, "languageDirective", languageDirective);
        inject(injector, "scopeService", scopeService);
        return injector;
    }

    private void stubSchema() {
        SchemaConfigDO config = new SchemaConfigDO();
        config.setScopeId(SCOPE_ID);
        config.setConfigKey(SchemaSkeletonValidator.WIKI_SCHEMA_KEY);
        config.setConfigValue(SCHEMA_MD);
        when(schemaManager.getSchema(eq(SCOPE_ID), any(String.class))).thenReturn(config);
        when(languageDirective.resolve(any())).thenReturn("");
    }

    private void stubStructuredModel() {
        when(schemaManager.getStructuredModel(SCOPE_ID))
            .thenReturn(new SchemaStructuredParser().parse(SCHEMA_MD));
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = SchemaInjector.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inject " + field, e);
        }
    }

    @Test
    void shouldKeepOnlyMatchedAndSummaryTemplatesWhenCategoryMatches() {
        stubSchema();
        stubStructuredModel();
        SchemaInjector injector = newInjector();

        String result = injector.prependForWriter(SCOPE_ID, "PROMPT", "研究分析/策略报告");

        assertTrue(result.contains("- **模板选择规则**：按分类匹配唯一模板"));
        assertTrue(result.contains("### 策略报告"));
        assertTrue(result.contains("1. 市场回顾（必需）"));
        assertTrue(result.contains("### 摘要页"));
        assertFalse(result.contains("### 读书笔记"));
        assertTrue(result.contains("## 2. 分类体系"));
        assertTrue(result.contains("## 4. 命名与引用约定"));
        assertTrue(result.endsWith("PROMPT"));
    }

    @Test
    void shouldFallbackToFullTemplatesWhenCategoryDoesNotMatch() {
        stubSchema();
        stubStructuredModel();
        SchemaInjector injector = newInjector();

        String result = injector.prependForWriter(SCOPE_ID, "PROMPT", "基金产品/运作信息");

        assertTrue(result.contains("### 策略报告"));
        assertTrue(result.contains("### 读书笔记"));
        assertTrue(result.contains("### 摘要页"));
    }

    @Test
    void shouldFallbackToFullTemplatesWhenCategoryBlank() {
        stubSchema();
        SchemaInjector injector = newInjector();

        String result = injector.prependForWriter(SCOPE_ID, "PROMPT", "  ");

        assertTrue(result.contains("### 策略报告"));
        assertTrue(result.contains("### 读书笔记"));
    }
}
