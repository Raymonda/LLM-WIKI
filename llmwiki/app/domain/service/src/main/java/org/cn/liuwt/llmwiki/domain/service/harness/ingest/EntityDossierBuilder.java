package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.ExecutionStrategy;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class EntityDossierBuilder {

    private static final Logger log = LoggerFactory.getLogger(EntityDossierBuilder.class);

    private static final int DEFAULT_MAX_DOSSIER_CHARS = 50000;

    public Map<String, EntityDossier> buildAll(
        InformationCatalog catalog,
        String sourceContent,
        List<Map<String, String>> entities,
        ExecutionStrategy strategy
    ) {
        Map<String, EntityDossier> dossiers = new LinkedHashMap<>();
        if (catalog == null || entities == null || entities.isEmpty()) {
            return dossiers;
        }

        int maxDossierChars = computeMaxDossierChars(strategy);

        for (Map<String, String> entity : entities) {
            String name = entity.get("name");
            if (name == null || name.isBlank()) continue;

            InformationCatalog.EntityRecord record = catalog.getEntity(name);
            if (record == null || !record.hasOccurrences()) {
                EntityDossier fallback = buildFallbackDossier(name, entity, sourceContent, maxDossierChars);
                if (fallback != null) {
                    dossiers.put(name, fallback);
                }
                continue;
            }

            EntityDossier dossier = buildDossier(name, entity, record, catalog, sourceContent, maxDossierChars);
            dossiers.put(name, dossier);
        }

        log.info("EntityDossierBuilder: built {} dossiers (maxChars={})", dossiers.size(), maxDossierChars);
        return dossiers;
    }

    private int computeMaxDossierChars(ExecutionStrategy strategy) {
        if (strategy == null) return DEFAULT_MAX_DOSSIER_CHARS;

        if (strategy.getQualityTier() == ExecutionStrategy.QualityTier.FULL_CONTEXT) {
            return Integer.MAX_VALUE;
        }

        int entityChars = strategy.getMaxSourceCharsEntity();
        return Math.max(DEFAULT_MAX_DOSSIER_CHARS, entityChars);
    }

    private EntityDossier buildDossier(
        String entityName,
        Map<String, String> entityMeta,
        InformationCatalog.EntityRecord record,
        InformationCatalog catalog,
        String sourceContent,
        int maxDossierChars
    ) {
        String entityType = entityMeta.getOrDefault("type", record.type());
        List<String> aliases = record.aliases();

        String definitionText = extractDefinitionText(record, sourceContent);

        List<String> ruleTexts = extractRuleTexts(record, sourceContent);

        List<String> dataPoints = extractDataPoints(record, sourceContent);

        Map<String, List<String>> sectionElaborations = extractSectionElaborations(record, sourceContent);

        List<String> containingSections = record.getContainingSectionTitles();

        Set<String> relatedEntities = catalog.getCoOccurringEntities(entityName);

        Map<String, String> relationshipHints = buildRelationshipHints(entityName, record, catalog);

        int totalSourceChars = computeTotalSourceChars(record, sourceContent);

        EntityDossier dossier = new EntityDossier(
            entityName, entityType, aliases,
            definitionText, ruleTexts, dataPoints, sectionElaborations,
            containingSections, relatedEntities, relationshipHints,
            record.totalMentions(), totalSourceChars
        );

        if (dossier.estimatePromptChars() > maxDossierChars) {
            dossier = trimDossier(dossier, maxDossierChars);
        }

        return dossier;
    }

    private String extractDefinitionText(InformationCatalog.EntityRecord record, String sourceContent) {
        InformationCatalog.Occurrence defOcc = record.definitionLocation();
        if (defOcc == null) return null;

        return extractFullParagraph(sourceContent, defOcc);
    }

    private List<String> extractRuleTexts(InformationCatalog.EntityRecord record, String sourceContent) {
        List<InformationCatalog.Occurrence> rules = record.getByType(InformationCatalog.MentionType.RULE);
        if (rules.isEmpty()) return List.of();

        List<String> texts = new ArrayList<>();
        for (InformationCatalog.Occurrence occ : rules) {
            String text = extractFullParagraph(sourceContent, occ);
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private List<String> extractDataPoints(InformationCatalog.EntityRecord record, String sourceContent) {
        List<InformationCatalog.Occurrence> dataOccs = record.getByType(InformationCatalog.MentionType.DATA);
        if (dataOccs.isEmpty()) return List.of();

        List<String> texts = new ArrayList<>();
        for (InformationCatalog.Occurrence occ : dataOccs) {
            String text = extractFullParagraph(sourceContent, occ);
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private Map<String, List<String>> extractSectionElaborations(
        InformationCatalog.EntityRecord record,
        String sourceContent
    ) {
        List<InformationCatalog.Occurrence> elaborations = record.getByType(InformationCatalog.MentionType.ELABORATION);
        if (elaborations.isEmpty()) return Map.of();

        Map<String, List<String>> bySection = new LinkedHashMap<>();
        for (InformationCatalog.Occurrence occ : elaborations) {
            String text = extractExtendedParagraph(sourceContent, occ);
            if (text != null && !text.isBlank()) {
                bySection.computeIfAbsent(occ.sectionTitle(), k -> new ArrayList<>()).add(text);
            }
        }
        return bySection;
    }

    private String extractFullParagraph(String sourceContent, InformationCatalog.Occurrence occ) {
        int start = Math.max(0, occ.charStart() - 200);
        int end = Math.min(sourceContent.length(), occ.charEnd() + 200);

        int paraStart = sourceContent.lastIndexOf("\n\n", start);
        if (paraStart < 0) paraStart = 0;
        else paraStart += 2;

        int paraEnd = sourceContent.indexOf("\n\n", end);
        if (paraEnd < 0) paraEnd = Math.min(sourceContent.length(), end + 500);

        return sourceContent.substring(paraStart, paraEnd).trim();
    }

    private String extractExtendedParagraph(String sourceContent, InformationCatalog.Occurrence occ) {
        int start = Math.max(0, occ.charStart() - 200);
        int end = Math.min(sourceContent.length(), occ.charEnd() + 200);

        int paraStart = sourceContent.lastIndexOf("\n\n", start);
        if (paraStart < 0) paraStart = 0;
        else paraStart += 2;

        int paraEnd = sourceContent.indexOf("\n\n", end);
        if (paraEnd < 0) paraEnd = Math.min(sourceContent.length(), end + 500);

        int prevParaStart = sourceContent.lastIndexOf("\n\n", paraStart - 1);
        if (prevParaStart >= 0) {
            prevParaStart += 2;
        } else {
            prevParaStart = 0;
        }

        int nextParaEnd = sourceContent.indexOf("\n\n", paraEnd + 2);
        if (nextParaEnd < 0) {
            nextParaEnd = Math.min(sourceContent.length(), paraEnd + 500);
        }

        return sourceContent.substring(prevParaStart, nextParaEnd).trim();
    }

    private Map<String, String> buildRelationshipHints(
        String entityName,
        InformationCatalog.EntityRecord record,
        InformationCatalog catalog
    ) {
        Map<String, String> hints = new LinkedHashMap<>();
        Set<String> coEntities = catalog.getCoOccurringEntities(entityName);
        if (coEntities == null || coEntities.isEmpty()) return hints;

        for (String coEntity : coEntities) {
            String hint = inferRelationshipHint(entityName, coEntity, record, catalog);
            hints.put(coEntity, hint);
        }
        return hints;
    }

    private String inferRelationshipHint(
        String entityName,
        String coEntity,
        InformationCatalog.EntityRecord selfRecord,
        InformationCatalog catalog
    ) {
        InformationCatalog.EntityRecord coRecord = catalog.getEntity(coEntity);
        if (coRecord == null) return "相关";

        int sharedParagraphs = 0;
        Set<Integer> selfParas = new HashSet<>();
        for (InformationCatalog.Occurrence occ : selfRecord.occurrences()) {
            selfParas.add(occ.paragraphIdx());
        }
        for (InformationCatalog.Occurrence occ : coRecord.occurrences()) {
            if (selfParas.contains(occ.paragraphIdx())) {
                sharedParagraphs++;
            }
        }

        if (sharedParagraphs >= 3) return "紧密协作";
        if (sharedParagraphs >= 2) return "业务关联";
        return "相关";
    }

    private int computeTotalSourceChars(InformationCatalog.EntityRecord record, String sourceContent) {
        int total = 0;
        Set<Integer> seenParagraphs = new HashSet<>();
        for (InformationCatalog.Occurrence occ : record.occurrences()) {
            if (seenParagraphs.add(occ.paragraphIdx())) {
                total += (occ.charEnd() - occ.charStart());
            }
        }
        return total;
    }

    private EntityDossier buildFallbackDossier(
        String entityName,
        Map<String, String> entityMeta,
        String sourceContent,
        int maxDossierChars
    ) {
        if (sourceContent == null || sourceContent.isEmpty()) return null;

        List<String> matchingLines = new ArrayList<>();
        String[] lines = sourceContent.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(entityName)) {
                int start = Math.max(0, i - 2);
                int end = Math.min(lines.length - 1, i + 2);
                StringBuilder sb = new StringBuilder();
                for (int j = start; j <= end; j++) {
                    sb.append(lines[j]).append("\n");
                }
                matchingLines.add(sb.toString().trim());
            }
        }

        if (matchingLines.isEmpty()) return null;

        String entityType = entityMeta.getOrDefault("type", "concept");
        Map<String, List<String>> elaborations = new LinkedHashMap<>();
        elaborations.put("全文检索结果", matchingLines.subList(0, Math.min(matchingLines.size(), 10)));

        return new EntityDossier(
            entityName, entityType, List.of(),
            null, List.of(), List.of(), elaborations,
            List.of(), Set.of(), Map.of(),
            matchingLines.size(),
            matchingLines.stream().mapToInt(String::length).sum()
        );
    }

    private EntityDossier trimDossier(EntityDossier dossier, int maxChars) {
        List<String> ruleTexts = dossier.ruleTexts();
        Map<String, List<String>> sectionElaborations = dossier.sectionElaborations();

        if (ruleTexts != null && ruleTexts.size() > 20) {
            ruleTexts = ruleTexts.subList(0, 20);
        }

        if (sectionElaborations != null) {
            Map<String, List<String>> trimmed = new LinkedHashMap<>();
            int totalChars = 0;
            int perSectionBudget = maxChars / Math.max(1, sectionElaborations.size());

            for (Map.Entry<String, List<String>> entry : sectionElaborations.entrySet()) {
                List<String> texts = new ArrayList<>();
                int sectionChars = 0;
                for (String text : entry.getValue()) {
                    if (sectionChars + text.length() > perSectionBudget) break;
                    texts.add(text);
                    sectionChars += text.length();
                }
                if (!texts.isEmpty()) {
                    trimmed.put(entry.getKey(), texts);
                    totalChars += sectionChars;
                }
            }
            sectionElaborations = trimmed;
        }

        return new EntityDossier(
            dossier.entityName(), dossier.entityType(), dossier.aliases(),
            dossier.definitionText(), ruleTexts, dossier.dataPoints(), sectionElaborations,
            dossier.containingSections(), dossier.relatedEntities(), dossier.relationshipHints(),
            dossier.totalMentions(), dossier.totalSourceChars()
        );
    }
}
