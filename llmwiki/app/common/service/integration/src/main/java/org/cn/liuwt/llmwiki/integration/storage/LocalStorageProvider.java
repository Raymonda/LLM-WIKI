package org.cn.liuwt.llmwiki.integration.storage;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
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

    @Autowired(required = false)
    private Environment environment;

    public void setBasePath(String basePath) {
        this.basePath = basePath != null ? Paths.get(basePath).toAbsolutePath().normalize().toString() : null;
    }

    @PostConstruct
    void init() {
        boolean explicit = isWikiDataPathExplicitlyConfigured();
        basePath = Paths.get(basePath).toAbsolutePath().normalize().toString();
        if (explicit) {
            log.info("Wiki data storage root (local): {}", basePath);
        } else {
            log.warn("");
            log.warn("================ WIKI DATA PATH WARNING ================");
            log.warn("  llmwiki.wiki-data-path 未显式配置，默认 ./wiki-data 已解析为:");
            log.warn("    {}", basePath);
            log.warn("  该路径锚定在应用启动目录(user.dir)，从不同目录启动将读写");
            log.warn("  不同的 wiki-data，造成 DB 记录与文件漂移（ingest 丢文件）。");
            log.warn("  修复: 设置环境变量 WIKI_DATA_PATH 为绝对路径后重启。");
            log.warn("==========================================================");
            log.warn("");
        }
    }

    private boolean isWikiDataPathExplicitlyConfigured() {
        if (environment == null) {
            return true;
        }
        String envVar = environment.getProperty("WIKI_DATA_PATH");
        if (envVar != null && !envVar.isBlank()) {
            return true;
        }
        String prop = environment.getProperty("llmwiki.wiki-data-path");
        return prop != null && !prop.isBlank() && !"./wiki-data".equals(prop.trim());
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

    @Override
    public boolean scopeDirectoryExists(String scopeId) {
        validateScopeId(scopeId);
        Path scopePath = Paths.get(basePath, scopeId).normalize();
        Path base = Paths.get(basePath).normalize();
        if (!scopePath.startsWith(base)) {
            throw new SecurityException("Path traversal detected in scopeId: " + scopeId);
        }
        return Files.isDirectory(scopePath);
    }

    @Override
    public void moveScopeDirectory(String oldScopeId, String newScopeId) {
        validateScopeId(oldScopeId);
        validateScopeId(newScopeId);
        Path source = Paths.get(basePath, oldScopeId).normalize();
        Path target = Paths.get(basePath, newScopeId).normalize();
        Path base = Paths.get(basePath).normalize();
        if (!source.startsWith(base) || !target.startsWith(base)) {
            throw new SecurityException("Path traversal detected in scope move: " + oldScopeId + " -> " + newScopeId);
        }
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            log.error("Failed to move scope directory: {} -> {}", oldScopeId, newScopeId, e);
            throw new RuntimeException("Failed to move scope directory: " + oldScopeId + " -> " + newScopeId, e);
        }
    }

    @Override
    public void move(String scopeId, String fromPath, String toPath) {
        Path source = resolvePath(scopeId, fromPath);
        Path target = resolvePath(scopeId, toPath);
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        } catch (IOException e) {
            log.error("Failed to move file: scopeId={}, from={}, to={}", scopeId, fromPath, toPath, e);
            throw new RuntimeException("Failed to move file: " + fromPath + " -> " + toPath, e);
        }
    }

    @Override
    public java.util.List<String> list(String scopeId, String dirPath) {
        Path dir = resolvePath(scopeId, dirPath);
        if (!Files.isDirectory(dir)) {
            return java.util.Collections.emptyList();
        }
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> dirPath + "/" + p.getFileName())
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list directory: scopeId={}, path={}", scopeId, dirPath, e);
            throw new RuntimeException("Failed to list directory: " + dirPath, e);
        }
    }
}