package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.domain.service.harness.prompt.PromptRegistry;
import org.cn.liuwt.llmwiki.integration.ai.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class ChunkMergeCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChunkMergeCoordinator.class);

    private final LlmClient chatClient;
    private final String schemaHeader;

    public ChunkMergeCoordinator(LlmClient chatClient) {
        this(chatClient, "");
    }

    public ChunkMergeCoordinator(LlmClient chatClient, String schemaHeader) {
        this.chatClient = chatClient;
        this.schemaHeader = schemaHeader == null ? "" : schemaHeader;
    }

    public String merge(List<ParallelAnalysisExecutor.ChunkAnalysisResult> results) {
        if (results.isEmpty()) {
            return "无分析结果";
        }

        if (results.size() == 1) {
            return results.get(0).content();
        }

        long errorCount = results.stream().filter(ParallelAnalysisExecutor.ChunkAnalysisResult::hasError).count();
        if (errorCount > 0) {
            log.warn("{} of {} chunk analyses had errors", errorCount, results.size());
        }

        List<String> validResults = results.stream()
            .filter(r -> !r.hasError())
            .map(r -> String.format("## 片段 %d 分析\n%s", r.chunkIndex() + 1, r.content()))
            .collect(Collectors.toList());

        if (validResults.isEmpty()) {
            return "所有片段分析均失败，无法生成合并结果";
        }

        String combined = String.join("\n\n---\n\n", validResults);

        if (chatClient.isAvailable()) {
            String prompt = schemaHeader + PromptRegistry.forIngest().mergeAnalyses();
            return chatClient.chat(prompt, combined);
        }

        return combined;
    }
}
