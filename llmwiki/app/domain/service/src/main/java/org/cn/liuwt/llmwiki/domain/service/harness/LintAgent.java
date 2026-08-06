package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.domain.model.harness.LintRulesConfig;
import org.cn.liuwt.llmwiki.domain.service.harness.GlobalSummaryService.GlobalSummary;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.SchemaInjector;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.LintPrompts;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LintAgent {

    private static final Logger log = LoggerFactory.getLogger(LintAgent.class);
    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> FINDING_LIST_TYPE = new TypeReference<>() {};

    private static final int PAGE_CATALOG_MAX = 200;
    private static final int PAGE_CONTENT_PREVIEW_MAX = 30;
    private static final int PAGE_CONTENT_PREVIEW_CHARS = 400;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private SchemaInjector schemaInjector;

    @Autowired
    private LlmConcurrencyBarrier concurrencyBarrier;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private StorageProvider storageProvider;

    public ProbeResult probe(Long scopeId, GlobalSummary summary,
                                 String previousFindingsSummary, String previousHealthSummary,
                                 LintRulesConfig rules) {
        return probe(scopeId, summary, previousFindingsSummary, previousHealthSummary, rules, null, null);
    }

    ProbeResult probe(Long scopeId, GlobalSummary summary,
                      String previousFindingsSummary, String previousHealthSummary,
                      LintRulesConfig rules,
                      List<WikiPageDO> focusPages, List<WikiPageDO> allPagesForStats) {
        if (chatClient == null || !rules.getDiagnosticStandard().isProbeEnabled()) {
            log.info("scopeId={} LintAgent probe 被跳过（chatClient=null 或 probeEnabled=false）", scopeId);
            return ProbeResult.skipped();
        }

        boolean acquired = false;
        try {
            acquired = concurrencyBarrier.tryAcquire(LlmConcurrencyBarrier.Bucket.LINT, 60000);
            if (!acquired) {
                log.warn("scopeId={} LintAgent probe 并发信号量获取失败", scopeId);
                return ProbeResult.withStatus("barrier_timeout");
            }
            String systemPrompt = schemaInjector.prependForLint(scopeId,
                LintPrompts.getInstance().probeSystemPrompt(rules));
            boolean incremental = focusPages != null && !focusPages.isEmpty();
            String pageCatalog = buildPageCatalog(scopeId, focusPages, allPagesForStats);
            String userPrompt = buildProbeUserPrompt(scopeId, summary, pageCatalog,
                previousFindingsSummary, previousHealthSummary, rules, incremental);

            int inputPromptLength = systemPrompt.length() + userPrompt.length();

            String raw = chatClient.chat(systemPrompt, userPrompt);

            List<Map<String, Object>> parsed = parseProbeOutput(raw);
            if (parsed == null) {
                log.warn("LintAgent probe 输出解析失败 scopeId={}，原始输出前 200 字符: {}",
                    scopeId, raw != null && raw.length() > 200 ? raw.substring(0, 200) : raw);
                return new ProbeResult(Collections.emptyList(), raw, inputPromptLength, "parse_failed");
            }
            return new ProbeResult(parsed, raw, inputPromptLength, "success");
        } catch (Exception e) {
            log.warn("LintAgent probe 失败 scopeId={}: {}", scopeId, e.getMessage());
            return ProbeResult.withStatus("error");
        } finally {
            if (acquired) {
                concurrencyBarrier.release(LlmConcurrencyBarrier.Bucket.LINT);
            }
        }
    }

    private String buildPageCatalog(Long scopeId) {
        return buildPageCatalog(scopeId, null, null);
    }

    String buildPageCatalog(Long scopeId, List<WikiPageDO> focusPages, List<WikiPageDO> allPagesForStats) {
        try {
            List<WikiPageDO> allPages = allPagesForStats != null ? allPagesForStats : wikiPageMapper.selectList(
                new QueryWrapper<WikiPageDO>()
                    .eq("scope_id", scopeId)
                    .select("id", "title", "file_path", "category", "health_status")
                    .orderByAsc("category", "title")
            );

            if (allPages.isEmpty()) {
                return "(暂无页面)";
            }

            boolean incremental = focusPages != null && !focusPages.isEmpty();
            List<WikiPageDO> detailPages = incremental ? focusPages : allPages;
            if (!incremental && detailPages.size() > PAGE_CATALOG_MAX) {
                detailPages = prioritizeAbnormalPages(detailPages, PAGE_CATALOG_MAX);
            }

            Set<Long> detailPageIds = detailPages.stream().map(WikiPageDO::getId).collect(Collectors.toSet());
            List<WikiPageKeywordDO> allKeywords = wikiPageKeywordMapper.selectList(
                new QueryWrapper<WikiPageKeywordDO>()
                    .in("page_id", detailPageIds)
                    .eq("scope_id", scopeId)
            );
            Map<Long, List<String>> keywordsByPage = allKeywords.stream()
                .collect(Collectors.groupingBy(WikiPageKeywordDO::getPageId,
                    Collectors.mapping(WikiPageKeywordDO::getKeyword, Collectors.toList())));

            StringBuilder sb = new StringBuilder();

            sb.append(buildCategoryStatsSummary(allPages)).append("\n");

            if (incremental) {
                sb.append("\n## 变更页面明细（").append(detailPages.size()).append(" 页，请重点诊断这些页面）\n\n");
            } else {
                long abnormalCount = allPages.stream()
                    .filter(p -> p.getHealthStatus() != null && !"healthy".equals(p.getHealthStatus()))
                    .count();
                if (abnormalCount > 0) {
                    sb.append("\n## 需关注页面明细（").append(abnormalCount).append(" 页异常）\n\n");
                } else {
                    sb.append("\n## 页面明细\n\n");
                }
            }

            for (WikiPageDO p : detailPages) {
                sb.append("- ").append(p.getTitle());
                sb.append(" [").append(p.getCategory() != null ? p.getCategory() : "未分类").append("]");
                if (p.getHealthStatus() != null && !"healthy".equals(p.getHealthStatus())) {
                    sb.append(" ⚠").append(p.getHealthStatus());
                }
                List<String> kws = keywordsByPage.getOrDefault(p.getId(), List.of());
                if (!kws.isEmpty()) {
                    int limit = Math.min(3, kws.size());
                    sb.append(" 🏷").append(String.join(",", kws.subList(0, limit)));
                    if (kws.size() > 3) sb.append("...");
                }
                sb.append("\n");
            }
            appendContentPreviews(sb, scopeId, detailPages);
            if (!incremental && allPages.size() > PAGE_CATALOG_MAX) {
                sb.append("- ...（共 ").append(allPages.size()).append(" 页，仅展示 ").append(detailPages.size()).append(" 页异常+抽样）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to build page catalog for Lint probe, scopeId={}: {}", scopeId, e.getMessage());
            return "(无法获取页面目录)";
        }
    }

    private void appendContentPreviews(StringBuilder sb, Long scopeId, List<WikiPageDO> detailPages) {
        List<WikiPageDO> previewPages = detailPages.stream()
            .filter(p -> p.getFilePath() != null)
            .limit(PAGE_CONTENT_PREVIEW_MAX)
            .toList();
        if (previewPages.isEmpty()) return;

        StringBuilder previewSb = new StringBuilder();
        int attached = 0;
        for (WikiPageDO p : previewPages) {
            String digest = buildContentDigest(scopeId, p.getFilePath());
            if (digest == null || digest.isBlank()) continue;
            previewSb.append("### ").append(p.getTitle() != null ? p.getTitle() : p.getFilePath()).append("\n")
                .append(digest).append("\n\n");
            attached++;
        }
        if (attached == 0) return;
        sb.append("\n## 页面内容摘录（每页前 ").append(PAGE_CONTENT_PREVIEW_CHARS)
            .append(" 字符，仅供内容级矛盾/孤儿判断参考）\n\n")
            .append(previewSb);
    }

    private String buildContentDigest(Long scopeId, String filePath) {
        try {
            String storagePath = filePath.startsWith("pages/") ? "wiki/" + filePath : filePath;
            byte[] bytes = storageProvider.read(String.valueOf(scopeId), storagePath);
            if (bytes == null) return null;
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            List<String> headings = content.lines()
                .filter(l -> l.startsWith("#"))
                .limit(8)
                .toList();
            String head = content.length() > PAGE_CONTENT_PREVIEW_CHARS
                ? content.substring(0, PAGE_CONTENT_PREVIEW_CHARS) + "..."
                : content;
            StringBuilder sb = new StringBuilder(head.trim());
            if (!headings.isEmpty()) {
                sb.append("\n标题结构：").append(String.join(" | ", headings));
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to read page content for lint probe preview {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private String buildCategoryStatsSummary(List<WikiPageDO> allPages) {
        Map<String, Map<String, Long>> catStats = new java.util.LinkedHashMap<>();
        for (WikiPageDO p : allPages) {
            String cat = p.getCategory() != null ? p.getCategory() : "未分类";
            String status = p.getHealthStatus() != null ? p.getHealthStatus() : "healthy";
            catStats.computeIfAbsent(cat, k -> new java.util.LinkedHashMap<>())
                .merge(status, 1L, Long::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 页面概况（共 ").append(allPages.size()).append(" 页，").append(catStats.size()).append(" 个分类）\n");
        for (Map.Entry<String, Map<String, Long>> entry : catStats.entrySet()) {
            long total = entry.getValue().values().stream().mapToLong(Long::longValue).sum();
            long healthy = entry.getValue().getOrDefault("healthy", 0L);
            long abnormal = total - healthy;
            sb.append("- [").append(entry.getKey()).append("] ").append(total).append("页");
            sb.append("（").append(healthy).append(" healthy");
            if (abnormal > 0) {
                sb.append(", ").append(abnormal).append(" 异常");
            }
            sb.append("）\n");
        }
        return sb.toString();
    }

    private List<WikiPageDO> prioritizeAbnormalPages(List<WikiPageDO> pages, int max) {
        List<WikiPageDO> abnormal = new ArrayList<>();
        List<WikiPageDO> normal = new ArrayList<>();
        for (WikiPageDO p : pages) {
            if (p.getHealthStatus() != null && !"healthy".equals(p.getHealthStatus())) {
                abnormal.add(p);
            } else {
                normal.add(p);
            }
        }
        List<WikiPageDO> result = new ArrayList<>(abnormal);
        int remaining = max - result.size();
        if (remaining > 0 && !normal.isEmpty()) {
            java.util.Collections.shuffle(normal);
            for (int i = 0; i < normal.size() && result.size() < max; i++) {
                result.add(normal.get(i));
            }
        }
        return result;
    }

    private String buildProbeUserPrompt(Long scopeId, GlobalSummary summary, String pageCatalog,
                                        String previousFindingsSummary, String previousHealthSummary,
                                        LintRulesConfig rules, boolean incremental) {
        StringBuilder sb = new StringBuilder();

        sb.append(summary.toCompactPrompt()).append("\n\n");

        String ingestDigest = buildIngestActivityDigest(scopeId);
        if (!ingestDigest.isEmpty()) {
            sb.append("## 今日 Ingest 活动（这些页面仅处理了同分类交叉引用，请重点关注跨分类交叉引用缺失）\n\n");
            sb.append(ingestDigest).append("\n\n");
        }

        if (incremental) {
            sb.append(pageCatalog).append("\n\n");
            sb.append("**请注意：以上为自上次 Lint 以来新增或变更的页面，请仅对这些变更页面进行诊断。**\n");
            sb.append("稳定页面无需重复诊断，请忽略未列出的页面。\n\n");
        } else {
            sb.append(pageCatalog).append("\n\n");
        }

        if (previousFindingsSummary != null && !previousFindingsSummary.isBlank()) {
            sb.append("## 上次Lint结果回顾\n\n");
            sb.append(previousFindingsSummary).append("\n\n");
        }

        if (previousHealthSummary != null && !previousHealthSummary.isBlank()) {
            sb.append("## 上次健康状态分布\n\n");
            sb.append(previousHealthSummary).append("\n\n");
        }

        sb.append("## 诊断标准参考\n\n");
        LintRulesConfig.DiagnosticStandard ds = rules.getDiagnosticStandard();
        sb.append("- 孤儿页面：入站链接数为0且创建超过 ").append(ds.getOrphanMinAgeDays()).append(" 天\n");
        sb.append("- 过时页面：来源文件修改时间晚于页面更新时间超过 ").append(ds.getStaleLagMediumDays())
            .append(" 天（中优先级）或 ").append(ds.getStaleLagHighDays()).append(" 天（高优先级）\n");
        sb.append("- 缺失交叉引用：共享至少 ").append(ds.getCrossrefMinSharedKeywords()).append(" 个关键词但无链接\n\n");

        sb.append("请按照系统提示中的格式要求，输出JSON数组。\n");
        return sb.toString();
    }

    private String buildIngestActivityDigest(Long scopeId) {
        try {
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(24);
            List<WikiPageDO> todayPages = wikiPageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .ge(WikiPageDO::getUpdatedAt, since)
                    .isNotNull(WikiPageDO::getFilePath)
                    .select(WikiPageDO::getId, WikiPageDO::getTitle, WikiPageDO::getFilePath, WikiPageDO::getCategory)
                    .last("LIMIT 50")
            );
            if (todayPages.isEmpty()) return "";

            Map<String, List<WikiPageDO>> byCategory = todayPages.stream()
                .filter(p -> p.getCategory() != null && !p.getCategory().isBlank())
                .collect(Collectors.groupingBy(WikiPageDO::getCategory));

            StringBuilder sb = new StringBuilder();
            sb.append("过去 24 小时 Ingest 创建了/更新了 ").append(todayPages.size()).append(" 个页面，分布在 ")
                .append(byCategory.size()).append(" 个分类中：\n");
            for (Map.Entry<String, List<WikiPageDO>> entry : byCategory.entrySet()) {
                sb.append("- [").append(entry.getKey()).append("] ");
                List<String> titles = entry.getValue().stream()
                    .map(p -> p.getTitle() != null ? p.getTitle() : p.getFilePath())
                    .limit(5)
                    .toList();
                sb.append(String.join("、", titles));
                if (entry.getValue().size() > 5) sb.append(" 等").append(entry.getValue().size()).append("个");
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to build ingest activity digest: {}", e.getMessage());
            return "";
        }
    }

    private List<Map<String, Object>> parseProbeOutput(String raw) {
        try {
            String json = raw;
            String trimmed = raw.trim();
            if (trimmed.startsWith("```json")) {
                json = trimmed.substring(7);
                if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
            } else if (trimmed.startsWith("```")) {
                json = trimmed.substring(3);
                if (json.endsWith("```")) json = json.substring(0, json.length() - 3);
            }
            json = json.trim();
            return OM.readValue(json, FINDING_LIST_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    public static class ProbeResult {
        private final List<Map<String, Object>> findings;
        private final String rawOutput;
        private final int inputPromptLength;
        private final String status;

        public ProbeResult(List<Map<String, Object>> findings, String rawOutput, int inputPromptLength) {
            this(findings, rawOutput, inputPromptLength, "success");
        }

        public ProbeResult(List<Map<String, Object>> findings, String rawOutput, int inputPromptLength, String status) {
            this.findings = findings != null ? findings : Collections.emptyList();
            this.rawOutput = rawOutput;
            this.inputPromptLength = inputPromptLength;
            this.status = status != null ? status : "success";
        }

        public static ProbeResult empty() {
            return new ProbeResult(Collections.emptyList(), null, 0, "success");
        }

        public static ProbeResult skipped() {
            return new ProbeResult(Collections.emptyList(), null, 0, "skipped");
        }

        public static ProbeResult withStatus(String status) {
            return new ProbeResult(Collections.emptyList(), null, 0, status);
        }

        public List<Map<String, Object>> getFindings() { return findings; }
        public String getRawOutput() { return rawOutput; }
        public int getInputPromptLength() { return inputPromptLength; }
        public String getStatus() { return status; }
        public boolean isEmpty() { return findings.isEmpty(); }

        public List<Map<String, Object>> getFindingsByType(String type) {
            return findings.stream()
                .filter(f -> type.equalsIgnoreCase(String.valueOf(f.get("type"))))
                .toList();
        }
    }
}