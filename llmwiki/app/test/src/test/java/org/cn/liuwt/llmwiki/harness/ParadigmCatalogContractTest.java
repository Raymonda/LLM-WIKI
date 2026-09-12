package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.governance.bootstrap.ParadigmCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParadigmCatalogContractTest {

    @Test
    void shouldInjectFaithfulContractIntoAllParadigmTemplates() {
        ParadigmCatalog catalog = new ParadigmCatalog();
        assertFalse(catalog.listAll().isEmpty(), "catalog must register paradigms");
        for (ParadigmCatalog.Paradigm p : catalog.listAll()) {
            String narrative = p.skeleton.getTemplates().getNarrative();
            assertNotNull(narrative, "paradigm '" + p.id + "' must carry template narrative");
            assertTrue(narrative.contains("来源编译页"), "paradigm '" + p.id + "' must define source-compilation pages");
            assertTrue(narrative.contains("（来源："), "paradigm '" + p.id + "' must require source annotation");
            assertTrue(narrative.contains("并列"), "paradigm '" + p.id + "' must require side-by-side conflicts");
            assertTrue(narrative.contains("不做裁决"), "paradigm '" + p.id + "' must forbid adjudication");
        }
    }
}
