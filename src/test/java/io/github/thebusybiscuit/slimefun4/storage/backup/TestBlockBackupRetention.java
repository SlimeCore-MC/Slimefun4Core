package io.github.thebusybiscuit.slimefun4.storage.backup;

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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins retention for the block archive, which is the largest thing this service writes.
 *
 * <p>Two properties keep an operator's history safe. A block archive lives in its own
 * retention bucket, so a run of row dumps can never purge it, and the file cap applies to
 * it as well, because a tree of stored inventories is far larger than a row dump.
 *
 * <p>Retention is driven directly rather than through {@code createBackup}, because the
 * block capture reads a relative data root and a test must not write into the working
 * directory to exercise a purge.
 */
class TestBlockBackupRetention {

    private static final Instant EPOCH = Instant.parse("2026-09-04T00:00:00Z");

    @TempDir
    Path workspace;

    private File directory;

    @BeforeEach
    void setUp() throws IOException {
        directory = workspace.resolve("backups").toFile();
        Files.createDirectories(directory.toPath());
    }

    @Test
    @DisplayName("A block archive is its own bucket, untouched by a row dump purge")
    void blockArchivesAreTheirOwnBucket() throws IOException {
        List<String> blocks = seed(BackupFormat.BLOCK_FILES, 3, 0);
        List<String> dumps = seed(BackupFormat.SQL_JSON, 4, 100);

        DatabaseBackupService.purgeOldBackups(directory, 1, BackupFormat.SQL_JSON);

        assertEquals(3, idsOf(BackupFormat.BLOCK_FILES).size(),
            "Purging row dumps must never touch a block archive");
        assertTrue(idsOf(BackupFormat.BLOCK_FILES).containsAll(blocks));
        assertEquals(1, idsOf(BackupFormat.SQL_JSON).size());
        assertTrue(idsOf(BackupFormat.SQL_JSON).contains(dumps.get(3)), "The newest row dump survives");
    }

    @Test
    @DisplayName("A block purge keeps the newest and deletes the oldest, sidecar included")
    void blockRetentionDeletesOldestFirst() throws IOException {
        List<String> blocks = seed(BackupFormat.BLOCK_FILES, 4, 0);
        seed(BackupFormat.LEGACY_FILES, 2, 50);

        DatabaseBackupService.purgeOldBackups(directory, 2, BackupFormat.BLOCK_FILES);

        List<String> remaining = idsOf(BackupFormat.BLOCK_FILES);

        assertEquals(2, remaining.size());
        assertTrue(remaining.contains(blocks.get(3)), "The newest block archive must survive");
        assertTrue(remaining.contains(blocks.get(2)));
        assertFalse(remaining.contains(blocks.get(0)), "The oldest block archive must go first");

        assertFalse(new File(directory, blocks.get(0) + ".zip").exists());
        assertFalse(new File(directory, blocks.get(0) + ".meta.json").exists(),
            "The metadata sidecar must be removed with the archive");

        assertEquals(2, idsOf(BackupFormat.LEGACY_FILES).size(),
            "A file archive is a separate bucket again");
    }

    @Test
    @DisplayName("The file archive cap applies to block archives, since they are the largest")
    void blockRetentionHonoursTheFileCap() throws IOException {
        seed(BackupFormat.BLOCK_FILES, 8, 0);

        DatabaseBackupService.purgeOldBackups(directory, 50, BackupFormat.BLOCK_FILES);

        assertEquals(5, idsOf(BackupFormat.BLOCK_FILES).size(),
            "A block archive is capped like a file archive, not like a row dump");
    }

    @Test
    @DisplayName("A row dump is not capped, so a large keep-last is honoured")
    void rowDumpsAreNotCapped() throws IOException {
        seed(BackupFormat.SQL_JSON, 8, 0);

        DatabaseBackupService.purgeOldBackups(directory, 50, BackupFormat.SQL_JSON);

        assertEquals(8, idsOf(BackupFormat.SQL_JSON).size());
    }

    @Test
    @DisplayName("Retention never purges the last block archive away")
    void retentionNeverEmptiesTheBucket() throws IOException {
        List<String> blocks = seed(BackupFormat.BLOCK_FILES, 3, 0);

        DatabaseBackupService.purgeOldBackups(directory, 1, BackupFormat.BLOCK_FILES);

        List<String> remaining = idsOf(BackupFormat.BLOCK_FILES);

        assertEquals(1, remaining.size());
        assertEquals(blocks.get(2), remaining.get(0), "The survivor is the newest one");
    }

    @Test
    @DisplayName("A keep-last of zero disables retention, so a pre-migration archive is never pruned")
    void keepLastZeroDisablesRetention() throws IOException {
        seed(BackupFormat.BLOCK_FILES, 9, 0);

        DatabaseBackupService.purgeOldBackups(directory, 0, BackupFormat.BLOCK_FILES);

        assertEquals(9, idsOf(BackupFormat.BLOCK_FILES).size(),
            "The backups taken before a migration must survive every later purge");
    }

    @Test
    @DisplayName("An archive that recorded nothing is never counted as a healthy copy")
    void anEmptyArchiveIsNotHealthy() throws IOException {
        List<String> blocks = seed(BackupFormat.BLOCK_FILES, 2, 0);
        String hollow = writeBackup(BackupFormat.BLOCK_FILES, EPOCH.plusSeconds(900), 0, true);

        DatabaseBackupService.purgeOldBackups(directory, 1, BackupFormat.BLOCK_FILES);

        List<String> remaining = idsOf(BackupFormat.BLOCK_FILES);

        assertTrue(remaining.contains(hollow),
            "An empty archive is skipped by retention rather than counted and deleted");
        assertTrue(remaining.contains(blocks.get(1)), "The newest healthy archive survives");
        assertFalse(remaining.contains(blocks.get(0)));
    }

    @Test
    @DisplayName("An archive whose data file is gone is skipped, not counted as retained")
    void aMissingDataFileIsNotHealthy() throws IOException {
        List<String> blocks = seed(BackupFormat.BLOCK_FILES, 3, 0);

        assertTrue(new File(directory, blocks.get(2) + ".zip").delete());

        DatabaseBackupService.purgeOldBackups(directory, 1, BackupFormat.BLOCK_FILES);

        List<String> remaining = idsOf(BackupFormat.BLOCK_FILES);

        assertEquals(2, remaining.size(), "The orphaned sidecar is left alone");
        assertTrue(remaining.contains(blocks.get(1)), "The newest archive that still has a file survives");
        assertFalse(remaining.contains(blocks.get(0)));
    }

    @Test
    @DisplayName("Every format the service writes is a distinct bucket")
    void everyFormatIsADistinctBucket() throws IOException {
        seed(BackupFormat.SQL_JSON, 3, 0);
        seed(BackupFormat.LEGACY_FILES, 3, 100);
        seed(BackupFormat.BLOCK_FILES, 3, 200);
        seed(BackupFormat.PLAYER_FILES, 3, 300);

        for (BackupFormat format : BackupFormat.values()) {
            DatabaseBackupService.purgeOldBackups(directory, 1, format);
        }

        for (BackupFormat format : BackupFormat.values()) {
            assertEquals(1, idsOf(format).size(), format.getDisplayName() + " lost its own bucket");
        }
    }

    private List<String> seed(BackupFormat format, int amount, int offsetSeconds) throws IOException {
        List<String> ids = new ArrayList<>(amount);

        for (int i = 0; i < amount; i++) {
            ids.add(writeBackup(format, EPOCH.plusSeconds(offsetSeconds + i), 12 + i, false));
        }

        return ids;
    }

    private String writeBackup(BackupFormat format, Instant createdAt, int entries, boolean empty) throws IOException {
        String backupId = format.name().toLowerCase(java.util.Locale.ROOT) + '-' + createdAt.getEpochSecond();
        File dataFile = new File(directory, backupId + ".zip");

        Files.writeString(dataFile.toPath(), "archive-" + backupId, StandardCharsets.UTF_8);

        String rowCounts = empty ? "" : "\n    \"stored-blocks\": " + entries + "\n  ";
        String json = "{\n"
            + "  \"backupId\": \"" + backupId + "\",\n"
            + "  \"provider\": \"SQLite\",\n"
            + "  \"createdAt\": \"" + createdAt + "\",\n"
            + "  \"slimefunVersion\": \"test\",\n"
            + "  \"checksum\": \"" + DatabaseBackupService.sha256Hex(dataFile) + "\",\n"
            + "  \"format\": \"" + format.name() + "\",\n"
            + "  \"automatic\": false,\n"
            + "  \"rowCounts\": {" + rowCounts + "}\n"
            + "}";

        Files.writeString(new File(directory, backupId + ".meta.json").toPath(), json, StandardCharsets.UTF_8);

        return backupId;
    }

    private List<String> idsOf(BackupFormat format) {
        List<String> ids = new ArrayList<>();

        for (BackupEntry entry : DatabaseBackupService.listBackups(directory)) {
            if (entry.metadata().format() == format) {
                ids.add(entry.metadata().backupId());
            }
        }

        return ids;
    }
}
