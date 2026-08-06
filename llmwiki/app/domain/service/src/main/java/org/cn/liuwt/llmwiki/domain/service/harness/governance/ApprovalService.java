package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;

public interface ApprovalService {
    enum ApprovalLevel {
        AUTO, CONFIRM, REVIEW
    }

    boolean requiresApproval(String stepName, String operationType);
    ApprovalLevel getApprovalLevel(String stepName, String operationType);
    ApprovalLevel getApprovalLevelWithSchemaViolations(String stepName, String operationType, ComplianceResult complianceResult);
    boolean approveStep(Long stepId, Long userId);
    boolean rejectStep(Long stepId, Long userId);
}