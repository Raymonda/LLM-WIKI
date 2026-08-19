package org.cn.liuwt.llmwiki.domain.model.harness;

import java.util.List;

public record FactBlock(
    String id,
    String conclusion,
    String evidence,
    List<FactRef> refs,
    String confidence,
    String kind
) {
    public record FactRef(String path, String title) {}
}
