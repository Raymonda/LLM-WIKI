package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.SchemaViolation;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.Severity;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ViolationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestOrchestratorSchemaPrecheckTest {

    @Mock
    private SchemaComplianceChecker schemaComplianceChecker;

    @InjectMocks
    private IngestOrchestrator orchestrator;

    private boolean precheck(Long scopeId, IngestContext context) throws Exception {
        Method method = IngestOrchestrator.class.getDeclaredMethod(
            "schemaPrecheckRequiresReview", Long.class, IngestContext.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(orchestrator, scopeId, context);
    }

    private static ComplianceResult review() {
        return new ComplianceResult(List.of(new SchemaViolation(
            ViolationType.CATEGORY, "分类不符合 Schema", Severity.HIGH, "category", "改用 §2 分类")));
    }

    private static IngestContext context() {
        IngestContext context = new IngestContext(1L, 2L, 3L, null);
        context.setWritingPlanJson("{\"plan\":[]}");
        context.setMetadataJson("{}");
        return context;
    }

    @Test
    void precheckSkipsWhenContextMissing() throws Exception {
        assertFalse(precheck(1L, null));
    }

    @Test
    void precheckFlagsPlanViolation() throws Exception {
        when(schemaComplianceChecker.checkPlan(eq(1L), any(), any())).thenReturn(review());
        assertTrue(precheck(1L, context()));
    }

    @Test
    void precheckFlagsMetadataViolation() throws Exception {
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(eq(1L), any(), any())).thenReturn(review());
        assertTrue(precheck(1L, context()));
    }

    @Test
    void precheckPassesWhenNoViolations() throws Exception {
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenReturn(ComplianceResult.ok());
        when(schemaComplianceChecker.check(any(), any(), any())).thenReturn(ComplianceResult.ok());
        assertFalse(precheck(1L, context()));
    }

    @Test
    void precheckFailsOpenWhenCheckerThrows() throws Exception {
        when(schemaComplianceChecker.checkPlan(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        assertFalse(precheck(1L, context()));
    }
}
