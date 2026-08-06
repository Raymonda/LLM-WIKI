package org.cn.liuwt.llmwiki.domain.service.harness.edit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

@Component
public class EditContextBuilder {

    private static final int FOCUSED_RADIUS = 15;
    private static final int EDGE_LINES = 5;
    private static final Pattern HEADING_PATTERN = Pattern.compile("(#{1,6})\\s+\\S.*");

    private final int maxContextChars;

    public EditContextBuilder(@Value("${llmwiki.edit.context.max-chars:24000}") int maxContextChars) {
        this.maxContextChars = Math.max(0, maxContextChars);
    }

    public String buildFullContext(String content, int anchorStart, int anchorEnd) {
        String[] lines = content.split("\n", -1);
        anchorStart = Math.max(1, Math.min(anchorStart, lines.length));
        anchorEnd = Math.max(anchorStart, Math.min(anchorEnd, lines.length));
        String full = renderLines(lines, anchorStart, anchorEnd, 1, lines.length);
        if (full.length() <= maxContextChars) {
            return full;
        }
        String header = "[文档共 " + lines.length + " 行，超出上下文预算，已裁剪，选中: L" + anchorStart + "-L" + anchorEnd + "]\n";
        StringBuilder skeletonBody = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (HEADING_PATTERN.matcher(lines[i]).matches()) {
                skeletonBody.append(" L").append(i + 1).append("| ").append(lines[i]).append("\n");
            }
        }
        int sectionStart = findSectionStart(lines, anchorStart);
        int sectionEnd = findSectionEnd(lines, anchorStart);
        String sectionHeader = "[选区所在章节 L" + sectionStart + "-L" + sectionEnd + "]\n";
        String sectionBody = renderLines(lines, anchorStart, anchorEnd, sectionStart, sectionEnd);
        String edgeStartHeader = "[文档开头 " + EDGE_LINES + " 行]\n";
        String edgeStartBody = renderLines(lines, anchorStart, anchorEnd, 1, Math.min(EDGE_LINES, lines.length));
        String edgeEndHeader = "[文档末尾 " + EDGE_LINES + " 行]\n";
        String edgeEndBody = renderLines(lines, anchorStart, anchorEnd, Math.max(1, lines.length - EDGE_LINES + 1), lines.length);
        int fixedOverhead = header.length() + "[大纲骨架]\n".length() + sectionHeader.length()
                + edgeStartHeader.length() + edgeEndHeader.length() + 2;
        int remaining = Math.max(0, maxContextChars - fixedOverhead);
        int[] grants = allocateBudget(remaining,
                sectionBody.length(), edgeEndBody.length(), edgeStartBody.length(), skeletonBody.length());
        StringBuilder sb = new StringBuilder();
        sb.append(header);
        sb.append("[大纲骨架]\n").append(fitToShare(skeletonBody.toString(), grants[3]));
        sb.append(sectionHeader).append(fitToShare(sectionBody, grants[0])).append("\n");
        sb.append(edgeStartHeader).append(fitToShare(edgeStartBody, grants[2])).append("\n");
        sb.append(edgeEndHeader).append(fitToShare(edgeEndBody, grants[1]));
        String result = sb.toString();
        if (result.length() > maxContextChars) {
            result = result.substring(0, maxContextChars);
        }
        return result;
    }

    private int[] allocateBudget(int remaining, int... demands) {
        int[] grants = new int[demands.length];
        for (int i = 0; i < demands.length; i++) {
            int grant = Math.min(remaining, demands[i]);
            grants[i] = grant;
            remaining -= grant;
        }
        return grants;
    }

    private String fitToShare(String body, int share) {
        if (body.length() <= share) {
            return body;
        }
        if (share <= 0) {
            return "";
        }
        return body.substring(0, share);
    }

    public String buildFocusedContext(String content, int anchorStart, int anchorEnd) {
        String[] lines = content.split("\n", -1);
        anchorStart = Math.max(1, Math.min(anchorStart, lines.length));
        anchorEnd = Math.max(anchorStart, Math.min(anchorEnd, lines.length));
        int from = Math.max(1, anchorStart - FOCUSED_RADIUS);
        int to = Math.min(lines.length, anchorEnd + FOCUSED_RADIUS);
        StringBuilder sb = new StringBuilder();
        String headingPath = buildHeadingPath(lines, anchorStart);
        if (!headingPath.isEmpty()) {
            sb.append("[位置: ").append(headingPath).append("]\n");
        }
        sb.append("[文档共 ").append(lines.length).append(" 行，显示 L").append(from).append("-L").append(to)
          .append("，选中: L").append(anchorStart).append("-L").append(anchorEnd).append("]\n");
        sb.append(renderLines(lines, anchorStart, anchorEnd, from, to));
        return sb.toString();
    }

    private String renderLines(String[] lines, int selStart, int selEnd, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to && i <= lines.length; i++) {
            boolean selected = i >= selStart && i <= selEnd;
            sb.append(selected ? ">>" : "  ")
              .append(" L").append(i).append("| ").append(lines[i - 1]).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private int findSectionStart(String[] lines, int anchorLine) {
        for (int i = anchorLine - 1; i >= 0; i--) {
            if (HEADING_PATTERN.matcher(lines[i]).matches()) {
                return i + 1;
            }
        }
        return 1;
    }

    private int findSectionEnd(String[] lines, int anchorLine) {
        int startIdx = findSectionStart(lines, anchorLine) - 1;
        int level = headingLevel(lines[startIdx]);
        for (int i = anchorLine; i < lines.length; i++) {
            if (HEADING_PATTERN.matcher(lines[i]).matches() && headingLevel(lines[i]) <= level) {
                return i;
            }
        }
        return lines.length;
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    private String buildHeadingPath(String[] lines, int anchorLine) {
        Deque<String> stack = new ArrayDeque<>();
        int currentLevel = Integer.MAX_VALUE;
        for (int i = anchorLine - 1; i >= 0; i--) {
            if (HEADING_PATTERN.matcher(lines[i]).matches()) {
                int level = headingLevel(lines[i]);
                if (level < currentLevel) {
                    stack.push(lines[i].replaceAll("^#+\\s+", ""));
                    currentLevel = level;
                }
                if (level == 1) {
                    break;
                }
            }
        }
        return String.join(" > ", stack);
    }
}
