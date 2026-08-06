package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageLinkDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class GetRelatedPagesTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageLinkMapper wikiPageLinkMapper;

    @Tool(description = "通过链接图谱获取与给定页面有链接关系的页面（包括引用该页面的和被该页面引用的）")
    public List<RelatedPageResult> getRelatedPages(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "页面路径，如 'pages/microservice-architecture.md'") String pagePath
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);

        WikiPageDO currentPage = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeIdLong)
                .eq(WikiPageDO::getFilePath, pagePath)
        );

        if (currentPage == null) {
            return List.of();
        }

        String status = currentPage.getLifecycleStatus();
        if ("DEPRECATED".equals(status) || "MERGED".equals(status) || "DELETED".equals(status)) {
            log.info("tool=getRelatedPages scopeId={} pagePath={} rejected: lifecycleStatus={}", scopeId, pagePath, status);
            return List.of();
        }

        CompletableFuture<List<WikiPageLinkDO>> outgoingFuture = CompletableFuture.supplyAsync(
            () -> wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeIdLong)
                    .eq(WikiPageLinkDO::getFromPageId, currentPage.getId())
            ));

        CompletableFuture<List<WikiPageLinkDO>> incomingFuture = CompletableFuture.supplyAsync(
            () -> wikiPageLinkMapper.selectList(
                new LambdaQueryWrapper<WikiPageLinkDO>()
                    .eq(WikiPageLinkDO::getScopeId, scopeIdLong)
                    .eq(WikiPageLinkDO::getToPageId, currentPage.getId())
            ));

        List<WikiPageLinkDO> outgoingLinks = outgoingFuture.join();
        List<WikiPageLinkDO> incomingLinks = incomingFuture.join();

        List<Long> relatedPageIds = new ArrayList<>();
        for (WikiPageLinkDO link : outgoingLinks) {
            relatedPageIds.add(link.getToPageId());
        }
        for (WikiPageLinkDO link : incomingLinks) {
            relatedPageIds.add(link.getFromPageId());
        }

        if (relatedPageIds.isEmpty()) {
            return List.of();
        }

        List<WikiPageDO> relatedPages = wikiPageMapper.selectBatchIds(relatedPageIds);
        Map<Long, WikiPageDO> pageMap = relatedPages.stream()
            .filter(p -> !"DEPRECATED".equals(p.getLifecycleStatus())
                      && !"MERGED".equals(p.getLifecycleStatus())
                      && !"DELETED".equals(p.getLifecycleStatus()))
            .collect(Collectors.toMap(WikiPageDO::getId, p -> p));

        List<RelatedPageResult> results = new ArrayList<>();

        for (WikiPageLinkDO link : outgoingLinks) {
            WikiPageDO targetPage = pageMap.get(link.getToPageId());
            if (targetPage != null && targetPage.getScopeId().equals(scopeIdLong)) {
                RelatedPageResult result = new RelatedPageResult();
                result.title = targetPage.getTitle();
                result.path = RelatedPageResult.toReadPath(targetPage.getFilePath());
                result.summary = targetPage.getSummary();
                result.linkType = link.getLinkType();
                result.direction = "outgoing";
                results.add(result);
            }
        }

        for (WikiPageLinkDO link : incomingLinks) {
            WikiPageDO sourcePage = pageMap.get(link.getFromPageId());
            if (sourcePage != null && sourcePage.getScopeId().equals(scopeIdLong)) {
                RelatedPageResult result = new RelatedPageResult();
                result.title = sourcePage.getTitle();
                result.path = RelatedPageResult.toReadPath(sourcePage.getFilePath());
                result.summary = sourcePage.getSummary();
                result.linkType = link.getLinkType();
                result.direction = "incoming";
                results.add(result);
            }
        }

        log.info("tool=getRelatedPages scopeId={} pagePath={} outgoing={} incoming={} total={}",
            scopeId, pagePath, outgoingLinks.size(), incomingLinks.size(), results.size());
        return results;
    }

    public static class RelatedPageResult {
        public String title;
        public String path;
        public String summary;
        public String linkType;
        public String direction;

        private static String toReadPath(String dbPath) {
            if (dbPath != null && dbPath.startsWith("pages/")) {
                return "wiki/" + dbPath;
            }
            return dbPath;
        }
    }
}