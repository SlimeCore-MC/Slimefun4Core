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
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two failure modes that were destroying backup history on a live server:
 * a backup that cannot read its source being written anyway, and retention purging a
 * real archive right after such a run.
 */
class TestDatabaseBackupService {

    @TempDir
    Path workspace;

    private File backupDirectory;
    private File databaseFile;
    private FakeStorageProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        backupDirectory = workspace.resolve("backups").toFile();
        databaseFile = workspace.resolve("slimefun.db").toFile();
        provider = FakeStorageProvider.sqlite(databaseFile);

        try (Connection connection = provider.connect()) {
            SqliteFixture.createSchema(connection);
            SqliteFixture.seed(connection);
        }
    }

    @Test
    @DisplayName("A backup that cannot read its source writes no file and purges nothing")
    void unreadableSourceKeepsHistoryIntact() throws IOException, InterruptedException {
        List<String> existing = createBackups(3);

        int filesBefore = listFiles().size();
        FakeStorageProvider unreachable = FakeStorageProvider.unreachableSql();

        assertThrows(IOException.class, () -> DatabaseBackupService.createBackup(
            unreachable, backupDirectory, false, 1, true));

        assertEquals(filesBefore, listFiles().size(), "A failed backup must not add or remove files");

        List<String> remaining = ids(DatabaseBackupService.listBackups(backupDirectory));
        assertEquals(3, remaining.size(), "A failed backup must not purge older backups");
        assertTrue(remaining.containsAll(existing), "Every pre-existing backup must survive");
    }

    @Test
    @DisplayName("An empty database produces no backup at all")
    void emptyDatabaseProducesNoBackup() throws SQLException {
        try (Connection connection = provider.connect()) {
            SqliteFixture.wipe(connection);
        }

        assertThrows(IOException.class, () -> DatabaseBackupService.createBackup(
            provider, backupDirectory, false, 5, true));

        assertTrue(listFiles().isEmpty(), "An empty dump must never become a file on disk");
        assertTrue(DatabaseBackupService.listBackups(backupDirectory).isEmpty());
    }

    @Test
    @DisplayName("Retention keeps the newest backups and deletes the oldest by creation time")
    void retentionDeletesOldestFirst() throws IOException, InterruptedException {
        List<String> created = createBackups(4);

        DatabaseBackupService.createBackup(provider, backupDirectory, false, 2, true);
        List<BackupEntry> remaining = DatabaseBackupService.listBackups(backupDirectory);

        assertEquals(2, remaining.size(), "keep-last must be honoured");

        List<String> ids = ids(remaining);
        assertTrue(ids.contains(created.get(3)), "The newest pre-existing backup must survive");
        assertFalse(ids.contains(created.get(0)), "The oldest backup must be purged first");
        assertFalse(ids.contains(created.get(1)));

        for (BackupEntry entry : remaining) {
            assertTrue(entry.exists(), "A retained backup must still have its data file");
            assertTrue(DatabaseBackupService.verifyBackup(entry), "A retained backup must still verify");
        }

        assertEquals(4, listFiles().size(), "Purging must remove the metadata sidecar as well");
    }

    @Test
    @DisplayName("Retention never purges every backup away")
    void retentionNeverEmptiesTheDirectory() throws IOException, InterruptedException {
        createBackups(3);

        BackupEntry latest = DatabaseBackupService.createBackup(provider, backupDirectory, false, 1, true);
        List<BackupEntry> remaining = DatabaseBackupService.listBackups(backupDirectory);

        assertEquals(1, remaining.size());
        assertEquals(latest.metadata().backupId(), remaining.get(0).metadata().backupId());
        assertTrue(DatabaseBackupService.verifyBackup(remaining.get(0)));
    }

    @Test
    @DisplayName("A tampered archive fails verification")
    void tamperedArchiveFailsVerification() throws IOException {
        BackupEntry entry = DatabaseBackupService.createBackup(provider, backupDirectory, false, 0, false);
        assertTrue(DatabaseBackupService.verifyBackup(entry));

        Files.writeString(entry.dataFile().toPath(), "{\"tables\": {}}", StandardCharsets.UTF_8);

        BackupEntry reloaded = DatabaseBackupService.listBackups(backupDirectory).get(0);
        assertFalse(DatabaseBackupService.verifyBackup(reloaded), "A rewritten data file must not verify");
    }

    @Test
    @DisplayName("A backup records the real row count of every managed table")
    void metadataRecordsRealRowCounts() throws IOException {
        BackupEntry entry = DatabaseBackupService.createBackup(provider, backupDirectory, false, 0, false);

        assertEquals(BackupFormat.SQL_JSON, entry.metadata().format());
        assertEquals(SqliteFixture.SEEDED_ROWS, entry.metadata().totalRows());
        assertFalse(entry.metadata().isEmpty());
        assertEquals(5, entry.metadata().rowCounts().size(), "Every managed table must be listed");
        assertTrue(entry.sizeBytes() > 0L);
    }

    private List<String> createBackups(int amount) throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>(amount);

        for (int i = 0; i < amount; i++) {
            ids.add(DatabaseBackupService.createBackup(
                provider, backupDirectory, false, 0, true).metadata().backupId());
            Thread.sleep(5L);
        }

        return ids;
    }

    private List<String> ids(List<BackupEntry> entries) {
        List<String> ids = new ArrayList<>(entries.size());

        for (BackupEntry entry : entries) {
            ids.add(entry.metadata().backupId());
        }

        return ids;
    }

    private List<File> listFiles() {
        File[] files = backupDirectory.listFiles();
        return files == null ? List.of() : List.of(files);
    }
}
