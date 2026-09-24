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
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

@Component
@ConditionalOnProperty(name = "llmwiki.storage.provider", havingValue = "nas")
public class NASStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(NASStorageProvider.class);

    @Value("${llmwiki.storage.nas.path:${llmwiki.wiki-data-path:./wiki-data}}")
    private String basePath;

    @Value("${llmwiki.storage.nas.lock-enabled:true}")
    private boolean lockEnabled;

    @Autowired(required = false)
    private Environment environment;

    public void setBasePath(String basePath) {
        this.basePath = basePath != null ? Paths.get(basePath).toAbsolutePath().normalize().toString() : null;
    }

    @PostConstruct
    void init() {
        basePath = Paths.get(basePath).toAbsolutePath().normalize().toString();
        if (isNasPathExplicitlyConfigured()) {
            log.info("Wiki data storage root (nas): {}", basePath);
        } else {
            log.warn("");
            log.warn("================ WIKI DATA PATH WARNING ================");
            log.warn("  llmwiki.storage.nas.path 未显式配置，默认 ./wiki-data 已解析为:");
            log.warn("    {}", basePath);
            log.warn("  该路径锚定在应用启动目录(user.dir)，从不同目录启动将读写");
            log.warn("  不同的 wiki-data，造成 DB 记录与文件漂移（ingest 丢文件）。");
            log.warn("  修复: 设置环境变量 WIKI_DATA_PATH 为绝对路径后重启。");
            log.warn("==========================================================");
            log.warn("");
        }
    }

    private boolean isNasPathExplicitlyConfigured() {
        if (environment == null) {
            return true;
        }
        String nasPath = environment.getProperty("llmwiki.storage.nas.path");
        if (nasPath != null && !nasPath.isBlank()) {
            return true;
        }
        String envVar = environment.getProperty("WIKI_DATA_PATH");
        if (envVar != null && !envVar.isBlank()) {
            return true;
        }
        String prop = environment.getProperty("llmwiki.wiki-data-path");
        return prop != null && !prop.isBlank() && !"./wiki-data".equals(prop.trim());
    }

    public void setLockEnabled(boolean lockEnabled) {
        this.lockEnabled = lockEnabled;
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directories: " + filePath, e);
        }
        if (lockEnabled) {
            writeWithLock(filePath, content);
        } else {
            writeDirect(filePath, content);
        }
    }

    @Override
    public void write(String scopeId, String path, InputStream content, long size) {
        Path filePath = resolvePath(scopeId, path);
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directories: " + filePath, e);
        }
        if (lockEnabled) {
            writeStreamWithLock(filePath, content);
        } else {
            writeStreamDirect(filePath, content);
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
            healthCheck(scopePath);
        } catch (IOException e) {
            log.error("Failed to create scope directory or health check: {}", scopeId, e);
            throw new RuntimeException("NAS health check failed for scope: " + scopeId, e);
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
        } catch (IOException e) {
            throw new RuntimeException("Failed to create directories: " + filePath, e);
        }
        if (lockEnabled) {
            appendWithLock(filePath, content);
        } else {
            appendDirect(filePath, content);
        }
    }

    private void appendDirect(Path filePath, byte[] content) {
        try {
            if (!Files.exists(filePath)) {
                Files.write(filePath, content);
            } else {
                Files.write(filePath, content, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.error("Failed to append file: path={}", filePath, e);
            throw new RuntimeException("Failed to append file: " + filePath, e);
        }
    }

    private void appendWithLock(Path filePath, byte[] content) {
        try {
            StandardOpenOption[] options = Files.exists(filePath)
                ? new StandardOpenOption[]{StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE};
            FileChannel channel = FileChannel.open(filePath, options);
            try {
                FileLock lock = acquireLock(channel, filePath);
                if (lock != null) {
                    try {
                        channel.write(java.nio.ByteBuffer.wrap(content));
                    } finally {
                        releaseLock(lock);
                    }
                } else {
                    log.warn("Could not acquire file lock for append, falling back to direct: {}", filePath);
                    appendDirect(filePath, content);
                }
            } finally {
                closeChannel(channel);
            }
        } catch (IOException e) {
            log.error("Failed to append file with lock: path={}", filePath, e);
            throw new RuntimeException("Failed to append file with lock: " + filePath, e);
        }
    }

    private void writeDirect(Path filePath, byte[] content) {
        try {
            Files.write(filePath, content);
        } catch (IOException e) {
            log.error("Failed to write file: path={}", filePath, e);
            throw new RuntimeException("Failed to write file: " + filePath, e);
        }
    }

    private void writeStreamDirect(Path filePath, InputStream content) {
        try {
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to write file from stream: path={}", filePath, e);
            throw new RuntimeException("Failed to write file from stream: " + filePath, e);
        }
    }

    private void writeWithLock(Path filePath, byte[] content) {
        try {
            FileChannel channel = FileChannel.open(filePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                FileLock lock = acquireLock(channel, filePath);
                if (lock != null) {
                    try {
                        channel.write(java.nio.ByteBuffer.wrap(content));
                    } finally {
                        releaseLock(lock);
                    }
                } else {
                    log.warn("Could not acquire file lock, falling back to direct write: {}", filePath);
                    Files.write(filePath, content);
                }
            } finally {
                closeChannel(channel);
            }
        } catch (IOException e) {
            log.error("Failed to write file with lock: path={}", filePath, e);
            throw new RuntimeException("Failed to write file with lock: " + filePath, e);
        }
    }

    private void writeStreamWithLock(Path filePath, InputStream content) {
        Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.copy(content, tempFile, StandardCopyOption.REPLACE_EXISTING);
            byte[] tempContent = Files.readAllBytes(tempFile);
            writeWithLock(filePath, tempContent);
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            log.error("Failed to write stream with lock: path={}", filePath, e);
            throw new RuntimeException("Failed to write stream with lock: " + filePath, e);
        }
    }

    private FileLock acquireLock(FileChannel channel, Path filePath) {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException e) {
            log.warn("Overlapping file lock on {}, same JVM already holds lock", filePath);
            return null;
        } catch (IOException e) {
            log.warn("Failed to acquire file lock on {}, NFS lock may not be supported: {}", filePath, e.getMessage());
            return null;
        }
    }

    private void releaseLock(FileLock lock) {
        try {
            lock.release();
        } catch (IOException e) {
            log.warn("Failed to release file lock: {}", e.getMessage());
        }
    }

    private void closeChannel(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("Failed to close file channel: {}", e.getMessage());
        }
    }

    private void healthCheck(Path scopePath) throws IOException {
        String testFileName = ".nas-health-check-" + System.nanoTime();
        Path testFile = scopePath.resolve(testFileName);
        byte[] testData = ("nas-ok-" + System.nanoTime()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            Files.write(testFile, testData);
            byte[] readData = Files.readAllBytes(testFile);
            if (!java.util.Arrays.equals(testData, readData)) {
                throw new IOException("NAS health check failed: read data mismatch");
            }
        } finally {
            Files.deleteIfExists(testFile);
        }
        log.info("NAS health check passed: {}", scopePath);
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