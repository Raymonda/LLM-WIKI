package org.cn.liuwt.llmwiki.domain.service.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IngestionStrategyAdvisor {

    private static final Logger log = LoggerFactory.getLogger(IngestionStrategyAdvisor.class);

    public ExecutionStrategy selectStrategy(DocumentProfile profile) {
        if (profile == null) {
            log.warn("DocumentProfile is null, using NARRATIVE default");
            return ExecutionStrategy.forNarrative();
        }

        ExecutionStrategy strategy;

        if (profile.isCompact()) {
            strategy = buildCompact(profile);
        } else if (profile.isLargePolicy()) {
            strategy = buildLargePolicy(profile);
        } else if (profile.documentType() == DocumentStructureAnalyzer.DocumentType.STRUCTURED
            && profile.chapterCount() >= 3) {
            strategy = buildChapterBased(profile);
        } else if (profile.hasRichCode()) {
            strategy = buildTechnicalRich(profile);
        } else {
            strategy = buildNarrative(profile);
        }

        strategy.adjustForProfile(profile);
        log.info("Strategy: preset={}, quality={}, summary={}k, entity={}k, chapter={}k, entities={}",
            strategy.getPreset(),
            strategy.getQualityTier(),
            strategy.getMaxSourceCharsSummary() / 1000,
            strategy.getMaxSourceCharsEntity() / 1000,
            strategy.getMaxSourceCharsChapter() / 1000,
            strategy.getMaxEntityPages());
        return strategy;
    }

    private ExecutionStrategy buildCompact(DocumentProfile profile) {
        ExecutionStrategy strategy = ExecutionStrategy.forCompact();
        strategy.setReasoning(String.format(
            "短文档（%d字符，%d chunks）：单段编译模式",
            profile.totalLength(), profile.chunkCount()));
        log.info("Strategy: COMPACT — length={}, chunks={}",
            profile.totalLength(), profile.chunkCount());
        return strategy;
    }

    private ExecutionStrategy buildLargePolicy(DocumentProfile profile) {
        ExecutionStrategy strategy = ExecutionStrategy.forLargePolicy();
        strategy.setReasoning(String.format(
            "大型制度文档（%d字符，%d H1，规则密度%.3f）：跳过合并，章节编译",
            profile.totalLength(), profile.h1Count(), profile.ruleDensity()));
        log.info("Strategy: LARGE_POLICY — length={}, h1={}, chapters={}",
            profile.totalLength(), profile.h1Count(), profile.chapterCount());
        return strategy;
    }

    private ExecutionStrategy buildChapterBased(DocumentProfile profile) {
        ExecutionStrategy strategy = ExecutionStrategy.forChapterBased();
        strategy.setReasoning(String.format(
            "结构化文档（%d H1，%d chapters）：章节编译模式",
            profile.h1Count(), profile.chapterCount()));
        log.info("Strategy: CHAPTER_BASED — h1={}, chapters={}, ruleDensity={}",
            profile.h1Count(), profile.chapterCount(), profile.ruleDensity());
        return strategy;
    }

    private ExecutionStrategy buildTechnicalRich(DocumentProfile profile) {
        ExecutionStrategy strategy = ExecutionStrategy.forTechnicalRich();
        strategy.setReasoning(String.format(
            "技术文档（代码块占比%.1f%%，表格占比%.1f%%）：实体编译模式",
            profile.codeBlockRatio() * 100, profile.tableRatio() * 100));
        log.info("Strategy: TECHNICAL_RICH — codeRatio={}, tableRatio={}",
            profile.codeBlockRatio(), profile.tableRatio());
        return strategy;
    }

    private ExecutionStrategy buildNarrative(DocumentProfile profile) {
        ExecutionStrategy strategy = ExecutionStrategy.forNarrative();
        strategy.setReasoning(String.format(
            "叙述性文档（%d字符，%d chunks）：实体编译模式",
            profile.totalLength(), profile.chunkCount()));
        log.info("Strategy: NARRATIVE — length={}, chunks={}",
            profile.totalLength(), profile.chunkCount());
        return strategy;
    }
}
