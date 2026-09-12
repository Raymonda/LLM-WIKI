package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.ExecutionDO;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.ExecutionMapper;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.common.util.exception.BusinessException;
import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.domain.model.system.AuditLogModel;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.harness.LintFindingService;
import org.cn.liuwt.llmwiki.domain.service.system.AuditLogService;
import org.cn.liuwt.llmwiki.facade.model.DuplicateInfo;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SourceService {
    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SourceMapper sourceMapper;

    private static final Set<String> DEPRECATE_BLOCKING_EXECUTION_STATUSES =
        Set.of("pending", "running", "awaiting_confirmation", "awaiting_review", "confirmed", "paused");

    private static final Set<String> VALID_DEPRECATE_CATEGORIES =
        Set.of("OUTDATED", "SUPERSEDED", "ERRONEOUS", "OTHER");

    private static final String TEMP_DIR = "raw/.tmp";
    private static final long TEMP_FILE_TTL_HOURS = 24;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private LintFindingService lintFindingService;

    public SourceModel uploadSource(MultipartFile file, Long scopeId, Long userId) {
        String originalName = file.getOriginalFilename();
        String format = extractFormat(originalName);
        String scopeIdStr = String.valueOf(scopeId);
        try {
            storageProvider.ensureBucket(scopeIdStr);
            cleanupStaleTempFiles(scopeIdStr);

            String tempPath = TEMP_DIR + "/" + UUID.randomUUID().toString().replace("-", "");
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(file.getInputStream(), messageDigest)) {
                storageProvider.write(scopeIdStr, tempPath, in, file.getSize());
            }
            String contentHash = bytesToHex(messageDigest.digest());
            String casPath = casPathOf(contentHash);
            finalizeCasUpload(scopeIdStr, tempPath, casPath);

            SourceDO sourceDO = new SourceDO();
            sourceDO.setName(originalName);
            sourceDO.setFilePath(casPath);
            sourceDO.setFormat(format);
            sourceDO.setSize(file.getSize());
            sourceDO.setStatus("uploaded");
            sourceDO.setScopeId(scopeId);
            sourceDO.setUploadUserId(userId);
            sourceDO.setContentHash(contentHash);
            sourceDO.setLifecycleStatus("ACTIVE");
            sourceDO.setFileModifiedAt(storageProvider.getLastModifiedTime(scopeIdStr, casPath));
            sourceMapper.insert(sourceDO);

            SourceModel model = toModel(sourceDO);
            SourceModel duplicate = findDuplicateSource(scopeId, contentHash);
            if (duplicate != null) {
                DuplicateInfo duplicateInfo = new DuplicateInfo();
                duplicateInfo.setExistingSourceId(duplicate.getId());
                duplicateInfo.setExistingSourceName(duplicate.getName());
                duplicateInfo.setExistingSourceStatus(duplicate.getStatus());
                duplicateInfo.setExistingSourceLifecycleStatus(duplicate.getLifecycleStatus());
                duplicateInfo.setExistingSourceDeprecatedReason(duplicate.getDeprecatedReason());
                if ("DEPRECATED".equals(duplicate.getLifecycleStatus())) {
                    String reasonSuffix = duplicate.getDeprecatedReason() != null
                        && !duplicate.getDeprecatedReason().isBlank()
                        ? "（原因：" + duplicate.getDeprecatedReason() + "）" : "";
                    duplicateInfo.setMessage("此文件内容与已废弃来源「" + duplicate.getName()
                        + "」相同" + reasonSuffix + "，AI 分析可能产出重复内容。");
                } else {
                    duplicateInfo.setMessage("此文件内容与已有来源「" + duplicate.getName()
                        + "」相同（" + duplicate.getStatus() + "），AI 分析可能产出重复内容。");
                }
                model.setDuplicateInfo(duplicateInfo);
            } else {
                SourceModel processing = findProcessingSource(scopeId, contentHash);
                if (processing != null) {
                    DuplicateInfo duplicateInfo = new DuplicateInfo();
                    duplicateInfo.setExistingSourceId(processing.getId());
                    duplicateInfo.setExistingSourceName(processing.getName());
                    duplicateInfo.setExistingSourceStatus(processing.getStatus());
                    duplicateInfo.setExistingSourceLifecycleStatus(processing.getLifecycleStatus());
                    duplicateInfo.setExistingSourceDeprecatedReason(processing.getDeprecatedReason());
                    duplicateInfo.setMessage("此文件内容与正在处理中的来源「" + processing.getName()
                        + "」相同，建议等待处理完成后再决定是否重新分析。");
                    model.setDuplicateInfo(duplicateInfo);
                }
            }
            return model;
        } catch (Exception e) {
            log.error("Failed to upload source file: {}", originalName, e);
            throw new RuntimeException("Failed to upload source file: " + originalName, e);
        }
    }

    /**
     * MCP 文本摄入后端：把一段 Markdown 直接写入 raw/ 存储并落 SourceDO。
     * 与 uploadSource 共用存储约定（SHA-256 contentHash）与查重语义（DuplicateInfo）。
     */
    public SourceModel uploadTextSource(String title, String markdown, Long scopeId, Long userId) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String originalName = (title != null && title.toLowerCase().endsWith(".md")) ? title : title + ".md";
        String scopeIdStr = String.valueOf(scopeId);
        try {
            storageProvider.ensureBucket(scopeIdStr);
            cleanupStaleTempFiles(scopeIdStr);

            String tempPath = TEMP_DIR + "/" + UUID.randomUUID().toString().replace("-", "");
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                DigestInputStream digestInputStream = new DigestInputStream(in, messageDigest);
                storageProvider.write(scopeIdStr, tempPath, digestInputStream, (long) bytes.length);
            }
            String contentHash = bytesToHex(messageDigest.digest());
            String casPath = casPathOf(contentHash);
            finalizeCasUpload(scopeIdStr, tempPath, casPath);

            SourceDO sourceDO = new SourceDO();
            sourceDO.setName(originalName);
            sourceDO.setFilePath(casPath);
            sourceDO.setFormat("md");
            sourceDO.setSize((long) bytes.length);
            sourceDO.setStatus("uploaded");
            sourceDO.setScopeId(scopeId);
            sourceDO.setUploadUserId(userId);
            sourceDO.setContentHash(contentHash);
            sourceDO.setLifecycleStatus("ACTIVE");
            sourceDO.setFileModifiedAt(storageProvider.getLastModifiedTime(scopeIdStr, casPath));
            sourceMapper.insert(sourceDO);

            SourceModel model = toModel(sourceDO);
            SourceModel duplicate = findDuplicateSource(scopeId, contentHash);
            if (duplicate == null) {
                duplicate = findProcessingSource(scopeId, contentHash);
            }
            if (duplicate != null) {
                DuplicateInfo duplicateInfo = new DuplicateInfo();
                duplicateInfo.setExistingSourceId(duplicate.getId());
                duplicateInfo.setExistingSourceName(duplicate.getName());
                duplicateInfo.setExistingSourceStatus(duplicate.getStatus());
                duplicateInfo.setExistingSourceLifecycleStatus(duplicate.getLifecycleStatus());
                duplicateInfo.setExistingSourceDeprecatedReason(duplicate.getDeprecatedReason());
                if ("DEPRECATED".equals(duplicate.getLifecycleStatus())) {
                    String reasonSuffix = duplicate.getDeprecatedReason() != null
                        && !duplicate.getDeprecatedReason().isBlank()
                        ? "（原因：" + duplicate.getDeprecatedReason() + "）" : "";
                    duplicateInfo.setMessage("此内容与已废弃来源「" + duplicate.getName()
                        + "」相同" + reasonSuffix + "，AI 分析可能产出重复内容。");
                } else {
                    duplicateInfo.setMessage("此内容与已有来源「" + duplicate.getName()
                        + "」相同（" + duplicate.getStatus() + "），AI 分析可能产出重复内容。");
                }
                model.setDuplicateInfo(duplicateInfo);
            }
            return model;
        } catch (Exception e) {
            log.error("Failed to upload text source: {}", originalName, e);
            throw new RuntimeException("Failed to upload text source: " + originalName, e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String casPathOf(String contentHash) {
        return "raw/" + contentHash.substring(0, 2) + "/" + contentHash.substring(2, 4) + "/" + contentHash;
    }

    private void finalizeCasUpload(String scopeIdStr, String tempPath, String casPath) {
        if (storageProvider.exists(scopeIdStr, casPath)) {
            storageProvider.delete(scopeIdStr, tempPath);
            log.info("CAS dedup hit, reusing existing raw object: {}", casPath);
            return;
        }
        try {
            storageProvider.move(scopeIdStr, tempPath, casPath);
        } catch (Exception e) {
            storageProvider.delete(scopeIdStr, tempPath);
            if (storageProvider.exists(scopeIdStr, casPath)) {
                log.info("CAS concurrent dedup hit, reusing existing raw object: {}", casPath);
                return;
            }
            throw e;
        }
    }

    private void cleanupStaleTempFiles(String scopeIdStr) {
        try {
            List<String> tempFiles = storageProvider.list(scopeIdStr, TEMP_DIR);
            if (tempFiles.isEmpty()) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusHours(TEMP_FILE_TTL_HOURS);
            for (String tempFile : tempFiles) {
                LocalDateTime modified = storageProvider.getLastModifiedTime(scopeIdStr, tempFile);
                if (modified != null && modified.isBefore(cutoff)) {
                    storageProvider.delete(scopeIdStr, tempFile);
                    log.info("Cleaned stale temp upload file: {}", tempFile);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean stale temp upload files: {}", e.getMessage());
        }
    }

    public SourceModel findDuplicateSource(Long scopeId, String contentHash) {
        if (contentHash == null) return null;
        SourceDO duplicate = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getScopeId, scopeId)
                .eq(SourceDO::getContentHash, contentHash)
                .eq(SourceDO::getStatus, "processed")
                .last("LIMIT 1")
        );
        return duplicate != null ? toModel(duplicate) : null;
    }

    public SourceModel findProcessingSource(Long scopeId, String contentHash) {
        if (contentHash == null) return null;
        SourceDO processing = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getScopeId, scopeId)
                .eq(SourceDO::getContentHash, contentHash)
                .eq(SourceDO::getStatus, "processing")
                .last("LIMIT 1")
        );
        return processing != null ? toModel(processing) : null;
    }

    public List<SourceModel> listSources(Long scopeId) {
        List<SourceDO> sourceDOs = sourceMapper.selectList(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getScopeId, scopeId)
                .orderByDesc(SourceDO::getCreatedAt)
        );
        return sourceDOs.stream().map(this::toModel).collect(Collectors.toList());
    }

    public long countSources(Long scopeId) {
        return sourceMapper.selectCount(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getScopeId, scopeId)
        );
    }

    public SourceModel getSource(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        return toModel(sourceDO);
    }

    public byte[] getSourceFileContent(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        String scopeIdStr = String.valueOf(scopeId);
        return storageProvider.read(scopeIdStr, sourceDO.getFilePath());
    }

    public InputStream getSourceFileStream(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        String scopeIdStr = String.valueOf(scopeId);
        return storageProvider.readStream(scopeIdStr, sourceDO.getFilePath());
    }

    public Resource getSourceResource(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        String scopeIdStr = String.valueOf(scopeId);
        String absolutePath = storageProvider.getUrl(scopeIdStr, sourceDO.getFilePath());
        return new FileSystemResource(absolutePath);
    }

    public String getParsedContent(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        String scopeIdStr = String.valueOf(scopeId);
        String parsedPath = "parsed/" + id + ".parsed.md";
        if (!storageProvider.exists(scopeIdStr, parsedPath)) {
            return null;
        }
        byte[] content = storageProvider.read(scopeIdStr, parsedPath);
        if (content == null) {
            return null;
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public String getRawTextContent(Long id, Long scopeId, String format) {
        if (format == null) return null;
        String lower = format.toLowerCase();
        if (!lower.equals("txt") && !lower.equals("md") && !lower.equals("csv")
            && !lower.equals("json") && !lower.equals("xml") && !lower.equals("yaml")
            && !lower.equals("yml") && !lower.equals("log") && !lower.equals("html")
            && !lower.equals("htm") && !lower.equals("properties") && !lower.equals("cfg")
            && !lower.equals("ini") && !lower.equals("conf")) {
            return null;
        }
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return null;
        }
        String scopeIdStr = String.valueOf(scopeId);
        byte[] content = storageProvider.read(scopeIdStr, sourceDO.getFilePath());
        if (content == null) {
            return null;
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public SourceModel deprecateSource(Long id, Long scopeId, Long userId, String category, String reason) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            throw new BusinessException(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        if ("DEPRECATED".equals(sourceDO.getLifecycleStatus())) {
            throw new BusinessException(ErrorCode.SOURCE_ALREADY_DEPRECATED);
        }
        if (category == null || !VALID_DEPRECATE_CATEGORIES.contains(category)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "category");
        }
        if ("OTHER".equals(category) && (reason == null || reason.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "reason");
        }
        boolean inFlight = "processing".equals(sourceDO.getStatus())
            || executionMapper.selectCount(new LambdaQueryWrapper<ExecutionDO>()
                .eq(ExecutionDO::getType, "ingest")
                .eq(ExecutionDO::getScopeId, scopeId)
                .eq(ExecutionDO::getSourceId, id)
                .in(ExecutionDO::getStatus, DEPRECATE_BLOCKING_EXECUTION_STATUSES)) > 0;
        if (inFlight) {
            throw new BusinessException(ErrorCode.SOURCE_DEPRECATE_WHILE_PROCESSING);
        }

        LocalDateTime now = LocalDateTime.now();
        sourceMapper.update(null,
            new LambdaUpdateWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
                .set(SourceDO::getLifecycleStatus, "DEPRECATED")
                .set(SourceDO::getDeprecatedAt, now)
                .set(SourceDO::getDeprecatedCategory, category)
                .set(SourceDO::getDeprecatedReason, reason)
                .set(SourceDO::getDeprecatedBy, userId)
        );
        writeAuditLog(scopeId, userId, "SOURCE_DEPRECATE", id, sourceDO.getName(),
            Map.of("category", category, "reason", reason != null ? reason : ""));

        sourceDO.setLifecycleStatus("DEPRECATED");
        sourceDO.setDeprecatedAt(now);
        sourceDO.setDeprecatedCategory(category);
        sourceDO.setDeprecatedReason(reason);
        sourceDO.setDeprecatedBy(userId);
        return toModel(sourceDO);
    }

    public SourceModel undeprecateSource(Long id, Long scopeId, Long userId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            throw new BusinessException(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        if (!"DEPRECATED".equals(sourceDO.getLifecycleStatus())) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_DEPRECATED);
        }
        sourceMapper.update(null,
            new LambdaUpdateWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
                .set(SourceDO::getLifecycleStatus, "ACTIVE")
                .set(SourceDO::getDeprecatedAt, null)
                .set(SourceDO::getDeprecatedCategory, null)
                .set(SourceDO::getDeprecatedReason, null)
                .set(SourceDO::getDeprecatedBy, null)
        );
        lintFindingService.resolveSourceFindingsOnUndeprecate(scopeId, id);
        writeAuditLog(scopeId, userId, "SOURCE_UNDEPRECATE", id, sourceDO.getName(), Map.of());

        sourceDO.setLifecycleStatus("ACTIVE");
        sourceDO.setDeprecatedAt(null);
        sourceDO.setDeprecatedCategory(null);
        sourceDO.setDeprecatedReason(null);
        sourceDO.setDeprecatedBy(null);
        return toModel(sourceDO);
    }

    public void deleteSource(Long id, Long scopeId) {
        throw new BusinessException(ErrorCode.SOURCE_DELETE_FORBIDDEN);
    }

    private void writeAuditLog(Long scopeId, Long userId, String action, Long sourceId,
                               String sourceName, Map<String, Object> detail) {
        try {
            AuditLogModel model = new AuditLogModel();
            model.setActorUserId(userId);
            model.setAction(action);
            model.setTargetType("source");
            model.setTargetId(sourceId);
            model.setTargetName(sourceName);
            model.setScopeId(scopeId);
            model.setDetailJson(objectMapper.writeValueAsString(detail));
            auditLogService.log(model);
        } catch (Exception e) {
            log.warn("Failed to write audit log: action={}, sourceId={}", action, sourceId, e);
        }
    }

    private String extractFormat(String filename) {
        if (filename == null) return "unknown";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return "unknown";
        return filename.substring(dotIndex + 1).toLowerCase();
    }

    private SourceModel toModel(SourceDO sourceDO) {
        SourceModel model = new SourceModel();
        model.setId(sourceDO.getId());
        model.setName(sourceDO.getName());
        model.setFilePath(sourceDO.getFilePath());
        model.setFormat(sourceDO.getFormat());
        model.setSize(sourceDO.getSize());
        model.setStatus(sourceDO.getStatus());
        model.setScopeId(sourceDO.getScopeId());
        model.setUploadUserId(sourceDO.getUploadUserId());
        model.setCreatedAt(sourceDO.getCreatedAt());
        model.setFileModifiedAt(sourceDO.getFileModifiedAt());
        model.setContentHash(sourceDO.getContentHash());
        model.setLifecycleStatus(sourceDO.getLifecycleStatus());
        model.setDeprecatedAt(sourceDO.getDeprecatedAt());
        model.setDeprecatedCategory(sourceDO.getDeprecatedCategory());
        model.setDeprecatedReason(sourceDO.getDeprecatedReason());
        model.setDeprecatedBy(sourceDO.getDeprecatedBy());
        return model;
    }
}