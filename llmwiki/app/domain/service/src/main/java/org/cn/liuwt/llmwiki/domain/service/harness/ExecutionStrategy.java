package org.cn.liuwt.llmwiki.domain.service.harness;

public class ExecutionStrategy {

    private static final int MODEL_CONTEXT_CHARS = 1_500_000;
    private static final int SYSTEM_PROMPT_RESERVE = 20_000;
    private static final int OUTPUT_RESERVE = 8_000;
    private static final double QUALITY_SAFETY_RATIO = 0.85;

    public enum Preset {
        LARGE_POLICY,
        CHAPTER_BASED,
        TECHNICAL_RICH,
        NARRATIVE,
        COMPACT
    }

    public enum QualityTier {
        FULL_CONTEXT,
        BATCH_OPTIMIZED,
        SAMPLED
    }

    private Preset preset;
    private QualityTier qualityTier;
    private boolean skipMerge;
    private int maxEntityPages;
    private int maxRelatedPages;
    private int maxSourceCharsChapter;
    private int maxSourceCharsSummary;
    private int maxSourceCharsEntity;
    private int maxSourceCharsRelated;
    private int maxAnalysisCharsEntity;
    private int maxAnalysisCharsRelated;
    private boolean useChapterMode;
    private String reasoning;

    private ExecutionStrategy() {}

    public static ExecutionStrategy forLargePolicy() {
        ExecutionStrategy s = new ExecutionStrategy();
        s.preset = Preset.LARGE_POLICY;
        s.skipMerge = true;
        s.maxEntityPages = 10;
        s.maxRelatedPages = 5;
        s.maxSourceCharsChapter = 50000;
        s.maxSourceCharsSummary = 60000;
        s.maxSourceCharsEntity = 30000;
        s.maxSourceCharsRelated = 16000;
        s.maxAnalysisCharsEntity = 8000;
        s.maxAnalysisCharsRelated = 6000;
        s.useChapterMode = true;
        s.reasoning = "大型制度文档：跳过合并，章节编译模式";
        return s;
    }

    public static ExecutionStrategy forChapterBased() {
        ExecutionStrategy s = new ExecutionStrategy();
        s.preset = Preset.CHAPTER_BASED;
        s.skipMerge = true;
        s.maxEntityPages = 12;
        s.maxRelatedPages = 5;
        s.maxSourceCharsChapter = 40000;
        s.maxSourceCharsSummary = 60000;
        s.maxSourceCharsEntity = 30000;
        s.maxSourceCharsRelated = 16000;
        s.maxAnalysisCharsEntity = 8000;
        s.maxAnalysisCharsRelated = 6000;
        s.useChapterMode = true;
        s.reasoning = "结构化文档：章节编译模式";
        return s;
    }

    public static ExecutionStrategy forTechnicalRich() {
        ExecutionStrategy s = new ExecutionStrategy();
        s.preset = Preset.TECHNICAL_RICH;
        s.skipMerge = false;
        s.maxEntityPages = 12;
        s.maxRelatedPages = 5;
        s.maxSourceCharsChapter = 30000;
        s.maxSourceCharsSummary = 40000;
        s.maxSourceCharsEntity = 30000;
        s.maxSourceCharsRelated = 16000;
        s.maxAnalysisCharsEntity = 8000;
        s.maxAnalysisCharsRelated = 6000;
        s.useChapterMode = false;
        s.reasoning = "技术文档：代码/表格丰富，实体编译模式";
        return s;
    }

    public static ExecutionStrategy forNarrative() {
        ExecutionStrategy s = new ExecutionStrategy();
        s.preset = Preset.NARRATIVE;
        s.skipMerge = false;
        s.maxEntityPages = 15;
        s.maxRelatedPages = 5;
        s.maxSourceCharsChapter = 30000;
        s.maxSourceCharsSummary = 40000;
        s.maxSourceCharsEntity = 30000;
        s.maxSourceCharsRelated = 16000;
        s.maxAnalysisCharsEntity = 8000;
        s.maxAnalysisCharsRelated = 6000;
        s.useChapterMode = false;
        s.reasoning = "叙述性文档：实体编译模式";
        return s;
    }

    public static ExecutionStrategy forCompact() {
        ExecutionStrategy s = new ExecutionStrategy();
        s.preset = Preset.COMPACT;
        s.skipMerge = false;
        s.maxEntityPages = 5;
        s.maxRelatedPages = 3;
        s.maxSourceCharsChapter = 20000;
        s.maxSourceCharsSummary = 20000;
        s.maxSourceCharsEntity = 20000;
        s.maxSourceCharsRelated = 8000;
        s.maxAnalysisCharsEntity = 5000;
        s.maxAnalysisCharsRelated = 4000;
        s.useChapterMode = false;
        s.reasoning = "短文档：单段编译模式";
        return s;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public void adjustForProfile(DocumentProfile profile) {
        if (profile == null) return;

        int totalLength = (int) Math.min(profile.totalLength(), Integer.MAX_VALUE);
        int effectiveContextBudget = computeEffectiveContextBudget();

        qualityTier = determineQualityTier(totalLength, profile.chapterCount(), effectiveContextBudget);

        if (qualityTier == QualityTier.FULL_CONTEXT) {
            maxSourceCharsSummary = Math.max(maxSourceCharsSummary, totalLength);
        } else {
            int dynamicSummary = Math.max(20000, Math.min(80000, (int)(totalLength * 0.12)));
            if (dynamicSummary > maxSourceCharsSummary) {
                maxSourceCharsSummary = dynamicSummary;
            }
        }

        int dynamicEntity = Math.max(20000, Math.min(50000, (int)(totalLength * 0.06)));
        if (dynamicEntity > maxSourceCharsEntity) {
            maxSourceCharsEntity = dynamicEntity;
        }

        if (profile.chapterCount() > 0 && useChapterMode) {
            int avgChapterLen = totalLength / profile.chapterCount();
            int neededChapterChars = (int)(avgChapterLen * 1.1);
            if (neededChapterChars > maxSourceCharsChapter) {
                maxSourceCharsChapter = Math.min(neededChapterChars, 100000);
            }
        }

        int densityBasedEntities = Math.max(maxEntityPages, Math.min(20, totalLength / 30000));
        maxEntityPages = densityBasedEntities;

        if (qualityTier == QualityTier.FULL_CONTEXT) {
            maxAnalysisCharsEntity = Math.max(maxAnalysisCharsEntity, 20000);
            maxAnalysisCharsRelated = Math.max(maxAnalysisCharsRelated, 15000);
        } else {
            int dynamicAnalysisEntity = Math.max(5000, Math.min(15000, (int)(totalLength * 0.01)));
            if (dynamicAnalysisEntity > maxAnalysisCharsEntity) {
                maxAnalysisCharsEntity = dynamicAnalysisEntity;
            }
            int dynamicAnalysisRelated = Math.max(4000, Math.min(12000, (int)(totalLength * 0.008)));
            if (dynamicAnalysisRelated > maxAnalysisCharsRelated) {
                maxAnalysisCharsRelated = dynamicAnalysisRelated;
            }
        }

        reasoning = String.format("%s | quality=%s, docLen=%d, budget=%d",
            reasoning, qualityTier, totalLength, effectiveContextBudget);
    }

    private int computeEffectiveContextBudget() {
        return (int)((MODEL_CONTEXT_CHARS - SYSTEM_PROMPT_RESERVE - OUTPUT_RESERVE) * QUALITY_SAFETY_RATIO);
    }

    private QualityTier determineQualityTier(int totalLength, int chapterCount, int contextBudget) {
        if (totalLength <= contextBudget) {
            return QualityTier.FULL_CONTEXT;
        }

        int batchBudget = chapterCount * 800;
        if (batchBudget <= contextBudget) {
            return QualityTier.BATCH_OPTIMIZED;
        }

        return QualityTier.SAMPLED;
    }

    public QualityTier getQualityTier() { return qualityTier; }
    public Preset getPreset() { return preset; }
    public boolean isSkipMerge() { return skipMerge; }
    public int getMaxEntityPages() { return maxEntityPages; }
    public int getMaxRelatedPages() { return maxRelatedPages; }
    public int getMaxSourceCharsChapter() { return maxSourceCharsChapter; }
    public int getMaxSourceCharsSummary() { return maxSourceCharsSummary; }
    public int getMaxSourceCharsEntity() { return maxSourceCharsEntity; }
    public int getMaxSourceCharsRelated() { return maxSourceCharsRelated; }
    public int getMaxAnalysisCharsEntity() { return maxAnalysisCharsEntity; }
    public int getMaxAnalysisCharsRelated() { return maxAnalysisCharsRelated; }
    public boolean isUseChapterMode() { return useChapterMode; }
    public String getReasoning() { return reasoning; }
}
