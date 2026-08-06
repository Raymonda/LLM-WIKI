package org.cn.liuwt.llmwiki.domain.service.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentStructureAnalyzer {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern RULE_KEYWORD_PATTERN = Pattern.compile(
        "应当|必须|规定|条款|不得|禁止|负责|要求|流程|制度|办法|细则|应当|严禁|可以|有权|无权|"
        + "适用|审批|审核|批准|备案|执行|实施|监督|检查|处罚|违规|违反|合规|职责|权限|"
        + "第一章|第二章|第三章|第四章|第五章|第[一二三四五六七八九十]+章|第[一二三四五六七八九十]+条"
    );

    private static final Pattern AUTHORITATIVE_TITLE_PATTERN = Pattern.compile(
        "条例|规定|规则|制度|办法|细则|规程|标准|规范|准则|"
        + "合同|协议|条款|章程|守则|公约|"
        + "告知书|说明书|承诺书|授权书|委托书|"
        + "操作手册|操作指南|作业指导书|"
        + "产品质量|检验检测|审计|合规"
    );

    private static final Pattern CN_PART_PATTERN = Pattern.compile(
        "^第[一二三四五六七八九十百]+部分\\s*(.+)?$"
    );
    private static final Pattern CN_CHAPTER_PATTERN = Pattern.compile(
        "^第[一二三四五六七八九十百]+章\\s*(.+)?$"
    );
    private static final Pattern TOC_DOT_PATTERN = Pattern.compile(
        ".*\\.{2,}.*\\d+\\s*$"
    );

    private static final int MIN_H1_FOR_STRUCTURED = 3;
    private static final double RULE_DENSITY_THRESHOLD = 0.003;
    private static final int MIN_HEADINGS_FOR_VALID_EXTRACTION = 2;

    public enum DocumentType { NARRATIVE, STRUCTURED }

    public record Chapter(String title, int headingLevel, String sourceContent,
                          List<Integer> chunkIndices, List<Chapter> subChapters) {}

    public record StructureReport(DocumentType type, List<Chapter> chapters,
                                   int totalH1, int totalH2, int totalH3,
                                   double ruleDensity, double codeBlockRatio, double tableRatio) {}

    public StructureReport analyze(String sourceContent, List<DocumentChunker.Chunk> chunks) {
        return analyze(sourceContent, chunks, null);
    }

    public StructureReport analyze(String sourceContent, List<DocumentChunker.Chunk> chunks, String sourceName) {
        if (sourceContent == null || sourceContent.isBlank()) {
            return new StructureReport(DocumentType.NARRATIVE, List.of(), 0, 0, 0, 0.0, 0.0, 0.0);
        }

        String[] lines = sourceContent.split("\n");
        List<HeadingInfo> headings = extractHeadings(lines);

        int h1Count = (int) headings.stream().filter(h -> h.level == 1).count();
        int h2Count = (int) headings.stream().filter(h -> h.level == 2).count();
        int h3Count = (int) headings.stream().filter(h -> h.level == 3).count();
        double ruleDensity = computeRuleDensity(sourceContent);
        double codeBlockRatio = countCodeBlocks(sourceContent);
        double tableRatio = countTableLines(sourceContent);

        DocumentType type = classifyDocumentType(h1Count, h2Count, ruleDensity, sourceContent.length(), sourceName);

        boolean useH1 = h1Count >= 2;
        int boundaryLevel = useH1 ? 1 : 2;
        List<Chapter> chapters = extractChapters(lines, headings, chunks, boundaryLevel);

        if (chapters.size() < MIN_HEADINGS_FOR_VALID_EXTRACTION && type == DocumentType.STRUCTURED) {
            List<HeadingInfo> cnHeadings = extractChineseHeadings(lines);
            if (cnHeadings.size() >= MIN_HEADINGS_FOR_VALID_EXTRACTION) {
                headings = mergeHeadings(headings, cnHeadings);
                h1Count = (int) headings.stream().filter(h -> h.level == 1).count();
                h2Count = (int) headings.stream().filter(h -> h.level == 2).count();
                h3Count = (int) headings.stream().filter(h -> h.level == 3).count();
                boundaryLevel = h1Count >= 2 ? 1 : 2;
                chapters = extractChapters(lines, headings, chunks, boundaryLevel);
            }
        }

        return new StructureReport(type, chapters, h1Count, h2Count, h3Count,
                                    ruleDensity, codeBlockRatio, tableRatio);
    }

    private DocumentType classifyDocumentType(int h1Count, int h2Count, double ruleDensity, int totalLength, String sourceName) {
        if (sourceName != null && AUTHORITATIVE_TITLE_PATTERN.matcher(sourceName).find()) {
            return DocumentType.STRUCTURED;
        }
        if (h1Count >= MIN_H1_FOR_STRUCTURED && ruleDensity > RULE_DENSITY_THRESHOLD) {
            return DocumentType.STRUCTURED;
        }
        if (h1Count >= 2 && h2Count >= 6 && ruleDensity > RULE_DENSITY_THRESHOLD * 1.5) {
            return DocumentType.STRUCTURED;
        }
        if (ruleDensity > RULE_DENSITY_THRESHOLD * 3 && totalLength > 20000) {
            return DocumentType.STRUCTURED;
        }
        return DocumentType.NARRATIVE;
    }

    private double computeRuleDensity(String content) {
        if (content == null || content.isEmpty()) return 0.0;
        Matcher matcher = RULE_KEYWORD_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return (double) count / content.length();
    }

    private List<HeadingInfo> extractHeadings(String[] lines) {
        List<HeadingInfo> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                int level = m.group(1).length();
                String title = m.group(2).trim();
                headings.add(new HeadingInfo(level, title, i));
            }
        }
        return headings;
    }

    private List<HeadingInfo> extractChineseHeadings(String[] lines) {
        List<HeadingInfo> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (TOC_DOT_PATTERN.matcher(trimmed).matches()) continue;

            Matcher partMatcher = CN_PART_PATTERN.matcher(trimmed);
            if (partMatcher.matches()) {
                int level = isNearPageBreak(lines, i) ? 1 : 3;
                headings.add(new HeadingInfo(level, trimmed, i));
                continue;
            }

            Matcher chapterMatcher = CN_CHAPTER_PATTERN.matcher(trimmed);
            if (chapterMatcher.matches()) {
                headings.add(new HeadingInfo(2, trimmed, i));
            }
        }
        return headings;
    }

    private boolean isNearPageBreak(String[] lines, int lineIndex) {
        int searchRange = 3;
        for (int i = Math.max(0, lineIndex - searchRange); i < lineIndex; i++) {
            if (lines[i].trim().equals("---")) return true;
        }
        return false;
    }

    private List<HeadingInfo> mergeHeadings(List<HeadingInfo> markdown, List<HeadingInfo> chinese) {
        List<HeadingInfo> merged = new ArrayList<>(markdown);
        java.util.Set<Integer> existingLines = markdown.stream()
            .map(h -> h.lineIndex)
            .collect(java.util.stream.Collectors.toSet());
        for (HeadingInfo h : chinese) {
            if (!existingLines.contains(h.lineIndex)) {
                merged.add(h);
            }
        }
        merged.sort(java.util.Comparator.comparingInt(h -> h.lineIndex));
        return merged;
    }

    private List<Chapter> extractChapters(String[] lines, List<HeadingInfo> headings,
                                           List<DocumentChunker.Chunk> chunks, int boundaryLevel) {
        List<Chapter> chapters = new ArrayList<>();
        List<HeadingInfo> boundaries = headings.stream()
            .filter(h -> h.level <= boundaryLevel)
            .toList();

        if (boundaries.isEmpty()) {
            return chapters;
        }

        for (int i = 0; i < boundaries.size(); i++) {
            HeadingInfo current = boundaries.get(i);
            int startLine = current.lineIndex;
            int endLine = (i + 1 < boundaries.size()) ? boundaries.get(i + 1).lineIndex : lines.length;

            StringBuilder content = new StringBuilder();
            for (int j = startLine; j < endLine; j++) {
                content.append(lines[j]).append("\n");
            }

            List<Integer> chunkIndices = mapLinesToChunks(startLine, endLine, chunks);

            List<Chapter> subChapters = extractSubChapters(lines, headings, current.lineIndex, endLine, boundaryLevel + 1);

            chapters.add(new Chapter(
                current.title,
                current.level,
                content.toString().trim(),
                chunkIndices,
                subChapters
            ));
        }

        return chapters;
    }

    private List<Chapter> extractSubChapters(String[] lines, List<HeadingInfo> allHeadings,
                                              int parentStart, int parentEnd, int subLevel) {
        List<Chapter> subs = new ArrayList<>();
        List<HeadingInfo> subHeadings = allHeadings.stream()
            .filter(h -> h.level == subLevel && h.lineIndex > parentStart && h.lineIndex < parentEnd)
            .toList();

        for (int i = 0; i < subHeadings.size(); i++) {
            HeadingInfo sub = subHeadings.get(i);
            int start = sub.lineIndex;
            int end = (i + 1 < subHeadings.size()) ? subHeadings.get(i + 1).lineIndex : parentEnd;

            StringBuilder content = new StringBuilder();
            for (int j = start; j < end; j++) {
                content.append(lines[j]).append("\n");
            }
            subs.add(new Chapter(sub.title, sub.level, content.toString().trim(), List.of(), List.of()));
        }
        return subs;
    }

    private List<Integer> mapLinesToChunks(int startLine, int endLine, List<DocumentChunker.Chunk> chunks) {
        List<Integer> indices = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) return indices;

        int charOffset = 0;
        int currentLine = 0;
        for (int ci = 0; ci < chunks.size(); ci++) {
            String chunkContent = chunks.get(ci).content();
            int chunkEndChar = charOffset + chunkContent.length();
            int chunkEndLine = currentLine + chunkContent.split("\n", -1).length - 1;

            if (chunkEndLine >= startLine && currentLine < endLine) {
                indices.add(ci);
            }

            charOffset = chunkEndChar;
            currentLine = chunkEndLine + 1;
        }
        return indices;
    }

    private double countCodeBlocks(String content) {
        if (content == null || content.isEmpty()) return 0.0;
        int codeBlockChars = 0;
        boolean inCodeBlock = false;
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
            } else if (inCodeBlock) {
                codeBlockChars += line.length() + 1;
            }
        }
        return (double) codeBlockChars / content.length();
    }

    private double countTableLines(String content) {
        if (content == null || content.isEmpty()) return 0.0;
        String[] lines = content.split("\n");
        int tableLines = 0;
        for (String line : lines) {
            if (line.trim().startsWith("|") && line.indexOf("|", 1) > 0) {
                tableLines++;
            }
        }
        int totalChars = content.length();
        int avgLineLen = lines.length > 0 ? totalChars / lines.length : 1;
        return (double) (tableLines * avgLineLen) / totalChars;
    }

    private record HeadingInfo(int level, String title, int lineIndex) {}
}
