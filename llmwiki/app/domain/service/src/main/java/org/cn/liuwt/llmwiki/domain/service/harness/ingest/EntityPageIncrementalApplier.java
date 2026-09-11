package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EntityPageIncrementalApplier {

    public record CandidateEntry(String section, String claim, String source, String quote, String relation) {}

    public record ConflictEvent(int existingLineIndex, String existingClaim, String newClaim, String source) {}

    public record ApplyResult(String content, int added, int mergedSources,
                              List<ConflictEvent> conflicts, List<String> skipped) {}

    private static final Pattern BULLET_PREFIX = Pattern.compile("^(\\s*[-*]\\s+)");

    private EntityPageIncrementalApplier() {}

    public static ApplyResult apply(String existingContent, List<CandidateEntry> candidates) {
        List<String> lines = splitLines(existingContent);
        List<EntityPageEntryParser.Entry> baseEntries =
            EntityPageEntryParser.parse(String.join("\n", lines)).entries();

        int added = 0;
        int mergedSources = 0;
        List<ConflictEvent> conflicts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        if (candidates != null) {
            for (CandidateEntry candidate : candidates) {
                String relation = candidate.relation() == null ? "new" : candidate.relation().trim().toLowerCase();
                boolean isDuplicate = relation.startsWith("duplicate_of:");
                boolean isConflict = relation.startsWith("conflict_with:");

                if ((isDuplicate || isConflict) && (candidate.source() == null || candidate.source().isBlank())) {
                    skipped.add("缺少 source 字段: " + candidate.claim());
                    continue;
                }

                if (isDuplicate || isConflict) {
                    int idx = parseIndex(relation, isConflict ? "conflict_with:" : "duplicate_of:");
                    if (idx < 0 || idx >= baseEntries.size()) {
                        skipped.add("relation 索引越界: " + relation + "（claim: " + candidate.claim() + "）");
                        continue;
                    }
                    EntityPageEntryParser.Entry target = locate(
                        EntityPageEntryParser.parse(String.join("\n", lines)).entries(), baseEntries.get(idx));
                    if (target == null) {
                        skipped.add("relation 目标条目未找到: " + relation + "（claim: " + candidate.claim() + "）");
                        continue;
                    }
                    if (isConflict) {
                        lines.addAll(target.lineIndex() + 1, buildEntryLines(candidate));
                        conflicts.add(new ConflictEvent(target.lineIndex(), target.claim(),
                            candidate.claim(), candidate.source()));
                        added++;
                    } else {
                        if (target.sources().contains(candidate.source().trim())) {
                            skipped.add("来源标注已存在: " + candidate.claim());
                            continue;
                        }
                        List<String> newSources = new ArrayList<>(target.sources());
                        newSources.add(candidate.source().trim());
                        lines.set(target.lineIndex(), bulletPrefixOf(lines.get(target.lineIndex()))
                            + target.claim() + EntityPageEntryParser.formatSourceSuffix(newSources));
                        mergedSources++;
                    }
                    continue;
                }

                String claim = candidate.claim() == null ? "" : candidate.claim().trim();
                EntityPageEntryParser.Entry exact = findExactClaim(
                    EntityPageEntryParser.parse(String.join("\n", lines)).entries(), claim);
                if (exact != null) {
                    if (candidate.source() != null && !candidate.source().isBlank()
                        && !exact.sources().contains(candidate.source().trim())) {
                        List<String> newSources = new ArrayList<>(exact.sources());
                        newSources.add(candidate.source().trim());
                        lines.set(exact.lineIndex(), bulletPrefixOf(lines.get(exact.lineIndex()))
                            + exact.claim() + EntityPageEntryParser.formatSourceSuffix(newSources));
                        mergedSources++;
                    } else {
                        skipped.add("完全重复条目已存在: " + claim);
                    }
                    continue;
                }

                String section = candidate.section() == null || candidate.section().isBlank()
                    ? "补充信息" : candidate.section().trim();
                int sectionEnd = findSectionEnd(lines, section);
                if (sectionEnd < 0) {
                    if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                        lines.add("");
                    }
                    lines.add("## " + section);
                    lines.add("");
                    lines.addAll(buildEntryLines(candidate));
                } else {
                    lines.addAll(sectionEnd, buildEntryLines(candidate));
                }
                added++;
            }
        }
        return new ApplyResult(String.join("\n", lines), added, mergedSources, conflicts, skipped);
    }

    private static List<String> buildEntryLines(CandidateEntry candidate) {
        List<String> result = new ArrayList<>();
        String claim = candidate.claim() == null ? "" : candidate.claim().trim();
        String suffix = candidate.source() == null || candidate.source().isBlank()
            ? "" : EntityPageEntryParser.formatSourceSuffix(List.of(candidate.source().trim()));
        result.add("- " + claim + suffix);
        if (candidate.quote() != null && !candidate.quote().isBlank()) {
            for (String q : candidate.quote().trim().split("\n")) {
                if (!q.isBlank()) {
                    result.add("> " + q.trim());
                }
            }
        }
        return result;
    }

    private static EntityPageEntryParser.Entry locate(List<EntityPageEntryParser.Entry> current,
                                                      EntityPageEntryParser.Entry anchor) {
        for (EntityPageEntryParser.Entry e : current) {
            if (e.section().equals(anchor.section()) && e.claim().equals(anchor.claim())) {
                return e;
            }
        }
        return null;
    }

    private static EntityPageEntryParser.Entry findExactClaim(List<EntityPageEntryParser.Entry> entries, String claim) {
        if (claim.isEmpty()) {
            return null;
        }
        for (EntityPageEntryParser.Entry e : entries) {
            if (e.claim().equals(claim)) {
                return e;
            }
        }
        return null;
    }

    private static int findSectionEnd(List<String> lines, String section) {
        String target = "## " + section;
        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(target)) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            return -1;
        }
        int end = lines.size();
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("## ")) {
                end = i;
                break;
            }
        }
        int lastNonBlank = headerIndex;
        for (int i = headerIndex + 1; i < end; i++) {
            if (!lines.get(i).isBlank()) {
                lastNonBlank = i;
            }
        }
        return lastNonBlank + 1;
    }

    private static String bulletPrefixOf(String line) {
        Matcher m = BULLET_PREFIX.matcher(line);
        return m.find() ? m.group(1) : "- ";
    }

    private static int parseIndex(String relation, String prefix) {
        try {
            return Integer.parseInt(relation.substring(prefix.length()).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null) {
            return lines;
        }
        for (String line : content.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }
}
