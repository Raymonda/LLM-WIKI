package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EntityPageEntryParser {

    public record Entry(int lineIndex, String section, String claim, List<String> sources) {}

    public record ParseResult(List<String> lines, List<Entry> entries) {}

    private static final Pattern BULLET_LINE = Pattern.compile("^(\\s*[-*]\\s+)(.+)$");
    private static final Pattern SOURCE_SUFFIX = Pattern.compile("[（(]\\s*来源[：:]\\s*([^）)]+)[）)]\\s*$");

    private EntityPageEntryParser() {}

    public static ParseResult parse(String content) {
        List<String> lines = new ArrayList<>();
        if (content != null) {
            for (String line : content.split("\n", -1)) {
                lines.add(line);
            }
        }
        List<Entry> entries = new ArrayList<>();
        String currentSection = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("## ")) {
                currentSection = trimmed.substring(3).trim();
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith(">") || trimmed.startsWith("|")) {
                continue;
            }
            Matcher bullet = BULLET_LINE.matcher(line);
            if (!bullet.matches()) {
                continue;
            }
            String body = bullet.group(2).trim();
            List<String> sources = new ArrayList<>();
            Matcher sm = SOURCE_SUFFIX.matcher(body);
            if (sm.find()) {
                for (String s : sm.group(1).split("[；;]")) {
                    String t = s.trim();
                    if (!t.isEmpty()) {
                        sources.add(t);
                    }
                }
                body = body.substring(0, sm.start()).trim();
            }
            entries.add(new Entry(i, currentSection, body, sources));
        }
        return new ParseResult(lines, entries);
    }

    public static String formatSourceSuffix(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return "（来源：" + String.join("；", sources) + "）";
    }

    public static String formatForPrompt(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        String currentSection = null;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.section().equals(currentSection)) {
                currentSection = e.section();
                sb.append("【").append(currentSection.isEmpty() ? "未分节" : currentSection).append("】\n");
            }
            sb.append("- [").append(i).append("] ").append(e.claim());
            sb.append(formatSourceSuffix(e.sources()));
            sb.append("\n");
        }
        return sb.toString();
    }
}
