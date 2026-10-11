package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.backup.FakeStorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.SqliteFixture;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the enumeration primitive the migrator was missing.
 *
 * <p>{@code Storage} cannot list players, which is why the migrator had no caller. Both
 * halves are pinned here, including the rule that a name which is not a uuid is reported
 * rather than silently skipped.
 */
class TestPlayerIndex {

    private static final String ALPHA = "3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30";
    private static final String BETA = "8c1d4f60-2a33-4e17-b0aa-91f4d7e6c218";
    private static final String GAMMA = "0b2e9a17-77c5-4d02-8e6b-1a3c5f8d4e99";

    @TempDir
    Path workspace;

    private File root;

    @BeforeEach
    void setUp() {
        root = workspace.resolve("Slimefun").toFile();
    }

    @Test
    @DisplayName("A legacy tree is enumerated from both player sections, without duplicates")
    void legacyEnumerationUnionsBothSections() throws IOException {
        write("Players/" + ALPHA + ".yml", "researches: [1]");
        write("Players/" + BETA + ".yml", "researches: [2]");
        write("waypoints/" + ALPHA + ".yml", "0: world;1.0;2.0;3.0");
        write("waypoints/" + GAMMA + ".yml", "0: world;4.0;5.0;6.0");

        PlayerIndex.Index index = PlayerIndex.fromLegacy(root);

        assertEquals(3, index.size(), "A player present in both sections must be counted once");
        assertTrue(index.unreadable().isEmpty());
        assertTrue(index.uuids().contains(UUID.fromString(ALPHA)));
        assertTrue(index.uuids().contains(UUID.fromString(BETA)));
        assertTrue(index.uuids().contains(UUID.fromString(GAMMA)),
            "A player with only waypoints must still be migrated");
    }

    @Test
    @DisplayName("Enumeration order is deterministic, so progress reporting is reproducible")
    void legacyEnumerationIsSorted() throws IOException {
        write("Players/" + BETA + ".yml", "researches: [1]");
        write("Players/" + GAMMA + ".yml", "researches: [2]");
        write("Players/" + ALPHA + ".yml", "researches: [3]");

        List<UUID> uuids = PlayerIndex.fromLegacy(root).uuids();
        List<String> asText = new ArrayList<>(uuids.size());

        for (UUID uuid : uuids) {
            asText.add(uuid.toString());
        }

        List<String> expected = new ArrayList<>(asText);
        expected.sort(String::compareTo);

        assertEquals(expected, asText, "Enumeration must be sorted by uuid text");
    }

    @Test
    @DisplayName("A filename that is not a uuid is reported, never silently ignored")
    void nonUuidFilenamesAreReported() throws IOException {
        write("Players/" + ALPHA + ".yml", "researches: [1]");
        write("Players/notaplayer.yml", "researches: [1]");
        write("waypoints/also-not-a-uuid.yml", "0: world;1.0;2.0;3.0");
        write("Players/README.txt", "ignored, not a yml file");

        PlayerIndex.Index index = PlayerIndex.fromLegacy(root);

        assertEquals(1, index.size(), "Only the real uuid may be migrated");
        assertEquals(2, index.unreadable().size(), "Both bad names must be reported");
        assertTrue(index.unreadable().contains("Players/notaplayer.yml"));
        assertTrue(index.unreadable().contains("waypoints/also-not-a-uuid.yml"));
        assertFalse(index.unreadable().contains("Players/README.txt"),
            "A file that is not a player file at all is not a problem to report");
    }

    @Test
    @DisplayName("A missing legacy tree enumerates nothing instead of failing")
    void missingLegacyTreeIsEmpty() {
        PlayerIndex.Index index = PlayerIndex.fromLegacy(new File(root, "does-not-exist"));

        assertEquals(0, index.size());
        assertTrue(index.unreadable().isEmpty());
    }

    @Test
    @DisplayName("A SQL backend is enumerated across every player table")
    void sqlEnumerationUnionsEveryTable() throws SQLException {
        FakeStorageProvider provider = FakeStorageProvider.sqlite(workspace.resolve("slimefun.db").toFile());

        try (Connection connection = provider.connect()) {
            SqliteFixture.createSchema(connection);
            SqliteFixture.seed(connection);

            insertResearch(connection, BETA, 7);
            insertWaypointOnly(connection, GAMMA);

            PlayerIndex.Index index = PlayerIndex.fromSql(connection, StorageProviderType.SQLITE);

            assertEquals(3, index.size());
            assertTrue(index.unreadable().isEmpty());
            assertTrue(index.uuids().contains(UUID.fromString(SqliteFixture.PLAYER_UUID)));
            assertTrue(index.uuids().contains(UUID.fromString(BETA)));
            assertTrue(index.uuids().contains(UUID.fromString(GAMMA)),
                "A player with only waypoints must still be migrated");
        }
    }

    @Test
    @DisplayName("A stored uuid that will not parse is reported, never silently ignored")
    void unparseableStoredUuidIsReported() throws SQLException {
        FakeStorageProvider provider = FakeStorageProvider.sqlite(workspace.resolve("broken.db").toFile());

        try (Connection connection = provider.connect()) {
            SqliteFixture.createSchema(connection);
            insertResearch(connection, ALPHA, 1);
            insertResearch(connection, "not-a-uuid", 2);

            PlayerIndex.Index index = PlayerIndex.fromSql(connection, StorageProviderType.SQLITE);

            assertEquals(1, index.size());
            assertEquals(1, index.unreadable().size());
            assertTrue(index.unreadable().get(0).contains("not-a-uuid"));
            assertTrue(index.unreadable().get(0).startsWith("player_researches"));
        }
    }

    @Test
    @DisplayName("An empty SQL backend enumerates nothing")
    void emptySqlBackendIsEmpty() throws SQLException {
        FakeStorageProvider provider = FakeStorageProvider.sqlite(workspace.resolve("empty.db").toFile());

        try (Connection connection = provider.connect()) {
            SqliteFixture.createSchema(connection);

            PlayerIndex.Index index = PlayerIndex.fromSql(connection, StorageProviderType.SQLITE);

            assertEquals(0, index.size());
            assertTrue(index.unreadable().isEmpty());
        }
    }

    @Test
    @DisplayName("Enumeration reads every managed table that carries a uuid")
    void everyPlayerTableIsEnumerated() {
        assertEquals(3, PlayerIndex.getPlayerTables().size());
        assertTrue(PlayerIndex.getPlayerTables().contains("player_researches"));
        assertTrue(PlayerIndex.getPlayerTables().contains("player_backpacks"));
        assertTrue(PlayerIndex.getPlayerTables().contains("player_waypoints"));
    }

    private void insertResearch(Connection connection, String uuid, int researchId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_researches (uuid, research_id) VALUES (?, ?)")) {
            ps.setString(1, uuid);
            ps.setInt(2, researchId);
            ps.executeUpdate();
        }
    }

    private void insertWaypointOnly(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_waypoints (uuid, waypoint_id, name, world, x, y, z, yaw, pitch)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid);
            ps.setString(2, "wp-only");
            ps.setString(3, "Outpost");
            ps.setString(4, "world");
            ps.setDouble(5, 0.5D);
            ps.setDouble(6, 64.0D);
            ps.setDouble(7, -0.5D);
            ps.setFloat(8, 0.0F);
            ps.setFloat(9, 0.0F);
            ps.executeUpdate();
        }
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
