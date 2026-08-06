package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InformationCatalogBuilder {

    private static final Logger log = LoggerFactory.getLogger(InformationCatalogBuilder.class);

    private static final int CONTEXT_CHARS = 200;

    private static final Pattern DEFINITION_PATTERN = Pattern.compile(
        "是指|定义为|是指|即指|所谓|系指|指的是|负责|是.*的(部门|机构|组织|单位|岗位|角色|职能)"
    );
    private static final Pattern RULE_PATTERN = Pattern.compile(
        "应当|必须|不得|禁止|严禁|应当|要求|规定|须|应|不得|不允许|有权|无权|"
        + "可以|负责|审批|审核|批准|备案|执行|实施|监督|检查|处罚|"
        + "在.*之前|在.*之后|不超过|不少于|至少|至多|不得超过"
    );
    private static final Pattern DATA_PATTERN = Pattern.compile(
        "\\d+%|百分之|‰|万元|亿元|元\\/|人\\/|次\\/|天|工作日|%"
        + "|编制.*\\d|现有.*\\d|共计.*\\d|合计.*\\d|总计.*\\d"
    );
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    public InformationCatalog build(
        String sourceContent,
        List<DocumentStructureAnalyzer.Chapter> chapters,
        List<Map<String, String>> entities
    ) {
        if (sourceContent == null || sourceContent.isBlank()) {
            return new InformationCatalog(List.of(), Map.of(), Map.of(), List.of());
        }

        List<InformationCatalog.SectionNode> sections = buildSectionTree(sourceContent, chapters);
        List<IndexedParagraph> paragraphs = buildParagraphIndex(sourceContent, sections);
        Map<String, EntitySearchProfile> searchProfiles = buildSearchProfiles(entities);

        Map<Integer, Set<String>> paragraphEntities = buildParagraphEntityMap(paragraphs, searchProfiles);

        Map<String, List<InformationCatalog.Occurrence>> rawOccurrences = new LinkedHashMap<>();
        for (Map.Entry<String, EntitySearchProfile> entry : searchProfiles.entrySet()) {
            String entityName = entry.getKey();
            EntitySearchProfile profile = entry.getValue();
            List<InformationCatalog.Occurrence> occurrences = findOccurrences(
                entityName, profile, paragraphs, sourceContent, paragraphEntities
            );
            if (!occurrences.isEmpty()) {
                rawOccurrences.put(entityName, occurrences);
            }
        }

        Map<String, Set<String>> coOccurrenceGraph = buildCoOccurrenceGraph(rawOccurrences, paragraphs);

        Map<String, InformationCatalog.EntityRecord> entityIndex = new LinkedHashMap<>();
        for (Map.Entry<String, EntitySearchProfile> entry : searchProfiles.entrySet()) {
            String name = entry.getKey();
            EntitySearchProfile profile = entry.getValue();
            List<InformationCatalog.Occurrence> occurrences = rawOccurrences.getOrDefault(name, List.of());

            InformationCatalog.Occurrence definitionLoc = findDefinitionLocation(occurrences);

            entityIndex.put(name, new InformationCatalog.EntityRecord(
                name,
                profile.type(),
                profile.aliases(),
                occurrences,
                definitionLoc,
                occurrences.size(),
                coOccurrenceGraph.getOrDefault(name, Set.of())
            ));
        }

        List<InformationCatalog.CrossChapterRelation> crossChapterRelations =
            detectCrossChapterRelations(rawOccurrences, entityIndex, sourceContent);

        log.info("InformationCatalog built: {} sections, {} paragraphs, {} entities indexed, {} co-occurrence edges, {} cross-chapter relations",
            sections.size(), paragraphs.size(), entityIndex.size(),
            coOccurrenceGraph.values().stream().mapToInt(Set::size).sum() / 2,
            crossChapterRelations.size());

        return new InformationCatalog(sections, entityIndex, coOccurrenceGraph, crossChapterRelations);
    }

    private List<InformationCatalog.SectionNode> buildSectionTree(String sourceContent, List<DocumentStructureAnalyzer.Chapter> chapters) {
        List<InformationCatalog.SectionNode> sections = new ArrayList<>();

        if (chapters != null && !chapters.isEmpty()) {
            int charOffset = 0;
            for (int i = 0; i < chapters.size(); i++) {
                DocumentStructureAnalyzer.Chapter ch = chapters.get(i);
                String sectionId = "ch-" + (i + 1);
                int charEnd = charOffset + (ch.sourceContent() != null ? ch.sourceContent().length() : 0);
                sections.add(new InformationCatalog.SectionNode(sectionId, ch.title(), ch.headingLevel(), charOffset, charEnd, null));

                if (ch.subChapters() != null) {
                    int subOffset = charOffset;
                    for (int j = 0; j < ch.subChapters().size(); j++) {
                        DocumentStructureAnalyzer.Chapter sub = ch.subChapters().get(j);
                        String subId = sectionId + "." + (j + 1);
                        int subEnd = subOffset + (sub.sourceContent() != null ? sub.sourceContent().length() : 0);
                        sections.add(new InformationCatalog.SectionNode(subId, sub.title(), sub.headingLevel(), subOffset, subEnd, sectionId));
                        subOffset = subEnd;
                    }
                }

                charOffset = charEnd;
            }
            return sections;
        }

        String[] lines = sourceContent.split("\n");
        int charPos = 0;
        int sectionIdx = 0;
        for (String line : lines) {
            Matcher m = HEADING_PATTERN.matcher(line);
            if (m.matches()) {
                int level = m.group(1).length();
                String title = m.group(2).trim();
                sections.add(new InformationCatalog.SectionNode("s-" + sectionIdx, title, level, charPos, charPos + line.length(), null));
                sectionIdx++;
            }
            charPos += line.length() + 1;
        }
        return sections;
    }

    private List<IndexedParagraph> buildParagraphIndex(String sourceContent, List<InformationCatalog.SectionNode> sections) {
        List<IndexedParagraph> paragraphs = new ArrayList<>();
        String[] parts = sourceContent.split("\n\n");
        int charOffset = 0;

        for (int i = 0; i < parts.length; i++) {
            String text = parts[i].trim();
            int charEnd = charOffset + parts[i].length() + 2;

            if (!text.isBlank() && text.length() > 5) {
                String sectionId = findContainingSection(charOffset, sections);
                String sectionTitle = findSectionTitle(sectionId, sections);
                paragraphs.add(new IndexedParagraph(i, sectionId, sectionTitle, charOffset, charEnd, text));
            }

            charOffset = charEnd;
        }
        return paragraphs;
    }

    private String findContainingSection(int charPos, List<InformationCatalog.SectionNode> sections) {
        String bestId = null;
        int bestStart = -1;
        for (InformationCatalog.SectionNode section : sections) {
            if (section.charStart() <= charPos && (bestId == null || section.charStart() > bestStart)) {
                bestId = section.id();
                bestStart = section.charStart();
            }
        }
        return bestId;
    }

    private String findSectionTitle(String sectionId, List<InformationCatalog.SectionNode> sections) {
        if (sectionId == null) return "未分类";
        for (InformationCatalog.SectionNode section : sections) {
            if (section.id().equals(sectionId)) {
                return section.title();
            }
        }
        return "未分类";
    }

    private Map<String, EntitySearchProfile> buildSearchProfiles(List<Map<String, String>> entities) {
        Map<String, EntitySearchProfile> profiles = new LinkedHashMap<>();
        if (entities == null) return profiles;

        for (Map<String, String> entity : entities) {
            String name = entity.get("name");
            if (name == null || name.isBlank()) continue;

            String type = entity.getOrDefault("type", "concept");
            List<String> aliases = new ArrayList<>();
            String aliasesStr = entity.get("aliases");
            if (aliasesStr != null && !aliasesStr.isEmpty()) {
                for (String alias : aliasesStr.split(",")) {
                    String trimmed = alias.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                        aliases.add(trimmed);
                    }
                }
            }

            profiles.put(name, new EntitySearchProfile(type, aliases));
        }
        return profiles;
    }

    private Map<Integer, Set<String>> buildParagraphEntityMap(
        List<IndexedParagraph> paragraphs,
        Map<String, EntitySearchProfile> searchProfiles
    ) {
        Map<Integer, Set<String>> map = new HashMap<>();
        for (Map.Entry<String, EntitySearchProfile> entry : searchProfiles.entrySet()) {
            String entityName = entry.getKey();
            EntitySearchProfile profile = entry.getValue();
            List<String> searchTerms = new ArrayList<>();
            searchTerms.add(entityName);
            searchTerms.addAll(profile.aliases());
            for (IndexedParagraph para : paragraphs) {
                for (String term : searchTerms) {
                    if (term.length() < 2) continue;
                    if (para.text().contains(term)) {
                        map.computeIfAbsent(para.index(), k -> new HashSet<>()).add(entityName);
                        break;
                    }
                }
            }
        }
        return map;
    }

    private List<InformationCatalog.Occurrence> findOccurrences(
        String entityName,
        EntitySearchProfile profile,
        List<IndexedParagraph> paragraphs,
        String sourceContent,
        Map<Integer, Set<String>> paragraphEntities
    ) {
        List<InformationCatalog.Occurrence> occurrences = new ArrayList<>();

        List<String> searchTerms = new ArrayList<>();
        searchTerms.add(entityName);
        searchTerms.addAll(profile.aliases());

        for (IndexedParagraph para : paragraphs) {
            for (String term : searchTerms) {
                if (term.length() < 2) continue;
                if (!para.text().contains(term)) continue;

                int matchStart = para.text().indexOf(term);
                int contextBeforeStart = Math.max(0, matchStart - CONTEXT_CHARS);
                String contextBefore = para.text().substring(contextBeforeStart, matchStart);

                int matchEnd = Math.min(matchStart + term.length(), para.text().length());
                int contextAfterEnd = Math.min(para.text().length(), matchEnd + CONTEXT_CHARS);
                String contextAfter = para.text().substring(matchEnd, contextAfterEnd);

                InformationCatalog.MentionType mentionType = classifyMentionType(para.text(), term);

                Set<String> allInPara = paragraphEntities.getOrDefault(para.index(), Set.of());
                List<String> surroundingEntities = new ArrayList<>();
                for (String other : allInPara) {
                    if (!other.equals(entityName)) {
                        surroundingEntities.add(other);
                    }
                }

                occurrences.add(new InformationCatalog.Occurrence(
                    para.sectionId(),
                    para.sectionTitle(),
                    para.index(),
                    para.charStart(),
                    para.charEnd(),
                    contextBefore,
                    term,
                    contextAfter,
                    mentionType,
                    surroundingEntities
                ));
                break;
            }
        }

        return occurrences;
    }

    private InformationCatalog.MentionType classifyMentionType(String paragraphText, String entityName) {
        if (DEFINITION_PATTERN.matcher(paragraphText).find()) {
            return InformationCatalog.MentionType.DEFINITION;
        }
        if (RULE_PATTERN.matcher(paragraphText).find()) {
            return InformationCatalog.MentionType.RULE;
        }
        if (DATA_PATTERN.matcher(paragraphText).find()) {
            return InformationCatalog.MentionType.DATA;
        }
        return InformationCatalog.MentionType.ELABORATION;
    }

    private InformationCatalog.Occurrence findDefinitionLocation(List<InformationCatalog.Occurrence> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) return null;

        for (InformationCatalog.Occurrence occ : occurrences) {
            if (occ.mentionType() == InformationCatalog.MentionType.DEFINITION) {
                return occ;
            }
        }

        return occurrences.get(0);
    }

    private Map<String, Set<String>> buildCoOccurrenceGraph(
        Map<String, List<InformationCatalog.Occurrence>> rawOccurrences,
        List<IndexedParagraph> paragraphs
    ) {
        Map<String, Set<String>> graph = new HashMap<>();

        Map<Integer, Set<String>> paragraphEntityMap = new HashMap<>();
        for (Map.Entry<String, List<InformationCatalog.Occurrence>> entry : rawOccurrences.entrySet()) {
            for (InformationCatalog.Occurrence occ : entry.getValue()) {
                paragraphEntityMap.computeIfAbsent(occ.paragraphIdx(), k -> new HashSet<>()).add(entry.getKey());
            }
        }

        for (Set<String> coEntities : paragraphEntityMap.values()) {
            if (coEntities.size() < 2) continue;
            List<String> entityList = new ArrayList<>(coEntities);
            for (int i = 0; i < entityList.size(); i++) {
                for (int j = i + 1; j < entityList.size(); j++) {
                    String a = entityList.get(i);
                    String b = entityList.get(j);
                    graph.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    graph.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                }
            }
        }

        return graph;
    }

    private List<InformationCatalog.CrossChapterRelation> detectCrossChapterRelations(
        Map<String, List<InformationCatalog.Occurrence>> rawOccurrences,
        Map<String, InformationCatalog.EntityRecord> entityIndex,
        String sourceContent
    ) {
        List<InformationCatalog.CrossChapterRelation> relations = new ArrayList<>();

        for (Map.Entry<String, List<InformationCatalog.Occurrence>> entry : rawOccurrences.entrySet()) {
            String entityName = entry.getKey();
            List<InformationCatalog.Occurrence> occurrences = entry.getValue();

            if (occurrences.size() < 2) continue;

            InformationCatalog.EntityRecord record = entityIndex.get(entityName);
            InformationCatalog.Occurrence defLoc = record != null ? record.definitionLocation() : null;
            String defSectionId = defLoc != null ? defLoc.sectionId() : null;

            Set<String> seenSections = new HashSet<>();
            for (InformationCatalog.Occurrence occ : occurrences) {
                if (!seenSections.add(occ.sectionId())) continue;
                if (defSectionId != null && occ.sectionId().equals(defSectionId)) continue;

                InformationCatalog.RelationType relType = inferRelationType(occ, defLoc);
                String snippet = extractContextSnippet(sourceContent, occ);

                relations.add(new InformationCatalog.CrossChapterRelation(
                    entityName,
                    defSectionId != null ? defSectionId : "unknown",
                    occ.sectionId(),
                    relType,
                    snippet
                ));
            }

            if (defLoc != null) {
                for (String coEntity : record.coOccurringEntities()) {
                    InformationCatalog.EntityRecord coRecord = entityIndex.get(coEntity);
                    if (coRecord == null) continue;
                    InformationCatalog.Occurrence coDefLoc = coRecord.definitionLocation();
                    if (coDefLoc == null) continue;
                    if (coDefLoc.sectionId().equals(defSectionId)) continue;

                    boolean alreadyRecorded = relations.stream().anyMatch(r ->
                        r.entityName().equals(entityName) &&
                        r.referencedInSection().equals(coDefLoc.sectionId()) &&
                        r.relationType() == InformationCatalog.RelationType.DEPENDS_ON
                    );
                    if (!alreadyRecorded) {
                        relations.add(new InformationCatalog.CrossChapterRelation(
                            entityName,
                            defSectionId,
                            coDefLoc.sectionId(),
                            InformationCatalog.RelationType.DEPENDS_ON,
                            entityName + " 与 " + coEntity + " 跨章节关联"
                        ));
                    }
                }
            }
        }

        return relations;
    }

    private InformationCatalog.RelationType inferRelationType(
        InformationCatalog.Occurrence referenceOcc,
        InformationCatalog.Occurrence definitionOcc
    ) {
        if (referenceOcc.mentionType() == InformationCatalog.MentionType.RULE) {
            return InformationCatalog.RelationType.REFERENCED_BY;
        }
        if (referenceOcc.mentionType() == InformationCatalog.MentionType.DATA) {
            return InformationCatalog.RelationType.REFERENCED_BY;
        }
        return InformationCatalog.RelationType.REFERENCED_BY;
    }

    private String extractContextSnippet(String sourceContent, InformationCatalog.Occurrence occ) {
        int start = Math.max(0, occ.charStart());
        int end = Math.min(sourceContent.length(), start + 100);
        return sourceContent.substring(start, end).replace("\n", " ").trim();
    }

    private record EntitySearchProfile(String type, List<String> aliases) {}

    private record IndexedParagraph(
        int index,
        String sectionId,
        String sectionTitle,
        int charStart,
        int charEnd,
        String text
    ) {}
}
