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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real backup and restore against a throwaway SQLite database.
 *
 * <p>One round trip exercises identifier quoting, typed parameter binding, the JSON
 * escape and unescape pair and the string aware brace scanners at once: the seeded row
 * set holds a binary column and a name containing a quote, a newline, a closing brace
 * and a closing bracket.
 */
class TestBackupRoundTrip {

    @TempDir
    Path workspace;

    private File backupDirectory;
    private FakeStorageProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        backupDirectory = workspace.resolve("backups").toFile();
        provider = FakeStorageProvider.sqlite(workspace.resolve("slimefun.db").toFile());

        try (Connection connection = provider.connect()) {
            SqliteFixture.createSchema(connection);
            SqliteFixture.seed(connection);
        }
    }

    @Test
    @DisplayName("A plain JSON dump restores every value unchanged")
    void plainDumpRoundTrip() throws IOException, SQLException {
        assertRoundTrip(false);
    }

    @Test
    @DisplayName("A compressed dump restores every value unchanged")
    void compressedDumpRoundTrip() throws IOException, SQLException {
        assertRoundTrip(true);
    }

    @Test
    @DisplayName("A tampered dump is refused before anything is deleted")
    void tamperedDumpIsRefusedBeforeDeleting() throws IOException, SQLException {
        BackupEntry entry = DatabaseBackupService.createBackup(provider, backupDirectory, false, 0, false);
        Files.writeString(entry.dataFile().toPath(), "{\"tables\": {}}", StandardCharsets.UTF_8);

        DatabaseRestoreService.RestoreResult result = DatabaseRestoreService.restore(entry, provider);

        assertFalse(result.success());
        assertTrue(result.message().contains("Checksum mismatch"), "Unexpected message: " + result.message());

        try (Connection connection = provider.connect()) {
            assertEquals(SqliteFixture.SEEDED_ROWS, SqliteFixture.countRows(connection),
                "A refused restore must not touch the live rows");
        }
    }

    @Test
    @DisplayName("A file archive is never restored into a SQL provider")
    void fileArchiveIsRefusedBySqlProvider() throws IOException {
        BackupEntry entry = fakeEntry(BackupFormat.LEGACY_FILES, Map.of("Players", 1));

        DatabaseRestoreService.RestoreResult result = DatabaseRestoreService.restore(entry, provider);

        assertFalse(result.success());
        assertTrue(result.message().contains("file archive"), "Unexpected message: " + result.message());
    }

    @Test
    @DisplayName("A SQL dump is never restored into a file backend")
    void sqlDumpIsRefusedByFileBackend() throws IOException {
        BackupEntry entry = DatabaseBackupService.createBackup(provider, backupDirectory, false, 0, false);

        DatabaseRestoreService.RestoreResult result =
            DatabaseRestoreService.restore(entry, FakeStorageProvider.legacy());

        assertFalse(result.success());
        assertTrue(result.message().contains("SQL dump"), "Unexpected message: " + result.message());
    }

    @Test
    @DisplayName("A backup that recorded no data is never restored")
    void emptyBackupIsRefused() throws IOException {
        BackupEntry entry = fakeEntry(BackupFormat.SQL_JSON, Map.of());

        DatabaseRestoreService.RestoreResult result = DatabaseRestoreService.restore(entry, provider);

        assertFalse(result.success());
        assertTrue(result.message().contains("recorded no data"), "Unexpected message: " + result.message());
    }

    private void assertRoundTrip(boolean compress) throws IOException, SQLException {
        BackupEntry entry = DatabaseBackupService.createBackup(provider, backupDirectory, compress, 0, false);

        assertEquals(compress, entry.dataFile().getName().endsWith(".zip"));
        assertEquals(SqliteFixture.SEEDED_ROWS, entry.metadata().totalRows());

        try (Connection connection = provider.connect()) {
            SqliteFixture.wipe(connection);
            assertEquals(0, SqliteFixture.countRows(connection));
        }

        DatabaseRestoreService.RestoreResult result = DatabaseRestoreService.restore(entry, provider);

        assertTrue(result.success(), result.message());
        assertEquals(5, result.tablesRestored());
        assertEquals(SqliteFixture.SEEDED_ROWS, result.rowsRestored());

        try (Connection connection = provider.connect()) {
            assertEquals(SqliteFixture.SEEDED_ROWS, SqliteFixture.countRows(connection));
            assertRestoredWaypoint(connection);
            assertRestoredBackpack(connection);
        }
    }

    private void assertRestoredWaypoint(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, world, x, y, z, yaw, pitch FROM player_waypoints WHERE uuid = ? AND waypoint_id = ?")) {
            ps.setString(1, SqliteFixture.PLAYER_UUID);
            ps.setString(2, SqliteFixture.WAYPOINT_ID);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "The waypoint row is missing after the restore");
                assertEquals(SqliteFixture.TRICKY_NAME, rs.getString("name"),
                    "Quotes, newlines and braces inside a value must survive the round trip");
                assertEquals("world", rs.getString("world"));
                assertEquals(SqliteFixture.X, rs.getDouble("x"));
                assertEquals(SqliteFixture.Y, rs.getDouble("y"));
                assertEquals(SqliteFixture.Z, rs.getDouble("z"));
                assertEquals(SqliteFixture.YAW, rs.getFloat("yaw"));
                assertEquals(SqliteFixture.PITCH, rs.getFloat("pitch"));
            }
        }
    }

    private void assertRestoredBackpack(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot, item_data FROM player_backpack_items WHERE uuid = ? ORDER BY slot")) {
            ps.setString(1, SqliteFixture.PLAYER_UUID);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "The backpack item row is missing after the restore");
                assertEquals(0, rs.getInt("slot"));
                assertArrayEquals(SqliteFixture.blobPayload(), rs.getBytes("item_data"),
                    "Binary item data must be restored byte for byte");

                assertTrue(rs.next(), "The empty backpack slot is missing after the restore");
                assertEquals(1, rs.getInt("slot"));
                assertNull(rs.getBytes("item_data"), "A null column must stay null");
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT size FROM player_backpacks WHERE uuid = ? AND backpack_id = ?")) {
            ps.setString(1, SqliteFixture.PLAYER_UUID);
            ps.setInt(2, 1);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(27, rs.getInt("size"));
            }
        }
    }

    private BackupEntry fakeEntry(BackupFormat format, Map<String, Integer> counts) throws IOException {
        Files.createDirectories(backupDirectory.toPath());
        File dataFile = new File(backupDirectory, "fake" + (format == BackupFormat.LEGACY_FILES ? ".zip" : ".json"));
        Files.writeString(dataFile.toPath(), "{\"tables\": {}}", StandardCharsets.UTF_8);

        BackupMetadata metadata = new BackupMetadata(
            "fake",
            "SQLite",
            Instant.now(),
            counts,
            DatabaseBackupService.sha256Hex(dataFile),
            "test",
            false,
            format);

        return new BackupEntry(dataFile, metadata);
    }
}
