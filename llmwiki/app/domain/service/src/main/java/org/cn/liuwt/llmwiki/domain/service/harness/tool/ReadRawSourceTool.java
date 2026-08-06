package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReadRawSourceTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    private static final int MAX_RETURN_CHARS = 30000;
    private static final int PARSED_MAX_CHARS = 50000;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    @Autowired
    private SourceMapper sourceMapper;

    @Autowired
    private StorageProvider storageProvider;

    @Tool(description = "读取知识库的原始来源文档（raw 层）。当 Wiki 编译页面的信息不够详细、需要查看原始文档的完整细节时使用。支持通过 sectionHeading 精准读取特定章节。优先返回 parsed 版本（结构化摘要，信息密度高），parsed 不存在时 fallback 到 raw 原始文件。sourceId 是来源文档 ID，可通过 getSourceInfo 工具获取。当 sectionHeading 传空字符串时返回文档目录结构（章节列表和标题列表），不返回正文内容")
    public String readRawSource(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "来源文档 ID（整数），可通过 getSourceInfo 工具获取") Long sourceId,
        @ToolParam(description = "可选：Markdown 标题文本（不含 # 前缀），如 '第三章 风险评估'。提供时只返回匹配章节的内容。传空字符串时返回文档目录结构（章节+标题列表）") String sectionHeading
    ) {
        Long scopeIdLong = Long.parseLong(scopeId);
        SourceDO source = sourceMapper.selectById(sourceId);
        if (source == null || !source.getScopeId().equals(scopeIdLong)) {
            return "来源文档不存在或无权限: sourceId=" + sourceId;
        }

        if (sectionHeading != null && sectionHeading.isBlank()) {
            String tocResult = buildTableOfContents(scopeId, sourceId, source);
            if (tocResult != null) {
                log.info("tool=readRawSource scopeId={} sourceId={} name='{}' mode=toc", scopeId, sourceId, source.getName());
                return tocResult;
            }
        }

        String content = readParsedContent(scopeId, sourceId);
        String sourceType = "parsed";
        if (content == null) {
            content = readRawContent(scopeId, source);
            sourceType = "raw";
            if (content == null) {
                return "无法读取来源文档内容: " + source.getName() + " (sourceId=" + sourceId + ")";
            }
        }

        if (sectionHeading != null && !sectionHeading.isBlank()) {
            String strippedHeading = sectionHeading.strip().replaceAll("^#+\\s*", "");
            String section = extractSection(content, strippedHeading);
            if (section != null) {
                log.info("tool=readRawSource scopeId={} sourceId={} name='{}' type={} heading='{}' found=true chars={}",
                    scopeId, sourceId, source.getName(), sourceType, strippedHeading, section.length());
                return truncateContent(section, source.getName() + "#" + strippedHeading);
            }

            List<String> headings = collectHeadings(content);
            log.info("tool=readRawSource scopeId={} sourceId={} heading='{}' found=false", scopeId, sourceId, strippedHeading);
            return buildSectionNotFoundError(strippedHeading, headings, source.getName(), sourceType);
        }

        log.info("tool=readRawSource scopeId={} sourceId={} name='{}' type={} chars={}",
            scopeId, sourceId, source.getName(), sourceType, content.length());
        return truncateContent(content, source.getName());
    }

    private String buildTableOfContents(String scopeId, Long sourceId, SourceDO source) {
        String indexJson = readIndexJson(scopeId, sourceId);
        if (indexJson == null) {
            return null;
        }

        List<ParsedSourceIndex.ChapterSummary> chapters = ParsedSourceIndex.extractChapterSummaries(indexJson);
        List<String> headings = ParsedSourceIndex.extractHeadingTexts(indexJson);

        StringBuilder sb = new StringBuilder();
        sb.append("文档: ").append(source.getName()).append(" (sourceId=").append(sourceId).append(")\n");
        sb.append("总字符数: 约 ").append(source.getSize() != null ? source.getSize() : "未知").append("\n\n");

        if (!chapters.isEmpty()) {
            sb.append("## 章节结构\n");
            for (ParsedSourceIndex.ChapterSummary ch : chapters) {
                sb.append("- ").append("#".repeat(ch.level())).append(" ").append(ch.title());
                sb.append(" (").append(ch.charCount()).append(" 字符)");
                sb.append("\n");
                for (String sub : ch.subChapterTitles()) {
                    sb.append("  - ").append(sub).append("\n");
                }
            }
            sb.append("\n");
        }

        if (!headings.isEmpty()) {
            sb.append("## 完整标题列表（可作为 sectionHeading 参数）\n");
            int limit = Math.min(headings.size(), 50);
            for (int i = 0; i < limit; i++) {
                sb.append("- ").append(headings.get(i)).append("\n");
            }
            if (headings.size() > 50) {
                sb.append("... 共 ").append(headings.size()).append(" 个标题\n");
            }
        }

        sb.append("\n提示: 请使用上方的章节标题作为 sectionHeading 参数调用 readRawSource 精准读取对应章节内容。\n");
        return sb.toString();
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

    private String readParsedContent(String scopeId, Long sourceId) {
        String parsedPath = "parsed/" + sourceId + ".parsed.md";
        try {
            byte[] bytes = storageProvider.read(scopeId, parsedPath);
            if (bytes != null && bytes.length > 0) {
                String content = new String(bytes, StandardCharsets.UTF_8);
                if (content.length() > PARSED_MAX_CHARS) {
                    return content.substring(0, PARSED_MAX_CHARS) + "\n\n...(parsed 内容过长已截断)";
                }
                return content;
            }
        } catch (Exception e) {
            log.debug("Failed to read parsed file for sourceId={}: {}", sourceId, e.getMessage());
        }
        return null;
    }

    private String readRawContent(String scopeId, SourceDO source) {
        String filePath = source.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = storageProvider.read(scopeId, filePath);
            if (bytes != null && bytes.length > 0) {
                String content = new String(bytes, StandardCharsets.UTF_8);
                return content;
            }
        } catch (Exception e) {
            log.debug("Failed to read raw file for sourceId={}: {}", source.getId(), e.getMessage());
        }
        return null;
    }

    private String extractSection(String content, String headingText) {
        Matcher matcher = HEADING_PATTERN.matcher(content);
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String text = matcher.group(2).strip();
            if (text.equalsIgnoreCase(headingText) || text.contains(headingText)) {
                int sectionStart = matcher.end();
                int sectionEnd = content.length();

                Matcher endMatcher = HEADING_PATTERN.matcher(content);
                endMatcher.region(sectionStart, content.length());
                while (endMatcher.find()) {
                    int nextLevel = endMatcher.group(1).length();
                    if (nextLevel <= level) {
                        sectionEnd = endMatcher.start();
                        break;
                    }
                }

                String sectionContent = content.substring(sectionStart, sectionEnd).strip();
                return "### " + text + "\n\n" + sectionContent;
            }
        }
        return null;
    }

    private List<String> collectHeadings(String content) {
        List<String> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);
        while (matcher.find()) {
            String text = matcher.group(2).strip();
            int level = matcher.group(1).length();
            headings.add("#".repeat(level) + " " + text);
        }
        return headings;
    }

    private String buildSectionNotFoundError(String heading, List<String> headings, String sourceName, String sourceType) {
        StringBuilder msg = new StringBuilder();
        msg.append("来源文档 '").append(sourceName).append("' (").append(sourceType).append(") 中未找到标题 '").append(heading).append("'。\n");
        if (!headings.isEmpty()) {
            msg.append("\n可用章节标题：\n");
            int limit = Math.min(20, headings.size());
            for (int i = 0; i < limit; i++) {
                msg.append("- ").append(headings.get(i)).append("\n");
            }
            if (headings.size() > 20) {
                msg.append("... 共 ").append(headings.size()).append(" 个章节\n");
            }
        } else {
            msg.append("该文档没有 Markdown 标题结构，请使用 readFileSection 指定精确关键词或读取全文。");
        }
        return msg.toString();
    }

    private String truncateContent(String content, String label) {
        if (content.length() <= MAX_RETURN_CHARS) {
            return content;
        }
        return content.substring(0, MAX_RETURN_CHARS)
            + "\n\n---\n⚠️ 原始文档已截断：共 " + content.length() + " 字符，仅返回前 "
            + MAX_RETURN_CHARS + " 字符。请使用 sectionHeading 参数精准读取特定章节。";
    }
}
