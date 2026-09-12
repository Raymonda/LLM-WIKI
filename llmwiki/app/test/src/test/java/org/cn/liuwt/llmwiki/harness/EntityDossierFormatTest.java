package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.ingest.EntityDossier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EntityDossierFormatTest {

    private static EntityDossier dossierWith(Map<String, String> hints) {
        return new EntityDossier(
            "华源证券产品", "文档", List.of(),
            null, List.of(), List.of(), Map.of(),
            List.of(), new LinkedHashSet<>(List.of("国泰海通")),
            hints, 0, 0);
    }

    @Test
    void shouldUseColonHintFormatWhenFormattingRelatedEntities() {
        EntityDossier dossier = dossierWith(Map.of("国泰海通", "担任该产品的托管人"));
        String out = dossier.formatForPrompt(10000);
        assertTrue(out.contains("- [[ 国泰海通 ]]：担任该产品的托管人"),
            "hint must be separated by colon, actual: " + out);
        assertFalse(out.contains("]]（"), "must not reproduce the bracketed hint pattern: " + out);
    }

    @Test
    void shouldEmitPlainLinkWhenHintMissingOrBlank() {
        EntityDossier missing = dossierWith(Map.of());
        assertTrue(missing.formatForPrompt(10000).contains("- [[ 国泰海通 ]]\n"));

        EntityDossier blank = dossierWith(Map.of("国泰海通", "  "));
        String out = blank.formatForPrompt(10000);
        assertTrue(out.contains("- [[ 国泰海通 ]]\n"), "blank hint must degrade to plain link: " + out);
        assertFalse(out.contains("："), "blank hint must not leave a dangling colon: " + out);
    }
}
