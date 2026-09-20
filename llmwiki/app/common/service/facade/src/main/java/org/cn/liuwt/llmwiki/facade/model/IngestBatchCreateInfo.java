package org.cn.liuwt.llmwiki.facade.model;

import java.util.List;

public record IngestBatchCreateInfo(Long batchId, int acceptedCount, int skippedDuplicateCount,
                                    List<SkippedItem> skipped, List<String> warnings) {

    public record SkippedItem(Long sourceId, String sourceName, Long duplicateOfSourceId, String reason) {}
}
