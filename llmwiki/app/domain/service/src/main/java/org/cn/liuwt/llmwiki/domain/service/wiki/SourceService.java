package org.cn.liuwt.llmwiki.domain.service.wiki;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.cn.liuwt.llmwiki.common.dal.dataobject.SourceDO;
import org.cn.liuwt.llmwiki.common.dal.mapper.SourceMapper;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.facade.model.DuplicateInfo;
import org.cn.liuwt.llmwiki.integration.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SourceService {
    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private SourceMapper sourceMapper;

    public SourceModel uploadSource(MultipartFile file, Long scopeId, Long userId) {
        String originalName = file.getOriginalFilename();
        String format = extractFormat(originalName);
        String scopeIdStr = String.valueOf(scopeId);
        String uniqueName = buildUniqueFileName(originalName);
        String storagePath = "raw/" + uniqueName;

        try {
            storageProvider.ensureBucket(scopeIdStr);

            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            DigestInputStream digestInputStream = new DigestInputStream(file.getInputStream(), messageDigest);
            storageProvider.write(scopeIdStr, storagePath, digestInputStream, file.getSize());

            byte[] hashBytes = messageDigest.digest();
            String contentHash = bytesToHex(hashBytes);

            SourceDO sourceDO = new SourceDO();
            sourceDO.setName(originalName);
            sourceDO.setFilePath(storagePath);
            sourceDO.setFormat(format);
            sourceDO.setSize(file.getSize());
            sourceDO.setStatus("uploaded");
            sourceDO.setScopeId(scopeId);
            sourceDO.setUploadUserId(userId);
            sourceDO.setContentHash(contentHash);

            java.time.LocalDateTime fileModifiedTime = storageProvider.getLastModifiedTime(scopeIdStr, storagePath);
            sourceDO.setFileModifiedAt(fileModifiedTime);

            sourceMapper.insert(sourceDO);

            SourceModel model = toModel(sourceDO);
            SourceModel duplicate = findDuplicateSource(scopeId, contentHash);
            if (duplicate != null) {
                DuplicateInfo duplicateInfo = new DuplicateInfo();
                duplicateInfo.setExistingSourceId(duplicate.getId());
                duplicateInfo.setExistingSourceName(duplicate.getName());
                duplicateInfo.setExistingSourceStatus(duplicate.getStatus());
                duplicateInfo.setMessage("此文件内容与已有来源「" + duplicate.getName() + "」相同（" + duplicate.getStatus() + "），AI 分析可能产出重复内容。");
                model.setDuplicateInfo(duplicateInfo);
            } else {
                SourceModel processing = findProcessingSource(scopeId, contentHash);
                if (processing != null) {
                    DuplicateInfo duplicateInfo = new DuplicateInfo();
                    duplicateInfo.setExistingSourceId(processing.getId());
                    duplicateInfo.setExistingSourceName(processing.getName());
                    duplicateInfo.setExistingSourceStatus(processing.getStatus());
                    duplicateInfo.setMessage("此文件内容与正在处理中的来源「" + processing.getName() + "」相同，建议等待处理完成后再决定是否重新分析。");
                    model.setDuplicateInfo(duplicateInfo);
                }
            }
            return model;
        } catch (Exception e) {
            log.error("Failed to upload source file: {}", originalName, e);
            throw new RuntimeException("Failed to upload source file: " + originalName, e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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

    private String buildUniqueFileName(String originalName) {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        if (originalName == null || originalName.isBlank()) {
            return uuid;
        }
        String safeName = originalName.replace('\\', '/');
        int slashIdx = safeName.lastIndexOf('/');
        if (slashIdx >= 0) {
            safeName = safeName.substring(slashIdx + 1);
        }
        return uuid + "-" + safeName;
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

    public void deleteSource(Long id, Long scopeId) {
        SourceDO sourceDO = sourceMapper.selectOne(
            new LambdaQueryWrapper<SourceDO>()
                .eq(SourceDO::getId, id)
                .eq(SourceDO::getScopeId, scopeId)
        );
        if (sourceDO == null) {
            return;
        }
        String scopeIdStr = String.valueOf(scopeId);
        storageProvider.delete(scopeIdStr, sourceDO.getFilePath());
        sourceMapper.deleteById(id);
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
        return model;
    }
}