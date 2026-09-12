package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SchemaPatchDO;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.harness.SchemaPatchModel.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SchemaPatchServiceTest {

    private static final String SCHEMA_WITH_CHANGELOG = String.join("\n",
        "## 1. 领域叙事",
        "内容 A",
        "",
        "## 7. 变更日志",
        "- 2026-09-01 冷启动初版，由 AI 与用户协同生成。",
        "- 2026-09-05 调整分类",
        "",
        "## 8. 附录",
        "尾部");

    private final SchemaPatchService service = new SchemaPatchService();

    private Object invoke(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = SchemaPatchService.class.getDeclaredMethod(name, paramTypes);
        method.setAccessible(true);
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private BusinessException expectBusinessException(String name, Class<?>[] paramTypes, Object... args) {
        try {
            invoke(name, paramTypes, args);
            fail("expected BusinessException from " + name);
            return null;
        } catch (BusinessException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("unexpected exception from " + name, e);
        }
    }

    private static SchemaPatchDO patch(Long id, String operation, String before, String after) {
        SchemaPatchDO p = new SchemaPatchDO();
        p.setId(id);
        p.setScopeId(1L);
        p.setSectionTitle("## 2. 分类与命名");
        p.setOperation(operation);
        p.setDiffBefore(before);
        p.setDiffAfter(after);
        return p;
    }

    @Test
    void replaceSingleReplacesExactlyOneOccurrence() throws Exception {
        String result = (String) invoke("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "alpha beta gamma", "beta", "BETA", "test");
        assertEquals("alpha BETA gamma", result);
    }

    @Test
    void replaceSingleRejectsWhenBeforeMissing() {
        BusinessException ex = expectBusinessException("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "alpha beta", "missing", "x", "test");
        assertEquals(ErrorCode.PATCH_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void replaceSingleRejectsMultipleMatchesToPreventGlobalReplace() {
        BusinessException ex = expectBusinessException("replaceSingle",
            new Class<?>[]{String.class, String.class, String.class, String.class},
            "重复规则\n其他内容\n重复规则", "重复规则", "新规则", "test");
        assertEquals(ErrorCode.PATCH_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsDeleteWithBlankBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "DELETE", "   ", null), Operation.DELETE);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_DELETE.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsDeleteWithNullBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "DELETE", null, null), Operation.DELETE);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_DELETE.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsModifyWithBlankBefore() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "MODIFY", "", "新规则"), Operation.MODIFY);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_MODIFY.getCode(), ex.getCode());
    }

    @Test
    void validateRejectsAddWithNullAfter() {
        BusinessException ex = expectBusinessException("validatePatchFields",
            new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "ADD", null, null), Operation.ADD);
        assertEquals(ErrorCode.PATCH_DIFF_EMPTY_ADD.getCode(), ex.getCode());
    }

    @Test
    void validateAcceptsWellFormedModify() throws Exception {
        invoke("validatePatchFields", new Class<?>[]{SchemaPatchDO.class, Operation.class},
            patch(1L, "MODIFY", "旧规则", "新规则"), Operation.MODIFY);
    }

    @Test
    void extractSection7BodyStopsAtNextHeading() throws Exception {
        String body = (String) invoke("extractSection7Body", new Class<?>[]{String.class}, SCHEMA_WITH_CHANGELOG);
        assertEquals("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。\n- 2026-09-05 调整分类", body);
    }

    @Test
    void extractSection7BodyReturnsEmptyWhenMissing() throws Exception {
        assertEquals("", invoke("extractSection7Body", new Class<?>[]{String.class}, "## 1. 领域叙事\n内容"));
    }

    @Test
    void extractSection7BodyHandlesCrlf() throws Exception {
        String body = (String) invoke("extractSection7Body", new Class<?>[]{String.class},
            "## 7. 变更日志\r\n- 条目一\r\n## 8. 附录\r\n尾巴");
        assertEquals("- 条目一", body);
    }

    @Test
    void appendChangelogKeepsExistingEntriesAndAddsNewOne() throws Exception {
        String body = (String) invoke("appendChangelogEntry", new Class<?>[]{String.class, SchemaPatchDO.class},
            SCHEMA_WITH_CHANGELOG, patch(42L, "ADD", null, "新增规则"));
        assertTrue(body.startsWith("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。\n- 2026-09-05 调整分类"));
        assertTrue(body.endsWith("用户采纳补丁#42：## 2. 分类与命名 ADD"));
    }

    @Test
    void appendChangelogOnSchemaWithoutSection7CreatesEntry() throws Exception {
        String body = (String) invoke("appendChangelogEntry", new Class<?>[]{String.class, SchemaPatchDO.class},
            "## 1. 领域叙事\n内容", patch(7L, "MODIFY", "a", "b"));
        assertTrue(body.contains("用户采纳补丁#7"));
    }

    @Test
    void batchChangelogSummarizesAppliedSections() throws Exception {
        String body = (String) invoke("appendBatchChangelogEntry", new Class<?>[]{String.class, List.class},
            SCHEMA_WITH_CHANGELOG, List.of(patch(9L, "ADD", null, "a"), patch(10L, "MODIFY", "b", "c")));
        assertTrue(body.startsWith("- 2026-09-01 冷启动初版，由 AI 与用户协同生成。"));
        assertTrue(body.endsWith("批量采纳补丁#9 等 2 条：## 2. 分类与命名"));
    }

    @Test
    void conflictsOnlyWhenSameOperationAndOverlappingText() throws Exception {
        Class<?>[] types = new Class<?>[]{SchemaPatchDO.class, SchemaPatchDO.class};
        assertTrue((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则：禁止使用缩写"), patch(2L, "ADD", null, "规则：禁止使用缩写")));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则：禁止使用缩写"), patch(2L, "DELETE", "规则：禁止使用缩写", null)));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(1L, "ADD", null, "规则A：禁止使用缩写"), patch(2L, "ADD", null, "规则B：完全不同的内容")));
        assertFalse((Boolean) invoke("conflictsWith", types,
            patch(3L, "ADD", null, "同一条目"), patch(3L, "ADD", null, "同一条目")));
    }

    @Test
    void conflictDetectionIgnoresWhitespaceDifferences() throws Exception {
        Class<?>[] types = new Class<?>[]{SchemaPatchDO.class, SchemaPatchDO.class};
        assertTrue((Boolean) invoke("conflictsWith", types,
            patch(1L, "MODIFY", "旧 规则", "新 规则"), patch(2L, "MODIFY", "旧\n规则", "新\t规则")));
    }
}
