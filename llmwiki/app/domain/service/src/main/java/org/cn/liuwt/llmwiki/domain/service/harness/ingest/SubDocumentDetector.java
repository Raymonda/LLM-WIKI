package org.cn.liuwt.llmwiki.domain.service.harness.ingest;

import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer;
import org.cn.liuwt.llmwiki.domain.service.harness.DocumentStructureAnalyzer.Chapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SubDocumentDetector {

    private static final Logger log = LoggerFactory.getLogger(SubDocumentDetector.class);

    private static final int MIN_SUB_DOCUMENTS = 3;
    private static final int MAX_SUB_DOCUMENTS = 15;
    private static final int MIN_SUB_DOC_CHARS = 3000;
    private static final double MIN_SUB_DOC_RATIO = 0.6;

    public static List<IngestContext.SubDocument> detect(List<Chapter> chapters, String sourceContent) {
        if (chapters == null || chapters.isEmpty() || sourceContent == null) {
            return null;
        }

        List<Chapter> candidates = selectSubDocumentCandidates(chapters);
        if (candidates.size() < MIN_SUB_DOCUMENTS) {
            return null;
        }

        List<Chapter> qualified = new ArrayList<>();
        for (Chapter ch : candidates) {
            if (ch.sourceContent() != null && ch.sourceContent().length() >= MIN_SUB_DOC_CHARS) {
                qualified.add(ch);
            }
        }

        if (qualified.size() < MIN_SUB_DOCUMENTS) {
            return null;
        }

        int totalQualifiedChars = qualified.stream()
            .mapToInt(ch -> ch.sourceContent() != null ? ch.sourceContent().length() : 0)
            .sum();
        double ratio = (double) totalQualifiedChars / sourceContent.length();
        if (ratio < MIN_SUB_DOC_RATIO) {
            return null;
        }

        if (qualified.size() > MAX_SUB_DOCUMENTS) {
            qualified = qualified.subList(0, MAX_SUB_DOCUMENTS);
        }

        List<IngestContext.SubDocument> result = new ArrayList<>();
        for (Chapter ch : qualified) {
            result.add(new IngestContext.SubDocument(
                ch.title(),
                ch.sourceContent(),
                ch.subChapters() != null ? ch.subChapters() : List.of()
            ));
        }

        log.info("SubDocumentDetector: detected {} sub-documents (total {} chars, ratio {}%)",
            result.size(), totalQualifiedChars, Math.round(ratio * 1000) / 10.0);
        return result;
    }

    private static List<Chapter> selectSubDocumentCandidates(List<Chapter> chapters) {
        boolean hasSubChapters = chapters.stream()
            .anyMatch(ch -> ch.subChapters() != null && !ch.subChapters().isEmpty());

        if (hasSubChapters) {
            List<Chapter> flattened = new ArrayList<>();
            for (Chapter parent : chapters) {
                if (parent.subChapters() != null && !parent.subChapters().isEmpty()) {
                    flattened.addAll(parent.subChapters());
                } else if (parent.sourceContent() != null && parent.sourceContent().length() >= MIN_SUB_DOC_CHARS) {
                    flattened.add(parent);
                }
            }
            return flattened;
        }

        return new ArrayList<>(chapters);
    }
}
