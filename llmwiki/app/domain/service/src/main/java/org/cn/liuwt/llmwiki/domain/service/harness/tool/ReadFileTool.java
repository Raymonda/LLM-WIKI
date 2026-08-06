package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.common.dal.dataobject.WikiPageDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.WikiPageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReadFileTool {

    private static final Logger log = LoggerFactory.getLogger("harness.tool");

    private static final int MAX_RETURN_CHARS = 20000;

    private static final ThreadLocal<Map<String, String>> FILE_CACHE = ThreadLocal.withInitial(HashMap::new);

    public static void clearCache() {
        FILE_CACHE.remove();
    }

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private WikiPageMapper wikiPageMapper;

    @Tool(description = "读取 wiki-data 中的文件（Wiki 页面）。path 是相对于 wiki-data/{scopeId}/ 的路径。支持两种格式：'wiki/pages/xxx.md'（完整存储路径）或 'pages/xxx.md'（数据库 filePath 格式，会自动补 'wiki/' 前缀）。超过 20000 字符的文件会截断返回并提示文件大小")
    public String readFile(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "文件路径，相对于 wiki-data/{scopeId}/。支持 'wiki/pages/xxx.md' 或 'pages/xxx.md' 格式，后者会自动补 'wiki/' 前缀") String path
    ) {
        String originalPath = path;
        if (path != null && path.startsWith("parsed/")) {
            return "请参考对应的 Wiki 参考页获取原文内容，参考页包含该文档的结构化摘要和完整原文。使用 searchWiki 搜索相关关键词定位具体参考页。";
        }

        String cacheKey = scopeId + ":" + path;
        Map<String, String> localCache = FILE_CACHE.get();
        String cached = localCache.get(cacheKey);
        if (cached != null) {
            log.debug("tool=readFile scopeId={} path={} cache-hit=true", scopeId, originalPath);
            return cached;
        }

        try {
            String resolvedPath = resolveWikiPath(path);

            // Lifecycle check: reject DEPRECATED/MERGED/DELETED pages
            String dbFilePath = resolvedPath.startsWith("wiki/") ? resolvedPath.substring(5) : resolvedPath;
            if (dbFilePath.startsWith("pages/")) {
                try {
                    Long scopeIdLong = Long.parseLong(scopeId);
                    WikiPageDO page = wikiPageMapper.selectOne(
                        new LambdaQueryWrapper<WikiPageDO>()
                            .eq(WikiPageDO::getScopeId, scopeIdLong)
                            .eq(WikiPageDO::getFilePath, dbFilePath)
                    );
                    if (page != null) {
                        String status = page.getLifecycleStatus();
                        if ("DEPRECATED".equals(status) || "MERGED".equals(status) || "DELETED".equals(status)) {
                            log.info("tool=readFile scopeId={} path={} rejected: lifecycleStatus={}", scopeId, originalPath, status);
                            return "⚠️ 页面 " + dbFilePath + " 已被标记为 " + status + "（" +
                                (page.getDeprecatedReason() != null ? page.getDeprecatedReason() : "无原因") +
                                "），不可引用此页面内容。请使用 searchWiki 或 listPages 查找其他 ACTIVE 状态的页面。";
                        }
                    }
                } catch (Exception e) {
                    log.debug("Lifecycle check failed for path={}: {}", dbFilePath, e.getMessage());
                }
            }

            byte[] content = storageProvider.read(scopeId, resolvedPath);

            if (content == null && !resolvedPath.equals(path)) {
                content = storageProvider.read(scopeId, path);
                if (content != null) {
                    String resolvedText = new String(content, StandardCharsets.UTF_8);
                    resolvedText = appendImageRefs(resolvedText, originalPath);
                    String result = truncateIfNeeded(resolvedText, originalPath, content.length);
                    localCache.put(cacheKey, result);
                    log.info("tool=readFile scopeId={} path={} hit=true bytes={} (auto-resolved)", scopeId, path, content.length);
                    return result;
                }
            }

            if (content == null) {
                log.info("tool=readFile scopeId={} path={} hit=false (not found)", scopeId, originalPath);
                return "文件不存在: " + resolvedPath;
            }

            String text = new String(content, StandardCharsets.UTF_8);
            text = appendImageRefs(text, originalPath);
            String result = truncateIfNeeded(text, originalPath, content.length);
            localCache.put(cacheKey, result);
            log.info("tool=readFile scopeId={} path={} hit=true bytes={}", scopeId, originalPath, content.length);
            return result;
        } catch (Exception e) {
            log.warn("tool=readFile scopeId={} path={} error={}", scopeId, originalPath, e.getMessage());
            return "读取文件失败: " + originalPath + " - " + e.getMessage();
        }
    }

    @Tool(description = "读取 wiki-data 中的文件（Wiki 页面）的指定章节内容。path 是相对于 wiki-data/{scopeId}/ 的路径。sectionHeading 是 Markdown 标题文本（如 '风险评估'、'概述'），返回该标题下的完整章节内容（到下一个同级或更高级标题为止）。支持 'wiki/pages/xxx.md' 或 'pages/xxx.md' 格式。当只需要某个页面的特定章节而非全文时使用此工具，可大幅节省 token")
    public String readFileSection(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "文件路径，相对于 wiki-data/{scopeId}/。支持 'wiki/pages/xxx.md' 或 'pages/xxx.md' 格式") String path,
        @ToolParam(description = "Markdown 标题文本（不含 # 前缀），如 '风险评估'、'合规管理要求'。支持模糊匹配") String sectionHeading
    ) {
        if (sectionHeading == null || sectionHeading.isBlank()) {
            return "sectionHeading 参数不能为空，请提供目标章节标题";
        }
        String fileContent = readFile(scopeId, path);
        if (fileContent == null || fileContent.isBlank()) {
            return "文件内容为空或读取失败: " + path;
        }
        if (fileContent.startsWith("文件不存在") || fileContent.startsWith("读取文件失败")) {
            return fileContent;
        }

        String strippedHeading = sectionHeading.strip().replaceAll("^#+\\s*", "");
        String section = extractSection(fileContent, strippedHeading);
        if (section != null) {
            log.info("tool=readFileSection scopeId={} path={} heading='{}' found=true chars={}",
                scopeId, path, strippedHeading, section.length());
            return truncateIfNeeded(section, path + "#" + strippedHeading, section.length());
        }

        List<String> allHeadings = collectHeadings(fileContent);
        log.info("tool=readFileSection scopeId={} path={} heading='{}' found=false availableHeadings={}",
            scopeId, path, strippedHeading, allHeadings.size());

        String fuzzyMatch = findFuzzyHeading(allHeadings, strippedHeading);
        if (fuzzyMatch != null) {
            String fuzzySection = extractSection(fileContent, fuzzyMatch);
            if (fuzzySection != null) {
                log.info("tool=readFileSection scopeId={} path={} fuzzyMatch='{}' chars={}",
                    scopeId, path, fuzzyMatch, fuzzySection.length());
                return truncateIfNeeded(fuzzySection, path + "#" + fuzzyMatch, fuzzySection.length());
            }
        }

        StringBuilder msg = new StringBuilder();
        msg.append("未找到标题 '").append(strippedHeading).append("' 对应的章节。\n");
        if (!allHeadings.isEmpty()) {
            msg.append("\n可用章节标题（请从中选择）：\n");
            int limit = Math.min(20, allHeadings.size());
            for (int i = 0; i < limit; i++) {
                msg.append("- ").append(allHeadings.get(i)).append("\n");
            }
            if (allHeadings.size() > 20) {
                msg.append("... 共 ").append(allHeadings.size()).append(" 个章节\n");
            }
        }
        return msg.toString();
    }

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern IMG_REF_PATTERN = Pattern.compile("!\\[([^\\]]*?)]\\(([^)]+?)\\)");

    private String appendImageRefs(String content, String pagePath) {
        if (!content.contains("![")) return content;
        Matcher m = IMG_REF_PATTERN.matcher(content);
        List<String> imageLines = new ArrayList<>();
        while (m.find() && imageLines.size() < 10) {
            String desc = m.group(1);
            String path = m.group(2);
            if (path != null && !path.isBlank() && !path.startsWith("http") && !path.startsWith("data:")) {
                imageLines.add("- `![" + (desc != null ? desc : "") + "](" + path + ")`");
            }
        }
        if (imageLines.isEmpty()) return content;
        StringBuilder sb = new StringBuilder(content);
        sb.append("\n\n---\n> **本页包含图片引用**（回答时可在相关内容处使用 `![描述](路径)` 引用，前端会自动渲染）：\n");
        for (String line : imageLines) {
            sb.append("> ").append(line).append("\n");
        }
        return sb.toString();
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

    private String findFuzzyHeading(List<String> headings, String target) {
        String targetLower = target.toLowerCase();
        for (String h : headings) {
            String hText = h.replaceAll("^#+\\s*", "").toLowerCase();
            if (hText.contains(targetLower) || targetLower.contains(hText)) {
                return h.replaceAll("^#+\\s*", "");
            }
        }
        return null;
    }

    private String truncateIfNeeded(String content, String path, int originalBytes) {
        if (content.length() <= MAX_RETURN_CHARS) {
            return content;
        }
        log.info("tool=readFile scopeId=- path={} truncated: {} chars > {} limit", path, content.length(), MAX_RETURN_CHARS);
        String truncated = content.substring(0, MAX_RETURN_CHARS);
        return truncated + "\n\n---\n⚠️ 文件已截断：原文件 " + formatCharCount(content.length())
            + " 字符（" + formatByteCount(originalBytes) + "），仅返回前 " + formatCharCount(MAX_RETURN_CHARS) + " 字符。"
            + "如需查看特定段落，请用 searchWiki 搜索关键词定位。";
    }

    private String formatCharCount(int chars) {
        if (chars < 1000) return chars + "";
        return String.format("%.1fK", chars / 1000.0);
    }

    private String formatByteCount(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }

    private String resolveWikiPath(String path) {
        if (path == null || path.isBlank()) return path;
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.startsWith("pages/") || p.startsWith("index.md") || p.startsWith("log.md")) {
            return "wiki/" + p;
        }
        return p;
    }
}