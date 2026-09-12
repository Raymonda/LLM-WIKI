package org.cn.liuwt.llmwiki.integration.storage;

import java.io.InputStream;
import java.util.List;

public interface StorageProvider {

    void write(String scopeId, String path, byte[] content);

    void write(String scopeId, String path, InputStream content, long size);

    byte[] read(String scopeId, String path);

    InputStream readStream(String scopeId, String path);

    boolean exists(String scopeId, String path);

    void delete(String scopeId, String path);

    String getUrl(String scopeId, String path);

    void ensureBucket(String scopeId);

    void append(String scopeId, String path, byte[] content);

    java.time.LocalDateTime getLastModifiedTime(String scopeId, String path);

    boolean scopeDirectoryExists(String scopeId);

    void moveScopeDirectory(String oldScopeId, String newScopeId);

    void move(String scopeId, String fromPath, String toPath);

    List<String> list(String scopeId, String dirPath);
}