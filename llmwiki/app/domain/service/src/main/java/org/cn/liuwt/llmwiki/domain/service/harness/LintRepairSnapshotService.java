package org.cn.liuwt.llmwiki.domain.service.harness;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.LintFindingDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.LintFindingMapper;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LintRepairSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(LintRepairSnapshotService.class);

    private static final String SNAPSHOT_DIR = "lint/snapshots/";

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private LintFindingMapper lintFindingMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String snapshotPath(Long findingId) {
        return SNAPSHOT_DIR + findingId + ".md";
    }

    public void snapshotBeforeRewrite(Long scopeId, Long findingId, String pageStoragePath) {
        if (scopeId == null || findingId == null || pageStoragePath == null || pageStoragePath.isBlank()) {
            return;
        }
        try {
            byte[] existing = storageProvider.read(String.valueOf(scopeId), pageStoragePath);
            if (existing == null) {
                return;
            }
            String snapshotPath = snapshotPath(findingId);
            storageProvider.write(String.valueOf(scopeId), snapshotPath, existing);

            LintFindingDO finding = lintFindingMapper.selectById(findingId);
            if (finding == null) {
                return;
            }
            Map<String, Object> extraMap = readExtra(finding.getExtra());
            extraMap.put("snapshotPath", snapshotPath);
            extraMap.put("restorePath", pageStoragePath);
            extraMap.put("snapshotAt", LocalDateTime.now().toString());
            lintFindingMapper.update(null, new LambdaUpdateWrapper<LintFindingDO>()
                .eq(LintFindingDO::getId, findingId)
                .set(LintFindingDO::getExtra, objectMapper.writeValueAsString(extraMap)));
        } catch (Exception e) {
            log.warn("snapshotBeforeRewrite failed: scopeId={}, findingId={}, path={}, error={}",
                scopeId, findingId, pageStoragePath, e.getMessage());
        }
    }

    public String restoreSnapshot(Long scopeId, String snapshotPath, String restorePath) {
        byte[] bytes = storageProvider.read(String.valueOf(scopeId), snapshotPath);
        if (bytes == null) {
            return null;
        }
        storageProvider.write(String.valueOf(scopeId), restorePath, bytes);
        deleteSnapshot(scopeId, snapshotPath);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void deleteSnapshot(Long scopeId, String snapshotPath) {
        if (scopeId == null || snapshotPath == null || snapshotPath.isBlank()) {
            return;
        }
        try {
            storageProvider.delete(String.valueOf(scopeId), snapshotPath);
        } catch (Exception e) {
            log.warn("deleteSnapshot failed: scopeId={}, path={}, error={}", scopeId, snapshotPath, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readExtra(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(extraJson, LinkedHashMap.class);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
