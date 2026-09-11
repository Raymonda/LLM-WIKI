package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.harness.conflict.ConflictDomainService;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.parser.SchemaSection6Parser;
import org.cn.liuwt.llmwiki.service.lint.LintService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LintRulingBriefServiceTest {

    @Mock
    private ConflictDomainService conflictDomainService;

    @Mock
    private SchemaSection6Parser schemaSection6Parser;

    @Mock
    private LintFindingService lintFindingService;

    @Mock
    private WikiPageMapper wikiPageMapper;

    private final LintRulesConfig rulesConfig = new LintRulesConfig();

    private LintService newService() {
        LintService service = new LintService();
        inject(service, "conflictDomainService", conflictDomainService);
        inject(service, "schemaSection6Parser", schemaSection6Parser);
        inject(service, "lintFindingService", lintFindingService);
        inject(service, "wikiPageMapper", wikiPageMapper);
        return service;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = LintService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            fail("Failed to inject " + field + ": " + e.getMessage());
        }
    }

    @Test
    void shouldThrowLintFindingNotFoundWhenOutcomeNotFound() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.notFound(31L));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.generateRulingBrief(1L, 31L));

        assertEquals("LINT_005", ex.getCode());
    }

    @Test
    void shouldThrowAiUnavailableWhenOutcomeAiUnavailable() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.aiUnavailable(31L));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.generateRulingBrief(1L, 31L));

        assertEquals("CONFLICT_005", ex.getCode());
    }

    @Test
    void shouldThrowBusyWhenOutcomeBusy() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.busy(31L));

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.generateRulingBrief(1L, 31L));

        assertEquals("CONFLICT_006", ex.getCode());
    }

    @Test
    void shouldThrowInternalErrorWhenJsonExtractOrGenerationFails() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.jsonExtractFailed(31L))
            .thenReturn(ConflictDomainService.RulingResult.generationFailed(31L, "boom"));

        BusinessException first = assertThrows(BusinessException.class,
            () -> service.generateRulingBrief(1L, 31L));
        BusinessException second = assertThrows(BusinessException.class,
            () -> service.generateRulingBrief(1L, 31L));

        assertEquals("SYS_001", first.getCode());
        assertEquals("SYS_001", second.getCode());
    }

    @Test
    void shouldReturnDeferredStatusWithReasonWhenOutcomeDeferred() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.deferred(31L, "策略要求人工裁决"));

        Map<String, Object> response = service.generateRulingBrief(1L, 31L);

        assertEquals("deferred", response.get("status"));
        assertEquals("策略要求人工裁决", response.get("reason"));
    }

    @Test
    void shouldReturnGeneratedStatusWhenOutcomeGenerated() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.generated(31L));

        Map<String, Object> response = service.generateRulingBrief(1L, 31L);

        assertEquals("generated", response.get("status"));
        verify(conflictDomainService).generateRulingBriefForFinding(1L, null, 31L, rulesConfig);
    }

    @Test
    void shouldReturnAlreadyGeneratedStatusWhenOutcomeAlreadyGenerated() {
        LintService service = newService();
        when(schemaSection6Parser.parse(1L)).thenReturn(rulesConfig);
        when(conflictDomainService.generateRulingBriefForFinding(1L, null, 31L, rulesConfig))
            .thenReturn(ConflictDomainService.RulingResult.alreadyGenerated(31L));

        Map<String, Object> response = service.generateRulingBrief(1L, 31L);

        assertEquals("already_generated", response.get("status"));
    }

    @Test
    void shouldFilterPageConflictsByIdentityAndPath() {
        LintService service = newService();
        WikiPageDO page = new WikiPageDO();
        page.setId(11L);
        page.setFilePath("entities/x.md");
        when(wikiPageMapper.selectById(11L)).thenReturn(page);
        when(lintFindingService.listFindings(1L, "conflict", null, null)).thenReturn(List.of(
            conflictFinding(101L, 11L, "entities/x.md", null),
            conflictFinding(102L, null, "entities/y.md", "{\"relatedPageId\":11}"),
            conflictFinding(103L, null, "entities/x.md", "{}"),
            conflictFinding(104L, 99L, "entities/other.md", "{\"relatedPageId\":98,\"fromPageId\":99}")
        ));

        List<LintFindingDO> matched = service.listPageConflicts(1L, 11L);

        assertEquals(List.of(101L, 102L, 103L),
            matched.stream().map(LintFindingDO::getId).toList());
    }

    private static LintFindingDO conflictFinding(Long id, Long assetId, String pagePath, String extra) {
        LintFindingDO finding = new LintFindingDO();
        finding.setId(id);
        finding.setScopeId(1L);
        finding.setFindingType("conflict");
        finding.setStatus("open");
        finding.setAssetId(assetId);
        finding.setPagePath(pagePath);
        finding.setExtra(extra);
        return finding;
    }
}
