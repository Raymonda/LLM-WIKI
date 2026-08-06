package org.cn.liuwt.llmwiki.domain.service.harness;

import org.springframework.stereotype.Component;

@Component
public class LanguageDirective {

    private static final String EN_DIRECTIVE =
        "[LANGUAGE DIRECTIVE] All generated content, titles, summaries, "
        + "and analysis MUST be written in English. Proper nouns, technical "
        + "terms, and entity names should retain their original language form.\n\n";

    public String resolve(String scopeLanguage) {
        if ("en".equals(scopeLanguage)) {
            return EN_DIRECTIVE;
        }
        return "";
    }
}
