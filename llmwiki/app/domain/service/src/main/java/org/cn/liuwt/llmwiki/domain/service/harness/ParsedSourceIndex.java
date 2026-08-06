package org.cn.liuwt.llmwiki.domain.service.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParsedSourceIndex {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String buildIndexJson(Long sourceId, String sourceName, String format,
                                         int totalChars, String documentType,
                                         List<DocumentStructureAnalyzer.Chapter> chapters,
                                         String sourceContent) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("sourceId", sourceId);
            root.put("name", sourceName != null ? sourceName : "");
            root.put("format", format != null ? format : "");
            root.put("totalChars", totalChars);
            root.put("documentType", documentType != null ? documentType : "NARRATIVE");

            ArrayNode headingsArr = root.putArray("headings");
            if (sourceContent != null) {
                extractAllHeadings(sourceContent, headingsArr);
            }

            ArrayNode chaptersArr = root.putArray("chapters");
            if (chapters != null) {
                for (DocumentStructureAnalyzer.Chapter ch : chapters) {
                    chaptersArr.add(buildChapterNode(ch));
                }
            }

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void extractAllHeadings(String content, ArrayNode arr) {
        Matcher matcher = HEADING_PATTERN.matcher(content);
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String text = matcher.group(2).strip();
            ObjectNode node = arr.addObject();
            node.put("level", level);
            node.put("text", text);
        }
    }

    private static ObjectNode buildChapterNode(DocumentStructureAnalyzer.Chapter ch) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("title", ch.title());
        node.put("level", ch.headingLevel());
        node.put("charCount", ch.sourceContent() != null ? ch.sourceContent().length() : 0);

        if (!ch.subChapters().isEmpty()) {
            ArrayNode subs = node.putArray("subChapters");
            for (DocumentStructureAnalyzer.Chapter sub : ch.subChapters()) {
                subs.add(buildChapterNode(sub));
            }
        }
        return node;
    }

    public static List<String> extractHeadingTexts(String indexJson) {
        List<String> result = new ArrayList<>();
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(indexJson);
            if (root.has("headings")) {
                for (var h : root.get("headings")) {
                    String text = h.has("text") ? h.get("text").asText() : null;
                    int level = h.has("level") ? h.get("level").asInt() : 1;
                    if (text != null) {
                        result.add("#".repeat(level) + " " + text);
                    }
                }
            }
        } catch (Exception e) {
            // silent
        }
        return result;
    }

    public static List<ChapterSummary> extractChapterSummaries(String indexJson) {
        List<ChapterSummary> result = new ArrayList<>();
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(indexJson);
            if (root.has("chapters")) {
                for (var ch : root.get("chapters")) {
                    String title = ch.has("title") ? ch.get("title").asText() : "";
                    int level = ch.has("level") ? ch.get("level").asInt() : 1;
                    int chars = ch.has("charCount") ? ch.get("charCount").asInt() : 0;
                    List<String> subTitles = new ArrayList<>();
                    if (ch.has("subChapters")) {
                        for (var sub : ch.get("subChapters")) {
                            subTitles.add(sub.has("title") ? sub.get("title").asText() : "");
                        }
                    }
                    result.add(new ChapterSummary(title, level, chars, subTitles));
                }
            }
        } catch (Exception e) {
            // silent
        }
        return result;
    }

    public record ChapterSummary(String title, int level, int charCount, List<String> subChapterTitles) {}
}
