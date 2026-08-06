package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class LinkWritingService {

    private static final Logger log = LoggerFactory.getLogger(LinkWritingService.class);

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    private static final Pattern MD_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern SOURCE_FILE_EXT_PATTERN = Pattern.compile("\\.(md|pdf|docx?|txt|rst|html?|csv|xlsx?|pptx?|json|yaml|yml|xml|adoc)$", Pattern.CASE_INSENSITIVE);

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Autowired
    private StorageProvider storageProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public int applyLinkSuggestions(String linkSuggestionsJson, Long scopeId) {
        return applyLinkSuggestionsWithExecution(linkSuggestionsJson, scopeId, null);
    }

    public int applyLinkSuggestionsWithExecution(String linkSuggestionsJson, Long scopeId, Long executionId) {
        if (linkSuggestionsJson == null || linkSuggestionsJson.isBlank()) {
            return 0;
        }
        List<LinkSuggestion> suggestions = parseSuggestions(linkSuggestionsJson);
        if (suggestions.isEmpty()) {
            return 0;
        }
        String scopeIdStr = String.valueOf(scopeId);
        int appliedCount = 0;
        for (LinkSuggestion s : suggestions) {
            try {
                // 设置 executionId
                LinkSuggestion suggestionWithExec = new LinkSuggestion(
                    s.fromPage(), s.toPage(), s.linkType(), 
                    s.linkContext(), s.confidence(), s.createdBy(), executionId
                );
                if (upsertLinkRecord(scopeId, suggestionWithExec)) {
                    appliedCount++;
                }
                // Phase 1 后不再自动同步到 FS
                // syncWikiLinkToFile(scopeId, scopeIdStr, suggestionWithExec);
            } catch (Exception e) {
                log.warn("Failed to apply link suggestion {} -> {}: {}", s.fromPage(), s.toPage(), e.getMessage());
            }
        }
        log.info("Applied {} link suggestions for scope {} (executionId={})", appliedCount, scopeId, executionId);
        return appliedCount;
    }

    List<LinkSuggestion> parseSuggestions(String json) {
        List<LinkSuggestion> suggestions = new ArrayList<>();
        try {
            String cleanJson = extractJsonArray(json);
            if (cleanJson == null || cleanJson.isBlank()) {
                return suggestions;
            }
            List<Map<String, Object>> rawList = objectMapper.readValue(
                cleanJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> item : rawList) {
                String fromPage = coalesce(item, "fromPage", "sourceTitle");
                String toPage = coalesce(item, "toPage", "targetTitle");
                String linkType = (String) item.get("linkType");
                String linkContext = (String) item.get("linkContext");
                
                Double confidence = null;
                Object confObj = item.get("confidence");
                if (confObj instanceof Number) {
                    confidence = ((Number) confObj).doubleValue();
                } else if (confObj instanceof String) {
                    try {
                        confidence = Double.parseDouble((String) confObj);
                    } catch (NumberFormatException ignore) {}
                }
                
                Object shouldLinkObj = item.get("shouldLink");
                if (shouldLinkObj instanceof Boolean && !((Boolean) shouldLinkObj)) {
                    log.debug("AI suggested shouldLink=false, skipping link {} -> {}", fromPage, toPage);
                    continue;
                }
                
                if (fromPage != null && !fromPage.isBlank()
                    && toPage != null && !toPage.isBlank()) {
                    suggestions.add(new LinkSuggestion(
                        fromPage.trim(),
                        toPage.trim(),
                        linkType != null ? linkType.trim() : "related",
                        linkContext,
                        confidence,
                        "lint_ai",
                        null
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse link suggestions JSON: {}", e.getMessage());
        }
        return suggestions;
    }

    private String coalesce(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    public boolean upsertLinkRecord(Long scopeId, LinkSuggestion suggestion) {
        WikiPageDO fromPageDO = resolvePage(scopeId, suggestion.fromPage());
        WikiPageDO toPageDO = resolvePage(scopeId, suggestion.toPage());
        if (fromPageDO == null || toPageDO == null) {
            log.debug("Skip link: fromPage={} or toPage={} not found in DB", suggestion.fromPage(), suggestion.toPage());
            return false;
        }

        WikiPageLinkDO existingLink = wikiPageLinkMapper.selectOne(
            new LambdaQueryWrapper<WikiPageLinkDO>()
                .eq(WikiPageLinkDO::getScopeId, scopeId)
                .eq(WikiPageLinkDO::getFromPageId, fromPageDO.getId())
                .eq(WikiPageLinkDO::getToPageId, toPageDO.getId())
        );

        if (existingLink != null) {
            // 更新现有链接（仅当有linkContext或confidence时）
            boolean needUpdate = false;
            if (suggestion.linkContext() != null && !suggestion.linkContext().isBlank()) {
                existingLink.setLinkContext(suggestion.linkContext());
                needUpdate = true;
            }
            if (suggestion.confidence() != null) {
                existingLink.setConfidence(suggestion.confidence());
                needUpdate = true;
            }
            if (!suggestion.linkType().equals(existingLink.getLinkType())) {
                existingLink.setLinkType(suggestion.linkType());
                needUpdate = true;
            }
            if (needUpdate) {
                wikiPageLinkMapper.updateById(existingLink);
            }
        } else {
            // 创建新链接
            WikiPageLinkDO newLink = new WikiPageLinkDO();
            newLink.setScopeId(scopeId);
            newLink.setFromPageId(fromPageDO.getId());
            newLink.setToPageId(toPageDO.getId());
            newLink.setLinkType(suggestion.linkType());
            newLink.setLinkContext(suggestion.linkContext());
            newLink.setCreatedBy(suggestion.createdBy() != null ? suggestion.createdBy() : "lint_ai");
            newLink.setConfidence(suggestion.confidence());
            newLink.setExecutionId(suggestion.executionId());
            wikiPageLinkMapper.insert(newLink);
        }
        return true;
    }

    WikiPageDO resolvePage(Long scopeId, String identifier) {
        if (identifier == null || identifier.isBlank()) return null;

        WikiPageDO byPath = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, identifier)
        );
        if (byPath != null) return byPath;

        if (!identifier.contains("/") && !identifier.endsWith(".md")) {
            WikiPageDO byTitle = wikiPageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageDO>()
                    .eq(WikiPageDO::getScopeId, scopeId)
                    .eq(WikiPageDO::getTitle, identifier)
            );
            if (byTitle != null) return byTitle;
        }

        return null;
    }

    /**
     * @deprecated Phase 1 后不再自动同步链接到 FS 文件，改为纯 DB 存储。
     * 此方法保留向后兼容，但默认不再调用。
     */
    @Deprecated
    void syncWikiLinkToFile(Long scopeId, String scopeIdStr, LinkSuggestion suggestion) {
        WikiPageDO toPageDO = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeId)
                .eq(WikiPageDO::getFilePath, suggestion.toPage)
        );
        if (toPageDO == null || toPageDO.getTitle() == null || toPageDO.getTitle().isBlank()) {
            return;
        }
        String toPageTitle = toPageDO.getTitle();
        String wikiLinkSyntax = "[[" + toPageTitle + "]]";

        String fromStoragePath = suggestion.fromPage.startsWith("pages/") ? "wiki/" + suggestion.fromPage : suggestion.fromPage;
        byte[] fileBytes = storageProvider.read(scopeIdStr, fromStoragePath);
        if (fileBytes == null) {
            return;
        }
        String content = new String(fileBytes, StandardCharsets.UTF_8);

        if (content.contains(wikiLinkSyntax)) {
            return;
        }

        String updated = insertWikiLink(content, toPageTitle, wikiLinkSyntax);
        if (!updated.equals(content)) {
            storageProvider.write(scopeIdStr, fromStoragePath, updated.getBytes(StandardCharsets.UTF_8));
            log.debug("Synced wiki link [[{}]] to file {}", toPageTitle, fromStoragePath);
        }
    }

    String insertWikiLink(String content, String toPageTitle, String wikiLinkSyntax) {
        String[] segments = content.split("```", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            if (i % 2 == 1) {
                result.append("```").append(segments[i]).append("```");
            } else {
                result.append(replaceFirstPlainText(segments[i], toPageTitle, wikiLinkSyntax));
            }
        }

        if (result.length() > 0 && result.charAt(result.length() - 1) == '`') {
            int trailingBackticks = 0;
            int idx = result.length() - 1;
            while (idx >= 0 && result.charAt(idx) == '`') {
                trailingBackticks++;
                idx--;
            }
            result.setLength(result.length() - trailingBackticks);
        }

        String updated = result.toString();

        if (!updated.contains(wikiLinkSyntax)) {
            updated = appendToRelatedSection(updated, wikiLinkSyntax);
        }

        return updated;
    }

    private String replaceFirstPlainText(String segment, String title, String wikiLinkSyntax) {
        if (segment.contains(wikiLinkSyntax)) {
            return segment;
        }

        int idx = segment.indexOf(title);
        if (idx < 0) {
            return segment;
        }

        boolean alreadyLinked = false;
        Matcher m = WIKI_LINK_PATTERN.matcher(segment);
        while (m.find()) {
            if (m.group(1).trim().equals(title)) {
                alreadyLinked = true;
                break;
            }
        }
        if (alreadyLinked) {
            return segment;
        }

        boolean isInsideHeading = false;
        int lineStart = segment.lastIndexOf('\n', idx);
        if (lineStart < 0) lineStart = 0;
        String linePrefix = segment.substring(lineStart, idx);
        if (linePrefix.matches("^#{1,6}\\s*$")) {
            isInsideHeading = true;
        }

        if (isInsideHeading) {
            return segment;
        }

        StringBuilder sb = new StringBuilder(segment);
        sb.replace(idx, idx + title.length(), wikiLinkSyntax);
        return sb.toString();
    }

    private String appendToRelatedSection(String content, String wikiLinkSyntax) {
        String[] relatedHeaders = {"## 关联页面", "## 相关页面", "## 关系与关联"};
        for (String header : relatedHeaders) {
            if (content.contains(header)) {
                int insertIdx = content.indexOf(header) + header.length();
                int nextLineEnd = content.indexOf('\n', insertIdx);
                if (nextLineEnd < 0) {
                    return content + "\n- " + wikiLinkSyntax + "\n";
                }
                return content.substring(0, nextLineEnd + 1)
                    + "- " + wikiLinkSyntax + "\n"
                    + content.substring(nextLineEnd + 1);
            }
        }

        int refIdx = content.lastIndexOf("## 参考来源");
        if (refIdx >= 0) {
            String before = content.substring(0, refIdx);
            String after = content.substring(refIdx);
            return before + "## 关联页面\n\n- " + wikiLinkSyntax + "\n\n" + after;
        }

        return content + "\n\n## 关联页面\n\n- " + wikiLinkSyntax + "\n";
    }

    private String extractJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return null;
    }

    record LinkSuggestion(
        String fromPage, 
        String toPage, 
        String linkType,
        String linkContext,
        Double confidence,
        String createdBy,
        Long executionId
    ) {
        // 向后兼容构造函数
        public LinkSuggestion(String fromPage, String toPage, String linkType) {
            this(fromPage, toPage, linkType, null, null, "manual", null);
        }
    }

    public String sanitizeSourceLinks(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        Matcher matcher = MD_LINK_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            String linkText = matcher.group(1);
            String href = matcher.group(2);
            if (isSourceReferenceLink(content, matcher.start(), matcher.end(), href)) {
                sb.append(content, lastEnd, matcher.start());
                sb.append("[").append(linkText).append("](source-ref:").append(linkText).append(")");
                lastEnd = matcher.end();
                log.debug("Sanitized source reference link: [{}]({})  →  source-ref:{}", linkText, href, linkText);
            }
        }
        if (lastEnd == 0) {
            return content;
        }
        sb.append(content, lastEnd, content.length());
        return sb.toString();
    }

    private boolean isSourceReferenceLink(String content, int matchStart, int matchEnd, String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            int ctxStart = Math.max(0, matchStart - 20);
            String context = content.substring(ctxStart, matchStart).toLowerCase();
            if (context.contains("来源：") || context.contains("来源:")) {
                return true;
            }
            String hrefLower = href.toLowerCase();
            if (SOURCE_FILE_EXT_PATTERN.matcher(hrefLower.replaceAll("[/]$", "")).find()) {
                return true;
            }
            return false;
        }
        if (href.startsWith("wiki/") || href.startsWith("pages/")) {
            return false;
        }
        if (SOURCE_FILE_EXT_PATTERN.matcher(href).find()) {
            return true;
        }
        return false;
    }

    public String sanitizeWikiLinks(String content, Long scopeId) {
        if (content == null || content.isBlank()) {
            return content;
        }
        Set<String> linkTargets = new LinkedHashSet<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            linkTargets.add(matcher.group(1).trim());
        }
        if (linkTargets.isEmpty()) {
            return content;
        }

        Set<String> existingTitles = wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .select(WikiPageDO::getTitle)
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getTitle, linkTargets)
        ).stream()
            .map(WikiPageDO::getTitle)
            .collect(Collectors.toSet());

        String result = content;
        for (String target : linkTargets) {
            if (!existingTitles.contains(target)) {
                String linkSyntax = "[[" + target + "]]";
                if (result.contains(linkSyntax)) {
                    result = result.replace(linkSyntax, target);
                    log.debug("Sanitized ghost link [[{}]] in scope {}", target, scopeId);
                }
            }
        }
        return result;
    }

    public int syncContentLinks(Long scopeId, Map<Long, String> pageIdToContent, Long executionId) {
        if (pageIdToContent == null || pageIdToContent.isEmpty()) {
            return 0;
        }

        Map<Long, Set<String>> pageLinkMap = new HashMap<>();
        Set<String> allTargets = new LinkedHashSet<>();
        for (Map.Entry<Long, String> entry : pageIdToContent.entrySet()) {
            String content = entry.getValue();
            if (content == null || content.isBlank()) continue;
            Set<String> targets = new LinkedHashSet<>();
            Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
            while (matcher.find()) {
                String target = matcher.group(1).trim();
                if (!target.isEmpty()) {
                    targets.add(target);
                    allTargets.add(target);
                }
            }
            if (!targets.isEmpty()) {
                pageLinkMap.put(entry.getKey(), targets);
            }
        }
        if (allTargets.isEmpty()) {
            return 0;
        }

        Map<String, Long> titleToId = new HashMap<>();
        wikiPageMapper.selectList(
            new LambdaQueryWrapper<WikiPageDO>()
                .select(WikiPageDO::getId, WikiPageDO::getTitle)
                .eq(WikiPageDO::getScopeId, scopeId)
                .in(WikiPageDO::getTitle, allTargets)
        ).forEach(p -> titleToId.put(p.getTitle(), p.getId()));

        int created = 0;
        for (Map.Entry<Long, Set<String>> entry : pageLinkMap.entrySet()) {
            Long fromPageId = entry.getKey();
            for (String targetTitle : entry.getValue()) {
                Long toPageId = titleToId.get(targetTitle);
                if (toPageId == null || toPageId.equals(fromPageId)) continue;

                WikiPageLinkDO existing = wikiPageLinkMapper.selectOne(
                    new LambdaQueryWrapper<WikiPageLinkDO>()
                        .eq(WikiPageLinkDO::getScopeId, scopeId)
                        .eq(WikiPageLinkDO::getFromPageId, fromPageId)
                        .eq(WikiPageLinkDO::getToPageId, toPageId)
                );
                if (existing != null) continue;

                WikiPageLinkDO newLink = new WikiPageLinkDO();
                newLink.setScopeId(scopeId);
                newLink.setFromPageId(fromPageId);
                newLink.setToPageId(toPageId);
                newLink.setLinkType("related");
                newLink.setCreatedBy("content_link");
                newLink.setConfidence(1.0);
                newLink.setExecutionId(executionId);
                wikiPageLinkMapper.insert(newLink);
                created++;
            }
        }
        if (created > 0) {
            log.info("syncContentLinks: created {} content-based links for scope {}", created, scopeId);
        }
        return created;
    }
}
