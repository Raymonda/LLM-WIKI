package org.cn.liuwt.llmwiki.domain.service.harness;

import org.cn.liuwt.llmwiki.integration.storage.LocalStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpillServiceTest {

    @TempDir
    Path tempDir;

    private SpillService spillService;

    @BeforeEach
    void setUp() {
        LocalStorageProvider storageProvider = new LocalStorageProvider();
        storageProvider.setBasePath(tempDir.toString());
        SpillProperties properties = new SpillProperties();
        properties.setMaxInlineBytes(64);
        spillService = new SpillService(storageProvider, properties);
    }

    @Test
    void spillIfNeeded_smallContent_staysInline() {
        SpillService.SpillResult result = spillService.spillIfNeeded(1L, "exec-1", "short content");

        assertFalse(result.spilled());
        assertEquals("short content", result.content());
        assertNull(result.spillId());
    }

    @Test
    void spillIfNeeded_nullAndEmpty_stayInline() {
        assertNull(spillService.spillIfNeeded(1L, "exec-1", null).content());
        assertFalse(spillService.spillIfNeeded(1L, "exec-1", "").spilled());
    }

    @Test
    void spillIfNeeded_largeContent_replacedWithLocator() {
        String large = "x".repeat(200);

        SpillService.SpillResult result = spillService.spillIfNeeded(1L, "exec-1", large);

        assertTrue(result.spilled());
        assertNotNull(result.spillId());
        assertTrue(result.content().contains("SPILL"));
        assertFalse(result.content().contains("xxxx"));
    }

    @Test
    void readSpill_roundTripsOriginalContent() {
        String large = "y".repeat(200);
        SpillService.SpillResult result = spillService.spillIfNeeded(1L, "exec-1", large);

        assertEquals(large, spillService.readSpill(1L, "exec-1", result.spillId()));
    }

    @Test
    void readSpill_wrongExecutionId_notFound() {
        String large = "a".repeat(200);
        SpillService.SpillResult result = spillService.spillIfNeeded(1L, "exec-A", large);

        assertThrows(Exception.class, () -> spillService.readSpill(1L, "exec-B", result.spillId()));
    }

    @Test
    void deleteSpills_removesSpilledFiles() {
        String large = "b".repeat(200);
        SpillService.SpillResult result = spillService.spillIfNeeded(2L, "exec-2", large);

        spillService.deleteSpills(2L, "exec-2", List.of(result.spillId()));

        assertThrows(Exception.class, () -> spillService.readSpill(2L, "exec-2", result.spillId()));
    }

    @Test
    void deleteSpills_unknownOrNullIds_noError() {
        assertDoesNotThrow(() -> spillService.deleteSpills(2L, "exec-2", List.of("deadbeef")));
        assertDoesNotThrow(() -> spillService.deleteSpills(2L, "exec-2", null));
        assertDoesNotThrow(() -> spillService.deleteSpills(2L, "exec-2", Arrays.asList(" ", null)));
    }

    @Test
    void spill_isIsolatedByScope() {
        String large = "c".repeat(200);
        SpillService.SpillResult result = spillService.spillIfNeeded(3L, "exec-3", large);

        assertThrows(Exception.class, () -> spillService.readSpill(4L, "exec-3", result.spillId()));
    }
}
