package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageSourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageSourceMapper;
import org.cn.liuwt.llmwiki.domain.service.harness.ParsedSourceIndex;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class GetSourceInfoTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Autowired
    private WikiPageSourceMapper wikiPageSourceMapper;

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Tool(description = "查询某个 Wiki 页面的原始来源文档信息。返回该页面编译自哪些原始文档（ID、名称、格式、大小、章节结构），用于需要了解知识的原始出处、或需要用 readRawSource 工具读取原始文档详情时的前置查询。返回的章节结构（chapters）可用于 readRawSource 的 sectionHeading 参数精准定位")
    public List<SourceInfoResult> getSourceInfo(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "页面路径，如 'pages/compliance-management.md'") String pagePath
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);

        WikiPageDO page = wikiPageMapper.selectOne(
            new LambdaQueryWrapper<WikiPageDO>()
                .eq(WikiPageDO::getScopeId, scopeIdLong)
                .eq(WikiPageDO::getFilePath, pagePath)
        );

        if (page == null) {
            log.info("tool=getSourceInfo scopeId={} pagePath='{}' found=false", scopeId, pagePath);
            return List.of();
        }

        // Lifecycle check: reject DEPRECATED/MERGED/DELETED pages
        String status = page.getLifecycleStatus();
        if ("DEPRECATED".equals(status) || "MERGED".equals(status) || "DELETED".equals(status)) {
            log.info("tool=getSourceInfo scopeId={} pagePath='{}' rejected: lifecycleStatus={}", scopeId, pagePath, status);
            return List.of();
        }

        List<WikiPageSourceDO> relations = wikiPageSourceMapper.selectList(
            new LambdaQueryWrapper<WikiPageSourceDO>()
                .eq(WikiPageSourceDO::getScopeId, scopeIdLong)
                .eq(WikiPageSourceDO::getPageId, page.getId())
        );

        if (relations.isEmpty()) {
            log.info("tool=getSourceInfo scopeId={} pagePath='{}' pageTitle='{}' sources=0",
                scopeId, pagePath, page.getTitle());
            return List.of();
        }

        List<Long> sourceIds = new ArrayList<>();
        for (WikiPageSourceDO rel : relations) {
            sourceIds.add(rel.getSourceId());
        }

        List<SourceDO> sources = sourceMapper.selectBatchIds(sourceIds);

        List<SourceInfoResult> results = new ArrayList<>();
        for (SourceDO source : sources) {
            SourceInfoResult info = new SourceInfoResult();
            info.sourceId = source.getId();
            info.name = source.getName();
            info.format = source.getFormat();
            info.size = source.getSize();
            info.status = source.getStatus();
            String indexJson = readIndexJson(scopeId, source.getId());
            info.hasParsed = checkParsedExists(scopeId, source.getId());
            info.hasIndex = indexJson != null;
            if (indexJson != null) {
                info.chapters = ParsedSourceIndex.extractChapterSummaries(indexJson);
                info.headingCount = ParsedSourceIndex.extractHeadingTexts(indexJson).size();
            } else {
                info.chapters = List.of();
                info.headingCount = 0;
            }
            results.add(info);
        }

        log.info("tool=getSourceInfo scopeId={} pagePath='{}' pageTitle='{}' sources={}",
            scopeId, pagePath, page.getTitle(), results.size());
        return results;
    }

    private String readIndexJson(String scopeId, Long sourceId) {
        String indexPath = "parsed/" + sourceId + ".index.json";
        try {
            byte[] bytes = storageProvider.read(scopeId, indexPath);
            if (bytes != null && bytes.length > 0) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Failed to read index.json for sourceId={}: {}", sourceId, e.getMessage());
        }
        return null;
    }

    private boolean checkParsedExists(String scopeId, Long sourceId) {
        String parsedPath = "parsed/" + sourceId + ".parsed.md";
        try {
            byte[] bytes = storageProvider.read(scopeId, parsedPath);
            return bytes != null && bytes.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static class SourceInfoResult {
        public Long sourceId;
        public String name;
        public String format;
        public Long size;
        public String status;
        public boolean hasParsed;
        public boolean hasIndex;
        public int headingCount;
        public List<ParsedSourceIndex.ChapterSummary> chapters;
    }
}
