package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.domain.service.harness.LlmConcurrencyBarrier;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConsistencyReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyReconciler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_PAGE_PREVIEW_CHARS = 1500;
    private static final int MAX_TOTAL_PAGES = 15;

    public record TermInconsistency(
        String standardTerm,
        List<String> variants,
        List<String> affectedPages
    ) {}

    public record FactConflict(
        String description,
        String pageA,
        String pageB,
        String claimA,
        String claimB
    ) {}

    public record ConsistencyReport(
        List<TermInconsistency> termInconsistencies,
        List<FactConflict> factConflicts,
        int termsFixed,
        int unfixableIssues,
        Set<String> fixedPaths
    ) {
        public ConsistencyReport(List<TermInconsistency> termInconsistencies,
                                 List<FactConflict> factConflicts,
                                 int termsFixed,
                                 int unfixableIssues) {
            this(termInconsistencies, factConflicts, termsFixed, unfixableIssues, Set.of());
        }

        public boolean hasIssues() {
            return !termInconsistencies.isEmpty() || !factConflicts.isEmpty();
        }

        public String summarize() {
            StringBuilder sb = new StringBuilder();
            sb.append("Consistency: ").append(termInconsistencies.size()).append(" term issues, ");
            sb.append(factConflicts.size()).append(" fact conflicts, ");
            sb.append(termsFixed).append(" fixed, ");
            sb.append(unfixableIssues).append(" unfixable");
            if (!termInconsistencies.isEmpty()) {
                sb.append("\nTerm inconsistencies:\n");
                for (TermInconsistency ti : termInconsistencies) {
                    sb.append("  - \"").append(ti.standardTerm).append("\" vs ")
                        .append(ti.variants).append(" in ").append(ti.affectedPages).append("\n");
                }
            }
            if (!factConflicts.isEmpty()) {
                sb.append("\nFact conflicts:\n");
                for (FactConflict fc : factConflicts) {
                    sb.append("  - ").append(fc.description)
                        .append(" (").append(fc.pageA).append(" vs ").append(fc.pageB).append(")\n");
                }
            }
            return sb.toString();
        }
    }

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LlmConcurrencyBarrier llmBarrier;

    public ConsistencyReport reconcile(IngestContext context) {
        Map<String, String> allPageContents = collectPageContents(context);
        if (allPageContents.size() < 2) {
            return new ConsistencyReport(List.of(), List.of(), 0, 0);
        }

        String writingPlanJson = context.getWritingPlanJson();
        List<String> consistencyRules = extractConsistencyRules(writingPlanJson);

        String llmResponse = callLlmForConsistencyCheck(allPageContents, consistencyRules);
        if (llmResponse == null || llmResponse.isBlank()) {
            return new ConsistencyReport(List.of(), List.of(), 0, 0);
        }

        List<TermInconsistency> termIssues = parseTermInconsistencies(llmResponse);
        List<FactConflict> factIssues = parseFactConflicts(llmResponse);

        Set<String> fixedPaths = new LinkedHashSet<>();
        int termsFixed = applyTermFixes(context, allPageContents, termIssues, fixedPaths);

        int unfixable = factIssues.size();
        log.info("ConsistencyReconciler: {} term issues ({} fixed across {} pages), {} fact conflicts (unfixable). scopeId={}",
            termIssues.size(), termsFixed, fixedPaths.size(), factIssues.size(), context.getScopeId());

        return new ConsistencyReport(termIssues, factIssues, termsFixed, unfixable, fixedPaths);
    }

    private Map<String, String> collectPageContents(IngestContext context) {
        Map<String, String> contents = new LinkedHashMap<>();
        String scopeIdStr = String.valueOf(context.getScopeId());

        ConcurrentHashMap<String, String> pageContents = context.getPageContents();
        if (pageContents != null) {
            for (Map.Entry<String, String> entry : pageContents.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    contents.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (contents.isEmpty()) {
            collectFromPages(scopeIdStr, context.getSummaryPage(), contents, "summary");
            collectFromPages(scopeIdStr, context.getEntityPages(), contents);
            collectFromPages(scopeIdStr, context.getChapterPages(), contents);
            collectFromPages(scopeIdStr, context.getUpdatedPages(), contents);
        }

        if (contents.size() > MAX_TOTAL_PAGES) {
            Map<String, String> trimmed = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                if (count >= MAX_TOTAL_PAGES) break;
                trimmed.put(entry.getKey(), entry.getValue());
                count++;
            }
            return trimmed;
        }

        return contents;
    }

    private void collectFromPages(String scopeIdStr, WikiPageDO page, Map<String, String> contents, String label) {
        if (page == null || page.getFilePath() == null) return;
        String content = readPageContent(scopeIdStr, page.getFilePath());
        if (content != null && !content.isBlank()) {
            contents.put(page.getFilePath(), content);
        }
    }

    private void collectFromPages(String scopeIdStr, ConcurrentHashMap<String, WikiPageDO> pages, Map<String, String> contents) {
        if (pages == null) return;
        for (Map.Entry<String, WikiPageDO> entry : pages.entrySet()) {
            WikiPageDO page = entry.getValue();
            if (page == null || page.getFilePath() == null) continue;
            String content = readPageContent(scopeIdStr, page.getFilePath());
            if (content != null && !content.isBlank()) {
                contents.put(page.getFilePath(), content);
            }
        }
    }

    private String readPageContent(String scopeIdStr, String filePath) {
        try {
            byte[] bytes = storageProvider.read(scopeIdStr, "wiki/" + filePath);
            if (bytes == null) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> extractConsistencyRules(String writingPlanJson) {
        if (writingPlanJson == null || writingPlanJson.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(writingPlanJson);
            JsonNode rules = root.get("consistencyRules");
            if (rules == null) return List.of();

            if (rules.isArray()) {
                List<String> result = new ArrayList<>();
                for (JsonNode rule : rules) {
                    result.add(rule.asText());
                }
                return result;
            }

            if (rules.isObject()) {
                List<String> result = new ArrayList<>();
                JsonNode termMap = rules.get("termMap");
                if (termMap != null && termMap.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = termMap.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        result.add("术语统一: \"" + field.getKey() + "\" = " + field.getValue().toString());
                    }
                }
                JsonNode rulesList = rules.get("rules");
                if (rulesList != null && rulesList.isArray()) {
                    for (JsonNode r : rulesList) {
                        result.add(r.asText());
                    }
                }
                return result;
            }

            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String callLlmForConsistencyCheck(Map<String, String> pageContents, List<String> consistencyRules) {
        if (chatClient == null || !chatClient.isAvailable()) {
            log.debug("ConsistencyReconciler: ChatClient unavailable, skipping LLM check");
            return null;
        }

        StringBuilder pagesSummary = new StringBuilder();
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            String title = extractTitleFromContent(content);
            String preview = content.length() > MAX_PAGE_PREVIEW_CHARS
                ? content.substring(0, MAX_PAGE_PREVIEW_CHARS) + "\n...(truncated)"
                : content;
            pagesSummary.append("### ").append(title != null ? title : path).append(" (").append(path).append(")\n");
            pagesSummary.append(preview).append("\n\n");
        }

        String rulesText = consistencyRules.isEmpty() ? "（无预设规则）" : String.join("\n", consistencyRules);

        String systemPrompt = "你是一个知识库的一致性审查员。你的任务是检查多个 Wiki 页面之间的术语一致性和事实一致性。\n\n"
            + "请输出严格的 JSON（不要 Markdown 代码块包裹），包含以下字段：\n"
            + "- termInconsistencies: 术语不一致列表。每个元素包含：\n"
            + "  - standardTerm: 应该使用的标准术语（选择原文中出现最多或最正式的那个）\n"
            + "  - variants: 不一致的变体列表（需要被替换为标准术语的词）\n"
            + "  - affectedPages: 受影响页面的路径列表\n"
            + "- factConflicts: 事实冲突列表。每个元素包含：\n"
            + "  - description: 冲突描述\n"
            + "  - pageA: 第一个页面路径\n"
            + "  - pageB: 第二个页面路径\n"
            + "  - claimA: 第一个页面的主张\n"
            + "  - claimB: 第二个页面的主张\n\n"
            + "检查要点：\n"
            + "1. 同一概念在不同页面中使用了不同的名称（如「合规官」vs「合规专员」）\n"
            + "2. 同一事实在不同页面中有矛盾的描述（如「3级审批」vs「两级审批」）\n"
            + "3. 不同页面对同一实体的强调点是否冲突\n\n"
            + "如果所有内容一致，返回 {\"termInconsistencies\":[],\"factConflicts\":[]}";

        String userMessage = "【全局一致性约束】\n" + rulesText + "\n\n"
            + "【页面内容】\n" + pagesSummary;

        if (!llmBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.RECONCILE, 60_000)) {
            log.warn("ConsistencyReconciler: barrier timeout, skipping");
            return null;
        }
        try {
            return chatClient.chat(systemPrompt, userMessage);
        } catch (Exception e) {
            log.warn("ConsistencyReconciler: LLM call failed: {}", e.getMessage());
            return null;
        } finally {
            llmBarrier.release(LlmConcurrencyBarrier.Bucket.RECONCILE);
        }
    }

    private List<TermInconsistency> parseTermInconsistencies(String response) {
        try {
            String json = extractJsonFromResponse(response);
            JsonNode root = MAPPER.readTree(json);
            JsonNode terms = root.get("termInconsistencies");
            if (terms == null || !terms.isArray()) return List.of();

            List<TermInconsistency> result = new ArrayList<>();
            for (JsonNode term : terms) {
                String standardTerm = term.has("standardTerm") ? term.get("standardTerm").asText() : null;
                if (standardTerm == null || standardTerm.isBlank()) continue;

                List<String> variants = new ArrayList<>();
                JsonNode variantsNode = term.get("variants");
                if (variantsNode != null && variantsNode.isArray()) {
                    for (JsonNode v : variantsNode) {
                        String variant = v.asText();
                        if (variant != null && !variant.isBlank() && !variant.equals(standardTerm)) {
                            variants.add(variant);
                        }
                    }
                }
                if (variants.isEmpty()) continue;

                List<String> affectedPages = new ArrayList<>();
                JsonNode pagesNode = term.get("affectedPages");
                if (pagesNode != null && pagesNode.isArray()) {
                    for (JsonNode p : pagesNode) {
                        affectedPages.add(p.asText());
                    }
                }

                result.add(new TermInconsistency(standardTerm, variants, affectedPages));
            }
            return result;
        } catch (Exception e) {
            log.warn("ConsistencyReconciler: failed to parse term inconsistencies: {}", e.getMessage());
            return List.of();
        }
    }

    private List<FactConflict> parseFactConflicts(String response) {
        try {
            String json = extractJsonFromResponse(response);
            JsonNode root = MAPPER.readTree(json);
            JsonNode conflicts = root.get("factConflicts");
            if (conflicts == null || !conflicts.isArray()) return List.of();

            List<FactConflict> result = new ArrayList<>();
            for (JsonNode conflict : conflicts) {
                result.add(new FactConflict(
                    conflict.has("description") ? conflict.get("description").asText() : "",
                    conflict.has("pageA") ? conflict.get("pageA").asText() : "",
                    conflict.has("pageB") ? conflict.get("pageB").asText() : "",
                    conflict.has("claimA") ? conflict.get("claimA").asText() : "",
                    conflict.has("claimB") ? conflict.get("claimB").asText() : ""
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("ConsistencyReconciler: failed to parse fact conflicts: {}", e.getMessage());
            return List.of();
        }
    }

    private int applyTermFixes(IngestContext context, Map<String, String> allContents, List<TermInconsistency> issues, Set<String> fixedPathsOut) {
        if (issues.isEmpty()) return 0;

        int fixCount = 0;
        String scopeIdStr = String.valueOf(context.getScopeId());

        for (TermInconsistency issue : issues) {
            String standard = issue.standardTerm();
            for (String variant : issue.variants()) {
                if (variant.length() < 2) continue;
                if (standard.contains(variant)) continue;

                for (Map.Entry<String, String> pageEntry : allContents.entrySet()) {
                    String path = pageEntry.getKey();
                    if (!issue.affectedPages().isEmpty() && !issue.affectedPages().contains(path)) {
                        continue;
                    }

                    String content = pageEntry.getValue();
                    if (content == null || !content.contains(variant)) continue;

                    String fixed = WritingPlanComplianceChecker.safeReplaceVariant(content, variant, standard);
                    if (fixed.equals(content)) continue;
                    pageEntry.setValue(fixed);
                    fixCount++;
                    fixedPathsOut.add(path);

                    try {
                        storageProvider.write(scopeIdStr, "wiki/" + path, fixed.getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        log.warn("ConsistencyReconciler: failed to write fix to {}: {}", path, e.getMessage());
                    }

                    ConcurrentHashMap<String, String> pageContents = context.getPageContents();
                    if (pageContents != null) {
                        pageContents.put(path, fixed);
                    }
                }
            }
        }

        return fixCount;
    }

    private String extractJsonFromResponse(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }

        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return trimmed;
    }

    private String extractTitleFromContent(String content) {
        if (content == null || content.isBlank()) return null;
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return null;
    }
}
