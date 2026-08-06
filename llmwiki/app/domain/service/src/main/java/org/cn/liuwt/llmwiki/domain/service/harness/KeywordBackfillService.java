package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageKeywordDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageKeywordMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keyword 按需补全服务 — Lint 前置步骤。
 * 扫描 ACTIVE 且 wiki_page_keyword 为空的页面，用轻量级 LLM 提取 3-5 个关键词写入。
 * 仅对 reference/summary/entity/manual 类型页面生效。
 */
@Service
public class KeywordBackfillService {

    private static final Logger log = LoggerFactory.getLogger(KeywordBackfillService.class);
    private static final int MAX_PAGES_PER_RUN = 50;
    private static final int MAX_CONTENT_CHARS = 1500;

    @Autowired(required = false)
    private LlmClient chatClient;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageKeywordMapper wikiPageKeywordMapper;

    @Autowired
    private StorageProvider storageProvider;

    /**
     * 执行 keyword 补全。返回本次补全的页面数。
     * 如果 focusPageIds 非空，只补全这些页面中缺 keyword 的。
     */
    public int backfill(Long scopeId, Set<Long> focusPageIds) {
        if (chatClient == null || !chatClient.isAvailable()) {
            log.debug("KeywordBackfill: AI unavailable, skipping for scopeId={}", scopeId);
            return 0;
        }

        List<WikiPageDO> pagesWithoutKw = wikiPageMapper.selectPagesWithoutKeywords(scopeId, MAX_PAGES_PER_RUN);
        if (pagesWithoutKw.isEmpty()) return 0;

        List<WikiPageDO> targetPages;
        if (focusPageIds != null && !focusPageIds.isEmpty()) {
            targetPages = pagesWithoutKw.stream()
                .filter(p -> p.getId() != null && focusPageIds.contains(p.getId()))
                .collect(Collectors.toList());
        } else {
            targetPages = pagesWithoutKw;
        }

        if (targetPages.isEmpty()) return 0;

        int backfilled = 0;
        for (WikiPageDO page : targetPages) {
            try {
                List<String> keywords = extractKeywords(scopeId, page);
                if (keywords.isEmpty()) continue;

                for (String kw : keywords) {
                    String trimmed = kw.trim().toLowerCase();
                    if (trimmed.isEmpty() || trimmed.length() > 50) continue;
                    Long existing = wikiPageKeywordMapper.selectCount(
                        new LambdaQueryWrapper<WikiPageKeywordDO>()
                            .eq(WikiPageKeywordDO::getScopeId, scopeId)
                            .eq(WikiPageKeywordDO::getPageId, page.getId())
                            .eq(WikiPageKeywordDO::getKeyword, trimmed)
                    );
                    if (existing != null && existing > 0) continue;
                    WikiPageKeywordDO kwDO = new WikiPageKeywordDO();
                    kwDO.setScopeId(scopeId);
                    kwDO.setPageId(page.getId());
                    kwDO.setKeyword(trimmed);
                    wikiPageKeywordMapper.insert(kwDO);
                }
                backfilled++;
            } catch (Exception e) {
                log.warn("KeywordBackfill failed for pageId={}: {}", page.getId(), e.getMessage());
            }
        }

        if (backfilled > 0) {
            log.info("KeywordBackfill: scopeId={}, backfilled={}/{} pages", scopeId, backfilled, targetPages.size());
        }
        return backfilled;
    }

    private List<String> extractKeywords(Long scopeId, WikiPageDO page) {
        String content = readPageContent(scopeId, page);
        if (content == null || content.length() < 50) {
            content = page.getSummary() != null ? page.getSummary() : page.getTitle();
        }
        String truncated = content.substring(0, Math.min(content.length(), MAX_CONTENT_CHARS));

        String prompt = "请从以下 Wiki 页面内容中提取 3-5 个核心关键词，用逗号分隔。"
            + "关键词应该是领域术语、专有名词或核心概念，不要提取过于宽泛的词。\n\n"
            + "标题：" + page.getTitle() + "\n"
            + (page.getCategory() != null ? "分类：" + page.getCategory() + "\n" : "")
            + "内容：\n" + truncated + "\n\n"
            + "请直接输出关键词，用逗号分隔，不要添加任何解释。";

        String response = chatClient.chat(prompt);
        if (response == null || response.isBlank()) return List.of();

        List<String> keywords = new ArrayList<>();
        for (String part : response.split("[,，;；\\n]")) {
            String kw = part.trim();
            if (!kw.isEmpty()) keywords.add(kw);
            if (keywords.size() >= 5) break;
        }
        return keywords;
    }

    private String readPageContent(Long scopeId, WikiPageDO page) {
        if (page.getFilePath() == null || page.getFilePath().isBlank()) return null;
        try {
            byte[] bytes = storageProvider.read(String.valueOf(scopeId), "wiki/" + page.getFilePath());
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
