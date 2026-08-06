package org.cn.liuwt.llmwiki.domain.service.harness.edit;

import org.cn.liuwt.llmwiki.facade.model.FailedBlockInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SearchReplaceEngine {

    private static final Pattern SEARCH_REPLACE_PATTERN = Pattern.compile(
        "<{4,8}\\s*SEARCH\\s*\\r?\\n([\\s\\S]*?)\\r?\\n\\s*={4,}\\s*\\r?\\n([\\s\\S]*?)\\r?\\n\\s*>{4,8}\\s*REPLACE",
        Pattern.MULTILINE);

    public List<SearchReplaceBlock> parseBlocks(String text) {
        List<SearchReplaceBlock> blocks = new ArrayList<>();
        if (text == null) {
            return blocks;
        }
        Matcher matcher = SEARCH_REPLACE_PATTERN.matcher(text);
        while (matcher.find()) {
            blocks.add(new SearchReplaceBlock(matcher.group(1), matcher.group(2)));
        }
        return blocks;
    }

    public record SearchReplaceBlock(String search, String replace) {
    }

    public record ApplyResult(String content, int appliedCount, List<FailedBlockInfo> failedBlocks,
                              List<BlockOutcome> outcomes) {
    }

    public record BlockOutcome(SearchReplaceBlock block, boolean applied, FailedBlockInfo failure) {
    }

    public List<Integer> fuzzyLocateAll(String document, String search) {
        List<Integer> positions = new ArrayList<>();
        if (document == null || search == null || search.isEmpty()) {
            return positions;
        }
        int from = 0;
        while (from < document.length()) {
            int idx = document.indexOf(search, from);
            if (idx < 0) {
                break;
            }
            positions.add(idx);
            from = idx + search.length();
        }
        if (!positions.isEmpty()) {
            return positions;
        }
        String trailingStripped = search.stripTrailing();
        if (!trailingStripped.isEmpty() && !trailingStripped.equals(search)) {
            from = 0;
            while (from < document.length()) {
                int idx = document.indexOf(trailingStripped, from);
                if (idx < 0) {
                    break;
                }
                positions.add(idx);
                from = idx + trailingStripped.length();
            }
            if (!positions.isEmpty()) {
                return positions;
            }
        }
        String normalizedSearch = normalizeWhitespace(search);
        String normalizedDoc = normalizeWhitespace(document);
        from = 0;
        while (from < normalizedDoc.length()) {
            int normIdx = normalizedDoc.indexOf(normalizedSearch, from);
            if (normIdx < 0) {
                break;
            }
            positions.add(mapNormalizedIndexToOriginal(document, normalizedDoc, normIdx));
            from = normIdx + normalizedSearch.length();
        }
        return positions;
    }

    public int fuzzyFind(String document, String search) {
        List<Integer> positions = fuzzyLocateAll(document, search);
        return positions.isEmpty() ? -1 : positions.get(0);
    }

    public ApplyResult applyBlocks(String document, List<SearchReplaceBlock> blocks) {
        String result = document;
        int applied = 0;
        List<FailedBlockInfo> failed = new ArrayList<>();
        List<BlockOutcome> outcomes = new ArrayList<>();
        for (SearchReplaceBlock block : blocks) {
            List<Integer> positions = fuzzyLocateAll(result, block.search());
            if (positions.isEmpty()) {
                String stripped = block.search().strip();
                if (!stripped.equals(block.search())) {
                    positions = fuzzyLocateAll(result, stripped);
                }
            }
            if (positions.isEmpty()) {
                FailedBlockInfo failure = buildFailure(block.search(), "NOT_FOUND", 0, List.of());
                failed.add(failure);
                outcomes.add(new BlockOutcome(block, false, failure));
                continue;
            }
            if (positions.size() > 1) {
                FailedBlockInfo failure = buildFailure(block.search(), "AMBIGUOUS", positions.size(), toLineNumbers(result, positions));
                failed.add(failure);
                outcomes.add(new BlockOutcome(block, false, failure));
                continue;
            }
            int idx = positions.get(0);
            int end = matchEndOf(result, block.search(), idx);
            result = result.substring(0, idx) + block.replace() + result.substring(end);
            applied++;
            outcomes.add(new BlockOutcome(block, true, null));
        }
        return new ApplyResult(result, applied, failed, outcomes);
    }

    public int matchEndOf(String document, String search, int startIdx) {
        String matched = document.substring(startIdx, Math.min(document.length(), startIdx + search.length()));
        if (matched.equals(search)) {
            return startIdx + search.length();
        }
        int end = resolveNormalizedEnd(document, search, startIdx);
        if (end < 0) {
            end = findEndOfBlock(document, startIdx, search.strip());
        }
        return Math.min(end, document.length());
    }

    public int findEndOfBlock(String document, int startIdx, String stripped) {
        int endIdx = startIdx + stripped.length();
        while (endIdx < document.length()
                && (document.charAt(endIdx) == ' ' || document.charAt(endIdx) == '\t')) {
            endIdx++;
        }
        return endIdx;
    }

    public int toLineNumber(String document, int charIndex) {
        int line = 1;
        int limit = Math.min(charIndex, document.length());
        for (int i = 0; i < limit; i++) {
            if (document.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private List<Integer> toLineNumbers(String document, List<Integer> positions) {
        List<Integer> lines = new ArrayList<>();
        for (Integer pos : positions) {
            lines.add(toLineNumber(document, pos));
        }
        return lines;
    }

    private int resolveNormalizedEnd(String document, String search, int expectedStart) {
        String normalizedSearch = normalizeWhitespace(search);
        String normalizedDoc = normalizeWhitespace(document);
        int normIdx = normalizedDoc.indexOf(normalizedSearch);
        while (normIdx >= 0) {
            int start = mapNormalizedIndexToOriginal(document, normalizedDoc, normIdx);
            if (start == expectedStart) {
                return mapNormalizedIndexToOriginal(document, normalizedDoc, normIdx + normalizedSearch.length());
            }
            normIdx = normalizedDoc.indexOf(normalizedSearch, normIdx + 1);
        }
        return -1;
    }

    private FailedBlockInfo buildFailure(String search, String reason, int matchCount, List<Integer> matchLines) {
        FailedBlockInfo info = new FailedBlockInfo();
        String firstLine = search.lines().findFirst().orElse("");
        info.setSearchPreview(firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine);
        info.setReason(reason);
        if (matchCount > 0) {
            info.setMatchCount(matchCount);
            info.setMatchLines(matchLines);
        }
        return info;
    }

    private String normalizeWhitespace(String text) {
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\r\\n", "\n");
    }

    private int mapNormalizedIndexToOriginal(String original, String normalized, int normalizedIdx) {
        int origPos = 0;
        int normPos = 0;
        while (normPos < normalizedIdx && origPos < original.length()) {
            char c = original.charAt(origPos);
            if (c == '\r' && origPos + 1 < original.length() && original.charAt(origPos + 1) == '\n') {
                origPos++;
                continue;
            }
            if (c == ' ' || c == '\t') {
                origPos++;
                normPos++;
                while (origPos < original.length()
                        && (original.charAt(origPos) == ' ' || original.charAt(origPos) == '\t')) {
                    origPos++;
                }
                continue;
            }
            origPos++;
            normPos++;
        }
        return origPos;
    }
}
