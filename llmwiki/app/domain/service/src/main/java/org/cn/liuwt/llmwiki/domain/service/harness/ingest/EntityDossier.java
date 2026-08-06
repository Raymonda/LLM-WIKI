package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record EntityDossier(
    String entityName,
    String entityType,
    List<String> aliases,

    String definitionText,
    List<String> ruleTexts,
    List<String> dataPoints,
    Map<String, List<String>> sectionElaborations,

    List<String> containingSections,
    Set<String> relatedEntities,
    Map<String, String> relationshipHints,

    int totalMentions,
    int totalSourceChars
) {

    public int estimatePromptChars() {
        int chars = 0;
        if (definitionText != null) chars += definitionText.length();
        if (ruleTexts != null) chars += ruleTexts.stream().mapToInt(String::length).sum();
        if (dataPoints != null) chars += dataPoints.stream().mapToInt(String::length).sum();
        if (sectionElaborations != null) {
            for (List<String> elaborations : sectionElaborations.values()) {
                chars += elaborations.stream().mapToInt(String::length).sum();
            }
        }
        return chars;
    }

    public String formatForPrompt(int maxChars) {
        StringBuilder sb = new StringBuilder();

        if (definitionText != null && !definitionText.isBlank()) {
            sb.append("【定义】\n").append(definitionText).append("\n\n");
        }

        if (ruleTexts != null && !ruleTexts.isEmpty()) {
            sb.append("【规则与条款】\n");
            for (String rule : ruleTexts) {
                sb.append("- ").append(rule).append("\n");
            }
            sb.append("\n");
        }

        if (dataPoints != null && !dataPoints.isEmpty()) {
            sb.append("【关键数据】\n");
            for (String data : dataPoints) {
                sb.append("- ").append(data).append("\n");
            }
            sb.append("\n");
        }

        if (sectionElaborations != null && !sectionElaborations.isEmpty()) {
            sb.append("【详细阐述】\n");
            for (Map.Entry<String, List<String>> entry : sectionElaborations.entrySet()) {
                sb.append("## ").append(entry.getKey()).append("\n");
                for (String text : entry.getValue()) {
                    sb.append(text).append("\n\n");
                }
            }
        }

        if (relatedEntities != null && !relatedEntities.isEmpty()) {
            sb.append("【关联实体】\n");
            for (String related : relatedEntities) {
                String hint = relationshipHints != null ? relationshipHints.get(related) : null;
                if (hint != null) {
                    sb.append("- [[ ").append(related).append(" ]]（").append(hint).append("）\n");
                } else {
                    sb.append("- [[ ").append(related).append(" ]]\n");
                }
            }
            sb.append("\n");
        }

        String result = sb.toString();
        if (result.length() <= maxChars) {
            return result;
        }
        return truncatePreservingStructure(result, maxChars);
    }

    private static String truncatePreservingStructure(String text, int maxChars) {
        if (text.length() <= maxChars) return text;

        int headSize = (int) (maxChars * 0.4);
        int tailSize = (int) (maxChars * 0.4);
        int midStart = (text.length() - tailSize);

        StringBuilder sb = new StringBuilder();
        sb.append(text, 0, headSize);
        sb.append("\n\n...(中间内容省略，完整信息请参考原文)...\n\n");
        sb.append(text, midStart, text.length());

        return sb.toString();
    }
}
