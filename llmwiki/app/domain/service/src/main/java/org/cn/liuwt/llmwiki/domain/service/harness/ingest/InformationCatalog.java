package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record InformationCatalog(
    List<SectionNode> sections,
    Map<String, EntityRecord> entityIndex,
    Map<String, Set<String>> coOccurrenceGraph,
    List<CrossChapterRelation> crossChapterRelations
) {

    public InformationCatalog(List<SectionNode> sections, Map<String, EntityRecord> entityIndex,
                               Map<String, Set<String>> coOccurrenceGraph) {
        this(sections, entityIndex, coOccurrenceGraph, List.of());
    }

    public EntityRecord getEntity(String entityName) {
        return entityIndex != null ? entityIndex.get(entityName) : null;
    }

    public Set<String> getCoOccurringEntities(String entityName) {
        return coOccurrenceGraph != null ? coOccurrenceGraph.getOrDefault(entityName, Set.of()) : Set.of();
    }

    public List<CrossChapterRelation> getRelationsForEntity(String entityName) {
        if (crossChapterRelations == null) return List.of();
        return crossChapterRelations.stream()
            .filter(r -> r.entityName().equals(entityName))
            .toList();
    }

    public record SectionNode(
        String id,
        String title,
        int level,
        int charStart,
        int charEnd,
        String parentSectionId
    ) {}

    public record EntityRecord(
        String name,
        String type,
        List<String> aliases,
        List<Occurrence> occurrences,
        Occurrence definitionLocation,
        int totalMentions,
        Set<String> coOccurringEntities
    ) {
        public boolean hasOccurrences() {
            return occurrences != null && !occurrences.isEmpty();
        }

        public List<Occurrence> getByType(MentionType type) {
            if (occurrences == null) return List.of();
            return occurrences.stream().filter(o -> o.mentionType() == type).toList();
        }

        public List<String> getContainingSectionTitles() {
            if (occurrences == null) return List.of();
            return occurrences.stream()
                .map(Occurrence::sectionTitle)
                .distinct()
                .toList();
        }

        public boolean spansMultipleSections() {
            if (occurrences == null || occurrences.size() < 2) return false;
            String firstSection = occurrences.get(0).sectionId();
            return occurrences.stream().anyMatch(o -> !o.sectionId().equals(firstSection));
        }
    }

    public record Occurrence(
        String sectionId,
        String sectionTitle,
        int paragraphIdx,
        int charStart,
        int charEnd,
        String contextBefore,
        String exactText,
        String contextAfter,
        MentionType mentionType,
        List<String> surroundingEntities
    ) {}

    public record CrossChapterRelation(
        String entityName,
        String definedInSection,
        String referencedInSection,
        RelationType relationType,
        String contextSnippet
    ) {}

    public enum MentionType {
        DEFINITION,
        RULE,
        DATA,
        ELABORATION
    }

    public enum RelationType {
        DEFINED_IN,
        REFERENCED_BY,
        DEPENDS_ON,
        SUPERSEDES
    }
}
