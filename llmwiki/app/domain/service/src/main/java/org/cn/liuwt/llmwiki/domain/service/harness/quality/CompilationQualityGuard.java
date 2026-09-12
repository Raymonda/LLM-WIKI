package org.cn.liuwt.llmwiki.domain.service.harness.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CompilationQualityGuard {

    public record GuardResult(String content, List<String> fixes, List<String> warnings) {}

    private static final Pattern LINKED_DUP =
        Pattern.compile("\\[\\[\\s*([^\\[\\]\\n]{1,40})\\s*\\]\\][（(]\\s*\\1\\s*[）)]");
    private static final Pattern SELF_DUP_FULL =
        Pattern.compile("([^（）()\\n]{2,40})（\\1）");
    private static final Pattern SELF_DUP_HALF =
        Pattern.compile("([^（）()\\n]{2,40})\\(\\1\\)");
    private static final Pattern H1_LINE = Pattern.compile("^#\\s+(.+?)\\s*$");
    private static final Pattern ENTITY_TOKEN =
        Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]{4,}(?:有限公司|证券|基金|银行|集团|事务所)");
    private static final String[] SENTENCE_TERMINALS = {
        "。", "！", "？", "；", "：", "…", ".", "!", "?", ";", ":", "）", ")", "]", "】", "》", "」", "\"", "”"
    };
    private static final String[] TRUNCATION_SUFFIXES = {
        "宣布", "维持", "实施", "执行", "确认", "制定", "要求", "规定", "包括", "说明"
    };
    private static final int FREQUENCY_WARN_THRESHOLD = 4;

    private CompilationQualityGuard() {}

    public static GuardResult guard(String content, String pageTitleOrNull) {
        List<String> fixes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return new GuardResult(content, fixes, warnings);
        }
        String fixed = fixLinkedDuplicates(content, fixes);
        fixed = fixSelfDuplicates(fixed, SELF_DUP_FULL, fixes);
        fixed = fixSelfDuplicates(fixed, SELF_DUP_HALF, fixes);
        fixed = removeMatchingH1(fixed, pageTitleOrNull, fixes);
        collectWarnings(fixed, warnings);
        return new GuardResult(fixed, fixes, warnings);
    }

    private static String fixLinkedDuplicates(String content, List<String> fixes) {
        Matcher m = LINKED_DUP.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String matched = m.group();
            String replacement = matched.substring(0, matched.indexOf("]]") + 2);
            fixes.add("重复括号（链接）: \"" + matched + "\" → \"" + replacement + "\"");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String fixSelfDuplicates(String content, Pattern pattern, List<String> fixes) {
        Matcher m = pattern.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String replacement = m.group(1);
            fixes.add("重复括号: \"" + m.group() + "\" → \"" + replacement + "\"");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String removeMatchingH1(String content, String pageTitleOrNull, List<String> fixes) {
        if (pageTitleOrNull == null || pageTitleOrNull.isBlank()) {
            return content;
        }
        String[] lines = content.split("\n", -1);
        int idx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            idx = i;
            break;
        }
        if (idx < 0) return content;
        Matcher m = H1_LINE.matcher(lines[idx]);
        if (!m.matches()) return content;
        String h1Title = m.group(1).trim();
        if (!h1Title.equalsIgnoreCase(pageTitleOrNull.trim())) return content;

        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i == idx) continue;
            if (i == idx + 1 && lines[i].isBlank()) continue;
            kept.add(lines[i]);
        }
        fixes.add("移除内容 H1（与页面标题重复）: \"# " + h1Title + "\"");
        return String.join("\n", kept);
    }

    private static void collectWarnings(String content, List<String> warnings) {
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(">") || t.startsWith("|")) continue;
            if (t.length() < 8) continue;
            if (endsWithAny(t, SENTENCE_TERMINALS)) continue;
            if (endsWithAny(t, TRUNCATION_SUFFIXES)) {
                warnings.add("疑似截断行: \"" + t + "\"");
            }
        }
        for (String paragraph : content.split("\n\\s*\n")) {
            if (paragraph.isBlank() || paragraph.trim().startsWith("#")) continue;
            Map<String, Integer> counts = new LinkedHashMap<>();
            Matcher m = ENTITY_TOKEN.matcher(paragraph);
            while (m.find()) {
                counts.merge(m.group(), 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() >= FREQUENCY_WARN_THRESHOLD) {
                    warnings.add("实体名重复频次异常（x" + e.getValue() + "）: \"" + e.getKey() + "\"");
                }
            }
        }
    }

    private static boolean endsWithAny(String text, String[] suffixes) {
        for (String suffix : suffixes) {
            if (text.endsWith(suffix)) return true;
        }
        return false;
    }
}
