package org.cn.liuwt.llmwiki.domain.service.harness.edit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SelectionAnchorer {

    public record AnchorResult(int startLine, int endLine, String status) {
    }

    private static final Pattern LINE_RANGE_PATTERN =
        Pattern.compile("L?(\\d+)(?:\\s*-\\s*L?(\\d+))?");

    private final SearchReplaceEngine searchReplaceEngine;

    public SelectionAnchorer(SearchReplaceEngine searchReplaceEngine) {
        this.searchReplaceEngine = searchReplaceEngine;
    }

    public AnchorResult anchor(String content, String selectedLines, String selectedText) {
        int totalLines = content == null || content.isEmpty() ? 1 : content.split("\n", -1).length;

        if (selectedText != null && !selectedText.isBlank()) {
            String text = selectedText.strip();
            List<Integer> positions = searchReplaceEngine.fuzzyLocateAll(content, text);
            if (positions.size() == 1) {
                int[] range = textLineRange(content, positions.get(0), text);
                return new AnchorResult(range[0], range[1], "exact");
            }
            if (positions.size() > 1) {
                int[] hint = parseLineRange(selectedLines);
                if (hint != null) {
                    int best = positions.get(0);
                    int bestDistance = Integer.MAX_VALUE;
                    for (Integer pos : positions) {
                        int line = searchReplaceEngine.toLineNumber(content, pos);
                        int distance = Math.abs(line - hint[0]);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = pos;
                        }
                    }
                    int[] range = textLineRange(content, best, text);
                    return new AnchorResult(range[0], range[1], "fuzzy");
                }
            }
        }

        int[] hint = parseLineRange(selectedLines);
        if (hint != null) {
            int start = clamp(hint[0], 1, totalLines);
            int end = clamp(hint[1], 1, totalLines);
            if (end < start) {
                end = start;
            }
            return new AnchorResult(start, end, "fallback-line");
        }

        return new AnchorResult(1, Math.max(1, totalLines), "fallback-full");
    }

    public int[] parseLineRange(String selectedLines) {
        if (selectedLines == null || selectedLines.isBlank()) {
            return null;
        }
        Matcher matcher = LINE_RANGE_PATTERN.matcher(selectedLines.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            int start = Integer.parseInt(matcher.group(1));
            int end = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : start;
            return new int[]{Math.min(start, end), Math.max(start, end)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int[] textLineRange(String content, int startPos, String text) {
        int startLine = searchReplaceEngine.toLineNumber(content, startPos);
        int end = searchReplaceEngine.matchEndOf(content, text, startPos);
        int lastChar = Math.max(startPos, Math.min(content.length() - 1, end - 1));
        int endLine = searchReplaceEngine.toLineNumber(content, lastChar);
        return new int[]{startLine, Math.max(startLine, endLine)};
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
