package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class EntityCandidateParser {

    public record ParseOutcome(List<EntityPageIncrementalApplier.CandidateEntry> candidates,
                               List<String> skipped, boolean fatal) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EntityCandidateParser() {}

    public static ParseOutcome parse(String rawJson) {
        List<EntityPageIncrementalApplier.CandidateEntry> candidates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (rawJson == null || rawJson.isBlank()) {
            return new ParseOutcome(candidates, skipped, true);
        }
        String cleaned = stripFences(rawJson.trim());
        JsonNode root;
        try {
            root = MAPPER.readTree(cleaned);
        } catch (Exception e) {
            return new ParseOutcome(candidates, skipped, true);
        }
        if (root == null || !root.isArray()) {
            return new ParseOutcome(candidates, skipped, true);
        }
        for (JsonNode node : root) {
            if (node == null || !node.isObject()) {
                skipped.add("非对象元素: " + node);
                continue;
            }
            String claim = text(node, "claim");
            if (claim == null || claim.isBlank()) {
                skipped.add("缺少 claim 字段: " + node);
                continue;
            }
            String relation = text(node, "relation");
            if (relation == null || relation.isBlank()) {
                skipped.add("缺少 relation 字段: " + claim);
                continue;
            }
            relation = relation.trim().toLowerCase();
            if (!relation.equals("new") && !relation.startsWith("duplicate_of:")
                && !relation.startsWith("conflict_with:")) {
                skipped.add("非法 relation: " + relation);
                continue;
            }
            candidates.add(new EntityPageIncrementalApplier.CandidateEntry(
                text(node, "section"), claim.trim(), text(node, "source"),
                text(node, "quote"), relation));
        }
        return new ParseOutcome(candidates, skipped, false);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static String stripFences(String raw) {
        String s = raw;
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
            s = s.trim();
        }
        return s;
    }
}
