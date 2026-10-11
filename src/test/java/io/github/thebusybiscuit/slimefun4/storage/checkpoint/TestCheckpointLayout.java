package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the on-disk shape of a checkpoint directory.
 *
 * <p>The two halves live in their own subdirectories, a skipped half has no file at all, and a
 * checkpoint whose recorded half is missing from disk is never offered as a restore source.
 */
class TestCheckpointLayout {

    @TempDir
    Path workspace;

    private File directory;

    @BeforeEach
    void setUp() {
        directory = workspace.resolve(CheckpointService.formatId(3)).toFile();
    }

    @Test
    @DisplayName("Each half resolves inside its own subdirectory")
    void layoutIsFixedForEveryProvider() throws IOException {
        File storage = writeHalf(Checkpoint.STORAGE_DIRECTORY, Checkpoint.STORAGE_FILE, "zip");
        File database = writeHalf(Checkpoint.DATABASE_DIRECTORY, databaseName(), "{}");

        Checkpoint checkpoint = new Checkpoint(directory, metadata(storageHalf(), databaseHalf()));

        assertEquals("checkpoint-003", checkpoint.id());
        assertEquals(3, checkpoint.sequence());
        assertEquals(storage, checkpoint.storageFile());
        assertEquals(database, checkpoint.databaseFile());
        assertEquals(new File(directory, Checkpoint.METADATA_FILE), checkpoint.metadataFile());
        assertEquals(Checkpoint.STORAGE_DIRECTORY, checkpoint.storageFile().getParentFile().getName());
        assertEquals(Checkpoint.DATABASE_DIRECTORY, checkpoint.databaseFile().getParentFile().getName());
        assertTrue(checkpoint.isRestorable());
        assertEquals(6144L, checkpoint.totalBytes(), "Both recorded sizes are reported together");
    }

    @Test
    @DisplayName("A skipped half has no file and does not block a restore")
    void aSkippedHalfHasNoFile() throws IOException {
        writeHalf(Checkpoint.STORAGE_DIRECTORY, Checkpoint.STORAGE_FILE, "zip");

        Checkpoint checkpoint = new Checkpoint(directory,
            metadata(storageHalf(), CheckpointComponent.skipped(BackupFormat.SQL_JSON, "No rows")));

        assertNotNull(checkpoint.storageFile());
        assertNull(checkpoint.databaseFile(), "A skipped half never names a file");
        assertTrue(checkpoint.isRestorable(RestoreScope.STORAGE_ONLY));
        assertFalse(checkpoint.isRestorable(RestoreScope.DATABASE_ONLY));
        assertTrue(checkpoint.isRestorable(), "A combined restore accepts a legitimately skipped half");
        assertEquals(4096L, checkpoint.totalBytes());
    }

    @Test
    @DisplayName("A recorded half that is missing from disk makes the checkpoint unrestorable")
    void aMissingFileIsNotRestorable() throws IOException {
        Checkpoint checkpoint = new Checkpoint(directory, metadata(storageHalf(), databaseHalf()));

        assertFalse(checkpoint.isRestorable(), "Neither half exists yet");

        File storage = writeHalf(Checkpoint.STORAGE_DIRECTORY, Checkpoint.STORAGE_FILE, "zip");

        assertFalse(checkpoint.isRestorable(), "The database half is still missing");
        assertTrue(checkpoint.isRestorable(RestoreScope.STORAGE_ONLY));
        assertFalse(checkpoint.isRestorable(RestoreScope.DATABASE_ONLY));

        File database = writeHalf(Checkpoint.DATABASE_DIRECTORY, databaseName(), "{}");

        assertTrue(checkpoint.isRestorable());
        assertTrue(storage.delete());
        assertFalse(checkpoint.isRestorable(), "Losing the storage half must invalidate the checkpoint");
        assertFalse(checkpoint.isRestorable(RestoreScope.STORAGE_ONLY));
        assertTrue(checkpoint.isRestorable(RestoreScope.DATABASE_ONLY));
        assertTrue(database.isFile(), "The other half is left alone on disk");
    }

    @Test
    @DisplayName("A failed half is unrestorable even when both files are on disk")
    void aFailedHalfIsNeverRestorable() throws IOException {
        writeHalf(Checkpoint.STORAGE_DIRECTORY, Checkpoint.STORAGE_FILE, "zip");
        writeHalf(Checkpoint.DATABASE_DIRECTORY, databaseName(), "{}");

        Checkpoint checkpoint = new Checkpoint(directory,
            metadata(storageHalf(), CheckpointComponent.failed(BackupFormat.SQL_JSON, "Connection refused")));

        assertFalse(checkpoint.metadata().isValid());
        assertFalse(checkpoint.isRestorable(), "Files on disk cannot rescue a combined restore");
        assertTrue(checkpoint.isRestorable(RestoreScope.STORAGE_ONLY));
        assertFalse(checkpoint.isRestorable(RestoreScope.DATABASE_ONLY));
    }

    private File writeHalf(String subdirectory, String fileName, String content) throws IOException {
        Path target = directory.toPath().resolve(subdirectory).resolve(fileName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);

        return target.toFile();
    }

    private static String databaseName() {
        return Checkpoint.DATABASE_FILE_BASE + ".json";
    }

    private static CheckpointComponent storageHalf() {
        return new CheckpointComponent(Checkpoint.STORAGE_FILE, BackupFormat.LEGACY_FILES, "aa11",
            4096L, Map.of("Players", 2, "stored-blocks", 5), CheckpointStatus.OK, "7 files");
    }

    private static CheckpointComponent databaseHalf() {
        return new CheckpointComponent(databaseName(), BackupFormat.SQL_JSON, "bb22",
            2048L, Map.of("player_researches", 4), CheckpointStatus.OK, "4 rows");
    }

    private static CheckpointMetadata metadata(CheckpointComponent storage, CheckpointComponent database) {
        return new CheckpointMetadata(
            CheckpointMetadata.CURRENT_FORMAT_VERSION,
            CheckpointService.formatId(3),
            3,
            Instant.parse("2026-09-05T16:40:00Z"),
            "4.0-UNOFFICIAL",
            "Paper 26.2",
            "SQLite",
            "SQLITE",
            true,
            storage,
            database);
    }
}
