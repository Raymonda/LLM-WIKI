package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class SpillService {

    private static final Logger log = LoggerFactory.getLogger(SpillService.class);

    private final StorageProvider storageProvider;
    private final SpillProperties properties;

    public SpillService(StorageProvider storageProvider, SpillProperties properties) {
        this.storageProvider = storageProvider;
        this.properties = properties;
    }

    public SpillResult spillIfNeeded(Long scopeId, String executionId, String content) {
        if (content == null || content.isEmpty()) {
            return new SpillResult(content, false, null);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= properties.getMaxInlineBytes()) {
            return new SpillResult(content, false, null);
        }
        String spillId = UUID.randomUUID().toString().substring(0, 8);
        storageProvider.write(String.valueOf(scopeId), spillPath(executionId, spillId), bytes);
        log.info("Spilled oversized tool output: scopeId={}, executionId={}, spillId={}, bytes={}, threshold={}",
                scopeId, executionId, spillId, bytes.length, properties.getMaxInlineBytes());
        String locator = "[SPILL spillId=" + spillId + " executionId=" + executionId
                + " size=" + bytes.length + "B] 工具输出超过内联阈值 " + properties.getMaxInlineBytes()
                + "B，原文已写入溢出存储，按需调用 readSpill(executionId, spillId) 读取";
        return new SpillResult(locator, true, spillId);
    }

    public String readSpill(Long scopeId, String executionId, String spillId) {
        return new String(storageProvider.read(String.valueOf(scopeId), spillPath(executionId, spillId)),
                StandardCharsets.UTF_8);
    }

    public void deleteSpills(Long scopeId, String executionId, List<String> spillIds) {
        if (spillIds == null) {
            return;
        }
        for (String spillId : spillIds) {
            if (spillId == null || spillId.isBlank()) {
                continue;
            }
            String path = spillPath(executionId, spillId);
            if (storageProvider.exists(String.valueOf(scopeId), path)) {
                storageProvider.delete(String.valueOf(scopeId), path);
            }
        }
    }

    private static String spillPath(String executionId, String spillId) {
        return "spill/" + executionId + "/" + spillId + ".txt";
    }

    public record SpillResult(String content, boolean spilled, String spillId) {}
}
