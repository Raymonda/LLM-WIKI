package org.cn.liuwt.llmwiki.domain.service.harness.conflict;

public enum ConflictResolutionStrategy {
    SOURCE_PRIORITY("source_priority", "高优先级来源胜出",
        "以高优先级来源的信息为准，合并内容。低优先级来源的冲突信息应被覆盖。", AutoLevel.REVIEW),
    NEWER_WINS("newer_wins", "最新来源胜出",
        "以最近更新的信息为准，合并内容。较旧的冲突信息应被覆盖。", AutoLevel.AUTO),
    ANNOTATE_BOTH("annotate_both", "保留双方并排标注",
        "保留双方信息，对矛盾部分添加标注（如「[存在不同说法]」），不可删除任何一方的内容。", AutoLevel.AUTO),
    ANNOTATE_AND_PATCH("annotate_and_patch", "标注并存+Schema补丁",
        "保留双方信息并添加标注。如果矛盾涉及概念定义，建议更新Schema规则。", AutoLevel.REVIEW),
    ADJUDICATE("adjudicate", "需人工裁决",
        "冲突内容需人工裁决，保留双方信息并标记为待裁决状态。", AutoLevel.DEFER);

    private final String key;
    private final String label;
    private final String promptInstruction;
    private final AutoLevel defaultAutoLevel;

    ConflictResolutionStrategy(String key, String label, String promptInstruction, AutoLevel defaultAutoLevel) {
        this.key = key;
        this.label = label;
        this.promptInstruction = promptInstruction;
        this.defaultAutoLevel = defaultAutoLevel;
    }

    public String getKey() { return key; }
    public String getLabel() { return label; }
    public String getPromptInstruction() { return promptInstruction; }
    public AutoLevel getDefaultAutoLevel() { return defaultAutoLevel; }
    public boolean isAutoExecutable() { return defaultAutoLevel == AutoLevel.AUTO; }

    public static ConflictResolutionStrategy fromString(String s) {
        if (s == null || s.isBlank()) return ANNOTATE_BOTH;
        for (ConflictResolutionStrategy st : values()) {
            if (st.key.equalsIgnoreCase(s)) return st;
        }
        return ANNOTATE_BOTH;
    }

    public static String buildStrategyInstruction(String defaultStrategy) {
        ConflictResolutionStrategy strategy = fromString(defaultStrategy);
        return "【冲突解决策略：" + strategy.label + "】" + strategy.promptInstruction + "\n";
    }

    public enum AutoLevel {
        AUTO,    // Schema 明确允许，自动执行
        REVIEW,  // 需要人工确认
        DEFER    // 暂缓处理
    }
}
