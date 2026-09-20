package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AutoConfirmPolicy {

    public record Signals(
        boolean schemaPrecheckViolation,
        int conflictCount,
        double completenessScore,
        double updateRatio,
        boolean hasSchemaGapHints,
        boolean parseDegraded
    ) {}

    public record AutoDecision(boolean autoApprove, boolean hardBlocked, int softScore, List<String> reasons) {}

    private final int conflictThreshold;
    private final int scoreThreshold;
    private final double completenessMin;
    private final double updateRatioMax;

    public AutoConfirmPolicy(
        @Value("${llmwiki.ingest.auto-confirm.conflict-threshold:3}") int conflictThreshold,
        @Value("${llmwiki.ingest.auto-confirm.score-threshold:3}") int scoreThreshold,
        @Value("${llmwiki.ingest.auto-confirm.completeness-min:0.5}") double completenessMin,
        @Value("${llmwiki.ingest.auto-confirm.update-ratio-max:0.5}") double updateRatioMax) {
        this.conflictThreshold = conflictThreshold;
        this.scoreThreshold = scoreThreshold;
        this.completenessMin = completenessMin;
        this.updateRatioMax = updateRatioMax;
    }

    public AutoDecision decide(Signals signals) {
        List<String> reasons = new ArrayList<>();
        if (signals.schemaPrecheckViolation()) {
            reasons.add("schema_precheck_violation");
        }
        if (signals.conflictCount() > conflictThreshold) {
            reasons.add("conflict_count:" + signals.conflictCount());
        }
        if (!reasons.isEmpty()) {
            return new AutoDecision(false, true, 0, List.copyOf(reasons));
        }
        int softScore = 0;
        if (signals.completenessScore() < completenessMin) {
            softScore += 2;
            reasons.add("low_completeness:" + signals.completenessScore());
        }
        if (signals.updateRatio() > updateRatioMax) {
            softScore += 2;
            reasons.add("high_update_ratio:" + signals.updateRatio());
        }
        if (signals.hasSchemaGapHints()) {
            softScore += 1;
            reasons.add("schema_gap_hints");
        }
        if (signals.parseDegraded()) {
            softScore += 1;
            reasons.add("parse_degraded");
        }
        return new AutoDecision(softScore < scoreThreshold, false, softScore, List.copyOf(reasons));
    }
}
