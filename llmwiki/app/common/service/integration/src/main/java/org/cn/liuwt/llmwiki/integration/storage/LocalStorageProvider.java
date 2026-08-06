package org.cn.liuwt.llmwiki.integration.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Component
@ConditionalOnProperty(name = "llmwiki.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageProvider.class);

    @Value("${llmwiki.wiki-data-path:./wiki-data}")
    private String basePath;

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    private Path resolvePath(String scopeId, String relativePath) {
        validateScopeId(scopeId);
        validatePathComponent(relativePath, "path");
        Path resolved = Paths.get(basePath, scopeId, relativePath).normalize();
        Path base = Paths.get(basePath).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        return resolved;
    }

    private void validatePathComponent(String component, String name) {
        if (component == null || component.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        if (component.contains("..")) {
            throw new SecurityException("Invalid " + name + ": path traversal detected in " + component);
        }
    }

    private void validateScopeId(String scopeId) {
        if (scopeId == null || scopeId.isEmpty()) {
            throw new IllegalArgumentException("scopeId must not be null or empty");
        }
        if (scopeId.contains("..") || scopeId.contains("/") || scopeId.contains("\\")) {
            throw new SecurityException("Invalid scopeId: illegal characters detected in " + scopeId);
        }
    }

    @Override
    public void write(String scopeId, String path, byte[] content) {
        Path filePath = resolvePath(scopeId, path);
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);
        } catch (IOException e) {
            log.error("Failed to write file: scopeId={}, path={}", scopeId, path, e);
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }

    @Override
    public void write(String scopeId, String path, InputStream content, long size) {
        Path filePath = resolvePath(scopeId, path);
        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to write file from stream: scopeId={}, path={}", scopeId, path, e);
            throw new RuntimeException("Failed to write file from stream: " + path, e);
        }
    }

    @Override
    public byte[] read(String scopeId, String path) {
        Path filePath = resolvePath(scopeId, path);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read file: scopeId={}, path={}", scopeId, path, e);
            throw new RuntimeException("Failed to read file: " + path, e);
        }
    }

    @Override
    public InputStream readStream(String scopeId, String path) {
        Path filePath = resolvePath(scopeId, path);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            log.error("Failed to read file stream: scopeId={}, path={}", scopeId, path, e);
            throw new RuntimeException("Failed to read file stream: " + path, e);
        }
    }

    @Override
    public boolean exists(String scopeId, String path) {
        return Files.exists(resolvePath(scopeId, path));
    }

    @Override
    public void delete(String scopeId, String path) {
        Path filePath = resolvePath(scopeId, path);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete file: scopeId={}, path={}", scopeId, path, e);
        }
    }

    @Override
    public String getUrl(String scopeId, String path) {
        return resolvePath(scopeId, path).toAbsolutePath().toString();
    }

    @Override
    public void ensureBucket(String scopeId) {
        validateScopeId(scopeId);
        Path scopePath = Paths.get(basePath, scopeId).normalize();
        Path base = Paths.get(basePath).normalize();
        if (!scopePath.startsWith(base)) {
            throw new SecurityException("Path traversal detected in scopeId: " + scopeId);
        }
        try {
            Files.createDirectories(scopePath);
        } catch (IOException e) {
            log.error("Failed to create scope directory: {}", scopeId, e);
            throw new RuntimeException("Failed to create scope directory: " + scopeId, e);
        }
    }

    @Override
    public java.time.LocalDateTime getLastModifiedTime(String scopeId, String path) {
        Path filePath = resolvePath(scopeId, path);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return java.time.LocalDateTime.ofInstant(
                Files.getLastModifiedTime(filePath).toInstant(),
                java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            log.error("Failed to get last modified time: scopeId={}, path={}", scopeId, path, e);
            return null;
        }
    }

    @Override
    public void append(String scopeId, String path, byte[] content) {
        Path filePath = resolvePath(scopeId, path);
        try {
            Files.createDirectories(filePath.getParent());
            if (!Files.exists(filePath)) {
                Files.write(filePath, content);
            } else {
                Files.write(filePath, content, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.error("Failed to append file: scopeId={}, path={}", scopeId, path, e);
            throw new RuntimeException("Failed to append file: " + path, e);
        }
    }
}