package org.cn.liuwt.llmwiki.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplateFidelityTest {

    @Test
    void shouldProtectRoleNounsWhenBuildingNormalizationPrinciple() {
        String prompt = PromptTemplate.knowledgeNormalizationPrinciple();
        assertTrue(prompt.contains("业务角色名词"), "role nouns must be named explicitly");
        assertTrue(prompt.contains("严禁替换为具体实体名称"), "role noun replacement must be forbidden");
        assertFalse(prompt.contains("必须使用具体名称"), "old blanket name-substitution rule must be gone");
    }

    @Test
    void shouldStillResolvePronounsAndKeepNonExhaustiveListRule() {
        String prompt = PromptTemplate.knowledgeNormalizationPrinciple();
        assertTrue(prompt.contains("指代"), "pronoun resolution must stay");
        assertTrue(prompt.contains("列举未尽"), "non-exhaustive list annotation must stay");
    }
}
