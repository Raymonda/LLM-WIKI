package org.cn.liuwt.llmwiki.domain.service.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocumentChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final int MAX_CHUNK_CHARS = 12000;
    private static final int OVERLAP_LINES = 3;

    public List<Chunk> chunk(String markdown, List<DocumentStructureAnalyzer.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) {
            return chunk(markdown);
        }
        List<Chunk> chunks = new ArrayList<>();
        int idx = 0;
        for (DocumentStructureAnalyzer.Chapter chapter : chapters) {
            String content = chapter.sourceContent();
            if (content == null || content.isBlank()) continue;
            if (content.length() <= MAX_CHUNK_CHARS) {
                chunks.add(new Chunk(content.trim(), idx, countLines(content)));
            } else {
                List<String> subChunks = splitByParagraphs(content);
                for (String sub : subChunks) {
                    chunks.add(new Chunk(sub.trim(), idx, countLines(sub)));
                }
            }
            idx++;
        }
        return chunks.isEmpty() ? chunk(markdown) : chunks;
    }

    private static final int PARAGRAPH_OVERLAP = 2;

    private List<String> splitByParagraphs(String content) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = content.split("\n\n");
        StringBuilder current = new StringBuilder();
        List<String> recentParagraphs = new ArrayList<>();
        for (String para : paragraphs) {
            if (current.length() + para.length() + 2 > MAX_CHUNK_CHARS && current.length() > 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
                int overlapStart = Math.max(0, recentParagraphs.size() - PARAGRAPH_OVERLAP);
                for (int j = overlapStart; j < recentParagraphs.size(); j++) {
                    current.append(recentParagraphs.get(j)).append("\n\n");
                }
            }
            current.append(para).append("\n\n");
            recentParagraphs.add(para);
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private int countLines(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count + 1;
    }

    public List<Chunk> chunk(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);

        List<Integer> headingIndices = new ArrayList<>();
        List<String> headingPrefixes = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING_PATTERN.matcher(lines[i]);
            if (m.matches()) {
                headingIndices.add(i);
                headingPrefixes.add(m.group(1) + " " + m.group(2));
            }
        }

        if (headingIndices.isEmpty()) {
            return chunkBySize(markdown, "");
        }

        for (int hi = 0; hi < headingIndices.size(); hi++) {
            int start = headingIndices.get(hi);
            int end = (hi + 1 < headingIndices.size()) ? headingIndices.get(hi + 1) : lines.length;

            StringBuilder context = new StringBuilder();
            int ci = hi - 1;
            while (ci >= 0 && headingPrefixes.get(ci).startsWith("#")) {
                context.insert(0, headingPrefixes.get(ci) + "\n");
                ci--;
            }

            StringBuilder sb = new StringBuilder(context);
            for (int i = start; i < end && i < lines.length; i++) {
                sb.append(lines[i]).append("\n");
                if (sb.length() > MAX_CHUNK_CHARS) {
                    chunks.add(new Chunk(sb.toString().trim(), hi, end - start));
                    sb = new StringBuilder(context);
                    int overlapStart = Math.max(start, i - OVERLAP_LINES);
                    for (int j = overlapStart; j <= i && j < end; j++) {
                        sb.append(lines[j]).append("\n");
                    }
                }
            }
            if (sb.length() > context.length() + 10) {
                chunks.add(new Chunk(sb.toString().trim(), hi, end - start));
            }
        }

        return chunks;
    }

    private List<Chunk> chunkBySize(String text, String titleContext) {
        List<Chunk> chunks = new ArrayList<>();
        int totalLen = text.length();
        int pos = 0;
        int idx = 0;

        while (pos < totalLen) {
            int end = Math.min(pos + MAX_CHUNK_CHARS, totalLen);
            String chunk = text.substring(pos, end);
            if (!titleContext.isEmpty()) {
                chunk = titleContext + "\n\n" + chunk;
            }
            chunks.add(new Chunk(chunk, idx, end - pos));
            if (end >= totalLen) {
                pos = totalLen;
            } else {
                pos = end - OVERLAP_LINES * 80;
                if (pos <= 0) pos = end;
            }
            idx++;
        }

        return chunks;
    }

    public record Chunk(String content, int index, int approximateLineCount) {}
}
