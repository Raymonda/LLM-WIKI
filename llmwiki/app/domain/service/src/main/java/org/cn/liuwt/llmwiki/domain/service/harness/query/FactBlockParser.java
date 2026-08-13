package org.cn.liuwt.llmwiki.domain.service.harness.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.FactBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FactBlockParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONFIDENCE_WEIGHT_HIGH = 0;
    private static final int CONFIDENCE_WEIGHT_MEDIUM = 1;
    private static final int CONFIDENCE_WEIGHT_LOW = 2;

    private FactBlockParser() {}

    public static FactBlock tryParse(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) return null;
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            if (node == null || !node.has("conclusion")) return null;
            String id = node.has("id") ? node.get("id").asText() : "fb-" + System.nanoTime();
            String conclusion = node.get("conclusion").asText();
            if (conclusion == null || conclusion.isBlank()) return null;
            String evidence = node.has("evidence") ? node.get("evidence").asText() : "";
            List<FactBlock.FactRef> refs = new ArrayList<>();
            if (node.has("refs") && node.get("refs").isArray()) {
                for (JsonNode refNode : node.get("refs")) {
                    String path = refNode.has("path") ? refNode.get("path").asText() : "";
                    String title = refNode.has("title") ? refNode.get("title").asText() : "";
                    refs.add(new FactBlock.FactRef(path, title));
                }
            }
            String confidence = node.has("confidence") ? node.get("confidence").asText() : "medium";
            if (confidence == null || confidence.isBlank()) confidence = "medium";
            String kind = node.has("kind") ? node.get("kind").asText() : "fact";
            return new FactBlock(id, conclusion, evidence, refs, confidence, kind);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> extractCompleteLines(StringBuilder buffer) {
        List<String> lines = new ArrayList<>();
        int newlineIndex;
        while ((newlineIndex = buffer.indexOf("\n")) >= 0) {
            String line = buffer.substring(0, newlineIndex);
            buffer.delete(0, newlineIndex + 1);
            if (!line.isBlank()) lines.add(line);
        }
        return lines;
    }

    public static String toSummaryView(List<FactBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return "（事实层未产出结构化事实）";
        List<FactBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingInt(FactBlockParser::confidenceWeight));
        StringBuilder sb = new StringBuilder("## 事实清单（").append(sorted.size()).append(" 条，按可信度排序）\n");
        for (int i = 0; i < sorted.size(); i++) {
            FactBlock block = sorted.get(i);
            sb.append('[').append(i + 1).append("] ")
              .append(confidenceLabel(block.confidence()));
            if ("contrast".equals(block.kind())) sb.append(" | 矛盾条目");
            sb.append(" | ").append(block.conclusion());
            if (block.evidence() != null && !block.evidence().isBlank()) {
                sb.append("（依据：").append(block.evidence()).append('）');
            }
            if (block.refs() != null && !block.refs().isEmpty()) {
                sb.append("；来源页：");
                for (FactBlock.FactRef ref : block.refs()) {
                    sb.append(ref.path()).append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String toFactInput(List<FactBlock> blocks, String fallbackText) {
        if (blocks == null || blocks.isEmpty()) {
            return fallbackText == null ? "（事实层未产出结构化事实）" : fallbackText;
        }
        return toSummaryView(blocks);
    }

    private static int confidenceWeight(FactBlock block) {
        String c = block.confidence() == null ? "medium" : block.confidence();
        return switch (c) {
            case "high" -> CONFIDENCE_WEIGHT_HIGH;
            case "low" -> CONFIDENCE_WEIGHT_LOW;
            default -> CONFIDENCE_WEIGHT_MEDIUM;
        };
    }

    private static String confidenceLabel(String confidence) {
        return switch (confidence == null ? "medium" : confidence) {
            case "high" -> "高可信";
            case "low" -> "低可信";
            default -> "中可信";
        };
    }
}
