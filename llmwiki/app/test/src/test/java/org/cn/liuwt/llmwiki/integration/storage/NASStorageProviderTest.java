package org.cn.liuwt.llmwiki.integration.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NASStorageProviderTest {

    private NASStorageProvider createProvider(Path tempDir) {
        NASStorageProvider provider = new NASStorageProvider();
        provider.setBasePath(tempDir.toString());
        provider.setLockEnabled(false);
        return provider;
    }

    private NASStorageProvider createProviderWithLock(Path tempDir) {
        NASStorageProvider provider = new NASStorageProvider();
        provider.setBasePath(tempDir.toString());
        provider.setLockEnabled(true);
        return provider;
    }

    @Test
    void shouldWriteAndReadFileWithoutLock(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        String scopeId = "test-scope";
        String path = "wiki/test.md";
        String content = "# Test\n\nNAS content";

        provider.write(scopeId, path, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(provider.exists(scopeId, path));

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldWriteAndReadFileWithLock(@TempDir Path tempDir) {
        NASStorageProvider provider = createProviderWithLock(tempDir);

        String scopeId = "test-scope";
        String path = "wiki/locked.md";
        String content = "# Locked\n\nWritten with FileLock";

        provider.write(scopeId, path, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(provider.exists(scopeId, path));

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldWriteStreamWithoutLock(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        String scopeId = "test-scope";
        String path = "raw/document.pdf";
        String content = "PDF binary content";

        provider.write(scopeId, path,
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            content.length());

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldWriteStreamWithLock(@TempDir Path tempDir) {
        NASStorageProvider provider = createProviderWithLock(tempDir);

        String scopeId = "test-scope";
        String path = "raw/locked-stream.pdf";
        String content = "Stream content with lock";

        provider.write(scopeId, path,
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            content.length());

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldDeleteFile(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.write("test-scope", "wiki/delete.md", "temp".getBytes(StandardCharsets.UTF_8));
        assertTrue(provider.exists("test-scope", "wiki/delete.md"));

        provider.delete("test-scope", "wiki/delete.md");
        assertFalse(provider.exists("test-scope", "wiki/delete.md"));
    }

    @Test
    void shouldReturnNullForNonExistentFile(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertNull(provider.read("test-scope", "wiki/nonexistent.md"));
    }

    @Test
    void shouldReturnNullStreamForNonExistentFile(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertNull(provider.readStream("test-scope", "wiki/nonexistent.md"));
    }

    @Test
    void shouldEnsureBucketWithHealthCheck(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.ensureBucket("new-scope");
        assertTrue(tempDir.resolve("new-scope").toFile().exists());
        assertFalse(tempDir.resolve("new-scope/.nas-health-check").toFile().exists());
    }

    @Test
    void shouldIsolateScopes(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.write("scope-a", "wiki/page.md", "A data".getBytes(StandardCharsets.UTF_8));
        provider.write("scope-b", "wiki/page.md", "B data".getBytes(StandardCharsets.UTF_8));

        assertEquals("A data", new String(provider.read("scope-a", "wiki/page.md"), StandardCharsets.UTF_8));
        assertEquals("B data", new String(provider.read("scope-b", "wiki/page.md"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectPathTraversalInPath(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.read("test-scope", "../../../etc/passwd"));
    }

    @Test
    void shouldRejectPathTraversalInScopeId(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.read("../../etc", "wiki/test.md"));
    }

    @Test
    void shouldRejectNullScopeId(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
            provider.read(null, "wiki/test.md"));
    }

    @Test
    void shouldRejectEmptyScopeId(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
            provider.read("", "wiki/test.md"));
    }

    @Test
    void shouldRejectEmptyPath(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(IllegalArgumentException.class, () ->
            provider.read("test-scope", ""));
    }

    @Test
    void shouldRejectSlashInScopeId(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.read("scope/other", "wiki/test.md"));
    }

    @Test
    void shouldRejectBackslashInScopeId(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.read("scope\\other", "wiki/test.md"));
    }

    @Test
    void shouldRejectDoubleDotInPath(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.write("test-scope", "wiki/../secret.md", "data".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectDoubleDotInEnsureBucket(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.ensureBucket("../../etc"));
    }

    @Test
    void shouldWriteOverExistingFile(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.write("test-scope", "wiki/page.md", "version 1".getBytes(StandardCharsets.UTF_8));
        provider.write("test-scope", "wiki/page.md", "version 2".getBytes(StandardCharsets.UTF_8));

        assertEquals("version 2", new String(provider.read("test-scope", "wiki/page.md"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldWriteOverExistingFileWithLock(@TempDir Path tempDir) {
        NASStorageProvider provider = createProviderWithLock(tempDir);

        provider.write("test-scope", "wiki/page.md", "version 1".getBytes(StandardCharsets.UTF_8));
        provider.write("test-scope", "wiki/page.md", "version 2".getBytes(StandardCharsets.UTF_8));

        assertEquals("version 2", new String(provider.read("test-scope", "wiki/page.md"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldGetUrl(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        String url = provider.getUrl("test-scope", "wiki/page.md");
        assertTrue(url.contains("test-scope"));
        assertTrue(url.contains("page.md"));
    }

    @Test
    void shouldDeleteNonExistentFileSilently(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.delete("test-scope", "wiki/nonexistent.md");
    }

    @Test
    void shouldReportScopeDirectoryExists(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertFalse(provider.scopeDirectoryExists("scope-x"));
        provider.ensureBucket("scope-x");
        assertTrue(provider.scopeDirectoryExists("scope-x"));
    }

    @Test
    void shouldMoveScopeDirectory(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        provider.ensureBucket("old-scope");
        provider.write("old-scope", "wiki/page.md", "moved".getBytes(StandardCharsets.UTF_8));

        provider.moveScopeDirectory("old-scope", "new-scope");

        assertFalse(provider.scopeDirectoryExists("old-scope"));
        assertTrue(provider.scopeDirectoryExists("new-scope"));
        assertEquals("moved", new String(provider.read("new-scope", "wiki/page.md"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectTraversalInMoveScopeDirectory(@TempDir Path tempDir) {
        NASStorageProvider provider = createProvider(tempDir);

        assertThrows(SecurityException.class, () ->
            provider.moveScopeDirectory("../../etc", "new-scope"));
    }
}