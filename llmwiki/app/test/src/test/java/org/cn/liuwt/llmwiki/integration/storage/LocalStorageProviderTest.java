package org.cn.liuwt.llmwiki.integration.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageProviderTest {

    @Test
    void shouldWriteAndReadFile(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        String scopeId = "test-scope";
        String path = "wiki/test.md";
        String content = "# Test\n\nHello World";

        provider.write(scopeId, path, content.getBytes(StandardCharsets.UTF_8));

        assertTrue(provider.exists(scopeId, path));

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldWriteAndReadStream(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        String scopeId = "test-scope";
        String path = "raw/test.pdf";
        String content = "PDF content here";

        provider.write(scopeId, path,
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
            content.length());

        assertTrue(provider.exists(scopeId, path));

        byte[] read = provider.read(scopeId, path);
        assertNotNull(read);
        assertEquals(content, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    void shouldDeleteFile(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        String scopeId = "test-scope";
        String path = "wiki/delete.md";

        provider.write(scopeId, path, "delete me".getBytes(StandardCharsets.UTF_8));
        assertTrue(provider.exists(scopeId, path));

        provider.delete(scopeId, path);
        assertFalse(provider.exists(scopeId, path));
    }

    @Test
    void shouldReturnNullForNonExistentFile(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        byte[] read = provider.read("test-scope", "wiki/nonexistent.md");
        assertNull(read);
    }

    @Test
    void shouldEnsureBucket(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        String scopeId = "new-scope";
        provider.ensureBucket(scopeId);

        assertTrue(tempDir.resolve(scopeId).toFile().exists());
    }

    @Test
    void shouldIsolateScopes(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        provider.write("scope-a", "wiki/page.md", "scope A".getBytes(StandardCharsets.UTF_8));
        provider.write("scope-b", "wiki/page.md", "scope B".getBytes(StandardCharsets.UTF_8));

        byte[] readA = provider.read("scope-a", "wiki/page.md");
        byte[] readB = provider.read("scope-b", "wiki/page.md");

        assertEquals("scope A", new String(readA, StandardCharsets.UTF_8));
        assertEquals("scope B", new String(readB, StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectPathTraversalInPath(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        assertThrows(SecurityException.class, () ->
            provider.read("test-scope", "../../../etc/passwd"));
    }

    @Test
    void shouldRejectPathTraversalInScopeId(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        assertThrows(SecurityException.class, () ->
            provider.read("../../etc", "wiki/test.md"));
    }

    @Test
    void shouldRejectNullScopeId(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () ->
            provider.read(null, "wiki/test.md"));
    }

    @Test
    void shouldRejectEmptyPath(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () ->
            provider.read("test-scope", ""));
    }

    @Test
    void shouldRejectSlashInScopeId(@TempDir Path tempDir) {
        LocalStorageProvider provider = new LocalStorageProvider();
        provider.setBasePath(tempDir.toString());

        assertThrows(SecurityException.class, () ->
            provider.read("scope/other", "wiki/test.md"));
    }
}