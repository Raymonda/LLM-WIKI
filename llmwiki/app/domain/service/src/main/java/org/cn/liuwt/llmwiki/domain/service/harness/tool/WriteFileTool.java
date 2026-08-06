package org.cn.liuwt.llmwiki.domain.service.harness.tool;

import org.cn.liuwt.llmwiki.common.util.PathGuard;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class WriteFileTool {

    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);

    @Autowired
    private StorageProvider storageProvider;

    @Tool(description = "写入或更新 Wiki 页面文件。path 是相对于 wiki-data/{scopeId}/ 的路径，如 'wiki/pages/new-page.md'。严禁写入 raw/ 目录——raw/ 为原始来源不可变区。")
    public boolean writeFile(
        @ToolParam(description = "知识库范围 ID") String scopeId,
        @ToolParam(description = "文件路径，相对于 wiki-data/{scopeId}/，如 'wiki/pages/new-page.md'。禁止以 raw/ 开头") String path,
        @ToolParam(description = "文件内容（Markdown 格式）") String content
    ) {
        try {
            PathGuard.assertWritable(path);
            storageProvider.write(scopeId, PathGuard.normalize(path), content.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (BusinessException e) {
            log.warn("writeFile rejected by PathGuard: scopeId={}, path={}, reason={}", scopeId, path, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("writeFile failed: scopeId={}, path={}, error={}", scopeId, path, e.getMessage());
            return false;
        }
    }
}