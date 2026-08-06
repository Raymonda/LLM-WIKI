package org.cn.liuwt.llmwiki.web.controller;

import org.cn.liuwt.llmwiki.common.util.exception.ErrorCode;
import org.cn.liuwt.llmwiki.common.util.result.Result;
import org.cn.liuwt.llmwiki.domain.service.harness.governance.RateLimitService;
import org.cn.liuwt.llmwiki.facade.model.SourceInfo;
import org.cn.liuwt.llmwiki.domain.model.wiki.SourceModel;
import org.cn.liuwt.llmwiki.domain.service.wiki.SourceService;
import org.cn.liuwt.llmwiki.web.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;

@RestController
@RequestMapping("/api/source")
public class SourceController {
    @Autowired
    private SourceService sourceService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RateLimitService rateLimitService;

    @PostMapping("/upload")
    public Result<SourceInfo> uploadSource(@RequestParam("file") MultipartFile file) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        Long userId = jwtTokenProvider.getCurrentUserId();

        if (!rateLimitService.checkFileSize(scopeId, file.getSize())) {
            return Result.failed(ErrorCode.INGEST_FILE_TOO_LARGE, file.getSize());
        }

        SourceModel source = sourceService.uploadSource(file, scopeId, userId);
        return Result.success(toInfo(source));
    }

    @GetMapping("/list")
    public Result<List<SourceInfo>> listSources() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        List<SourceModel> sources = sourceService.listSources(scopeId);
        List<SourceInfo> infos = sources.stream().map(this::toInfo).toList();
        return Result.success(infos);
    }

    @GetMapping("/count")
    public Result<Map<String, Long>> countSources() {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        long count = sourceService.countSources(scopeId);
        return Result.success(Map.of("count", count));
    }

    @GetMapping("/{id}")
    public Result<SourceInfo> getSource(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SourceModel source = sourceService.getSource(id, scopeId);
        if (source == null) {
            return Result.failed(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        return Result.success(toInfo(source));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteSource(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        sourceService.deleteSource(id, scopeId);
        return Result.success();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadSource(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SourceModel source = sourceService.getSource(id, scopeId);
        if (source == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        Resource resource = sourceService.getSourceResource(id, scopeId);
        if (resource == null || !resource.exists()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        String encodedName = URLEncoder.encode(source.getName(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        String contentType = guessContentType(source.getFormat());
        long contentLength = source.getSize() != null ? source.getSize() : 0;
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName)
            .contentType(MediaType.parseMediaType(contentType))
            .contentLength(contentLength)
            .body(resource);
    }

    @GetMapping("/{id}/content")
    public Result<Map<String, Object>> getSourceContent(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SourceModel source = sourceService.getSource(id, scopeId);
        if (source == null) {
            return Result.failed(ErrorCode.INGEST_SOURCE_NOT_FOUND);
        }
        String parsedContent = sourceService.getParsedContent(id, scopeId);
        String previewType = determinePreviewType(source.getFormat());
        if (parsedContent == null && "text".equals(previewType)) {
            parsedContent = sourceService.getRawTextContent(id, scopeId, source.getFormat());
        }
        Map<String, Object> data = Map.of(
            "id", id,
            "name", source.getName() != null ? source.getName() : "",
            "format", source.getFormat() != null ? source.getFormat() : "",
            "hasParsedContent", parsedContent != null,
            "parsedContent", parsedContent != null ? parsedContent : "",
            "previewType", previewType
        );
        return Result.success(data);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> previewSource(@PathVariable Long id) {
        Long scopeId = jwtTokenProvider.getCurrentScopeId();
        SourceModel source = sourceService.getSource(id, scopeId);
        if (source == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        String format = source.getFormat();
        if (!isInlinePreviewable(format)) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        Resource resource = sourceService.getSourceResource(id, scopeId);
        if (resource == null || !resource.exists()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        String encodedName = URLEncoder.encode(source.getName(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        String contentType = guessContentType(format);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName)
            .contentType(MediaType.parseMediaType(contentType))
            .body(resource);
    }

    private String determinePreviewType(String format) {
        if (format == null) return "parsed";
        String lower = format.toLowerCase();
        if (isImageFormat(lower)) return "image";
        if (lower.equals("pdf")) return "pdf";
        if (lower.equals("txt") || lower.equals("md")) return "text";
        return "parsed";
    }

    private boolean isInlinePreviewable(String format) {
        if (format == null) return false;
        String lower = format.toLowerCase();
        if (lower.equals("svg")) return false;
        return isImageFormat(lower) || lower.equals("pdf");
    }

    private boolean isImageFormat(String lower) {
        return lower.equals("jpg") || lower.equals("jpeg") || lower.equals("png")
            || lower.equals("gif") || lower.equals("bmp") || lower.equals("webp")
            || lower.equals("svg") || lower.equals("ico") || lower.equals("tiff") || lower.equals("tif");
    }

    private String guessContentType(String format) {
        if (format == null) return "application/octet-stream";
        String lower = format.toLowerCase();
        if (isImageFormat(lower)) {
            if (lower.equals("jpg") || lower.equals("jpeg")) return "image/jpeg";
            if (lower.equals("png")) return "image/png";
            if (lower.equals("gif")) return "image/gif";
            if (lower.equals("bmp")) return "image/bmp";
            if (lower.equals("webp")) return "image/webp";
            if (lower.equals("svg")) return "image/svg+xml";
            if (lower.equals("ico")) return "image/x-icon";
            if (lower.equals("tiff") || lower.equals("tif")) return "image/tiff";
            return "application/octet-stream";
        }
        return switch (lower) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt", "md" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private SourceInfo toInfo(SourceModel model) {
        SourceInfo info = new SourceInfo();
        info.setId(model.getId());
        info.setName(model.getName());
        info.setFilePath(model.getFilePath());
        info.setFormat(model.getFormat());
        info.setSize(model.getSize());
        info.setStatus(model.getStatus());
        info.setCreatedAt(model.getCreatedAt());
        info.setFileModifiedAt(model.getFileModifiedAt());
        info.setContentHash(model.getContentHash());
        info.setDuplicateInfo(model.getDuplicateInfo());
        return info;
    }
}