package org.cn.liuwt.llmwiki.domain.service.harness.governance;

import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionStepDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionStepMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.validation.SchemaComplianceChecker.ComplianceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ApprovalServiceImpl implements ApprovalService {

    @Autowired
    private ExecutionStepMapper executionStepMapper;

    private static final Map<String, ApprovalLevel> INGEST_DEFAULTS = Map.of(
        "UPLOAD", ApprovalLevel.AUTO,
        "ANALYZE", ApprovalLevel.AUTO,
        "WRITE", ApprovalLevel.AUTO,
        "COMPLETE", ApprovalLevel.AUTO
    );

    private static final Map<String, ApprovalLevel> QUERY_DEFAULTS = Map.of(
        "TOOL_CALL", ApprovalLevel.AUTO,
        "SAVE_TO_WIKI", ApprovalLevel.CONFIRM
    );

    private static final Map<String, ApprovalLevel> LINT_DEFAULTS = Map.ofEntries(
        Map.entry("CHECK_ORPHANS", ApprovalLevel.AUTO),
        Map.entry("CHECK_STALE", ApprovalLevel.AUTO),
        Map.entry("CHECK_MISSING_CROSSREFS", ApprovalLevel.AUTO),
        Map.entry("CHECK_CONFLICTS", ApprovalLevel.AUTO),
        Map.entry("CHECK_GAPS", ApprovalLevel.AUTO),
        Map.entry("WRITE_HEALTH_STATUS", ApprovalLevel.AUTO),
        Map.entry("GENERATE_RULING_BRIEF", ApprovalLevel.AUTO),
        Map.entry("AUTO_FIX_ORPHANS", ApprovalLevel.AUTO),
        Map.entry("AUTO_FIX_CROSSREFS", ApprovalLevel.AUTO),
        Map.entry("GENERATE_REPORT", ApprovalLevel.AUTO),
        Map.entry("SUGGEST_ACTIONS", ApprovalLevel.CONFIRM),
        Map.entry("PROPOSE_SCHEMA_PATCH", ApprovalLevel.AUTO)
    );

    @Override
    public boolean requiresApproval(String stepName, String operationType) {
        ApprovalLevel level = getApprovalLevel(stepName, operationType);
        return level != ApprovalLevel.AUTO;
    }

    @Override
    public ApprovalLevel getApprovalLevel(String stepName, String operationType) {
        Map<String, ApprovalLevel> defaults = switch (operationType) {
            case "ingest" -> INGEST_DEFAULTS;
            case "query" -> QUERY_DEFAULTS;
            case "lint" -> LINT_DEFAULTS;
            default -> Map.of(stepName, ApprovalLevel.AUTO);
        };
        return defaults.getOrDefault(stepName, ApprovalLevel.AUTO);
    }

    @Override
    public ApprovalLevel getApprovalLevelWithSchemaViolations(String stepName, String operationType, ComplianceResult complianceResult) {
        ApprovalLevel baseLevel = getApprovalLevel(stepName, operationType);
        if (complianceResult != null && complianceResult.requiresReview()) {
            if (baseLevel == ApprovalLevel.AUTO) {
                return ApprovalLevel.REVIEW;
            }
            if (baseLevel == ApprovalLevel.CONFIRM) {
                return ApprovalLevel.REVIEW;
            }
        }
        return baseLevel;
    }

    @Override
    public boolean approveStep(Long stepId, Long userId) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO == null) {
            return false;
        }
        stepDO.setApprovedBy(userId);
        stepDO.setStatus("approved");
        executionStepMapper.updateById(stepDO);
        return true;
    }

    @Override
    public boolean rejectStep(Long stepId, Long userId) {
        ExecutionStepDO stepDO = executionStepMapper.selectById(stepId);
        if (stepDO == null) {
            return false;
        }
        stepDO.setApprovedBy(userId);
        stepDO.setStatus("rejected");
        executionStepMapper.updateById(stepDO);
        return true;
    }
}