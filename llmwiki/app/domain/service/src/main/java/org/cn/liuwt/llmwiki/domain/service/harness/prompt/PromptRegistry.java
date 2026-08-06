package org.cn.liuwt.llmwiki.domain.service.harness.prompt;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.QueryPrompts;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.IngestPrompts;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.LintPrompts;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.SchemaPatchPrompts;
import org.cn.liuwt.llmwiki.domain.service.harness.prompt.config.PageModifyPrompts;

public class PromptRegistry {

    private PromptRegistry() {}

    public static QueryPrompts forQuery() {
        return QueryPrompts.get();
    }

    public static IngestPrompts forIngest() {
        return IngestPrompts.get();
    }

    public static LintPrompts forLint() {
        return LintPrompts.get();
    }

    public static SchemaPatchPrompts forSchemaPatch() {
        return SchemaPatchPrompts.get();
    }

    public static PageModifyPrompts forPageModify() {
        return PageModifyPrompts.get();
    }
}
