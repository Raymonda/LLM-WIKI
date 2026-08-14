package org.cn.liuwt.llmwiki.domain.service.harness.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiReferencePathParser {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]\\(\\(?([^()\\s]+)\\)\\)?");

    private WikiReferencePathParser() {
    }

    public static List<String> extractPaths(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(answer);
        while (matcher.find()) {
            String path = matcher.group(2);
            if (path.startsWith("wiki/")) {
                path = path.substring(5);
            }
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }
}
