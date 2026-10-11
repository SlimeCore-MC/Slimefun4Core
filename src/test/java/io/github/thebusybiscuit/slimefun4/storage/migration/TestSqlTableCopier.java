package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.backup.FakeStorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.SqliteFixture;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code SQL -> SQL} path, which must be byte exact.
 *
 * <p>The failure this pins down is the one the restore path used to have: an item blob that
 * takes a text detour comes out corrupted. Nothing here goes near Bukkit, so a research id,
 * a backpack size and a world name are all carried as raw values.
 */
class TestSqlTableCopier {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("Every managed row crosses, and an item blob arrives byte for byte identical")
    void roundTripIsByteExact() throws SQLException {
        try (Connection source = open("source.db"); Connection destination = open("destination.db")) {
            SqliteFixture.createSchema(source);
            SqliteFixture.seed(source);
            SqliteFixture.createSchema(destination);

            destination.setAutoCommit(false);

            Map<String, Integer> copied = SqlTableCopier.copyAll(
                source, StorageProviderType.SQLITE, destination, StorageProviderType.SQLITE);

            destination.commit();

            assertEquals(1, copied.get("player_researches"));
            assertEquals(1, copied.get("player_backpacks"));
            assertEquals(2, copied.get("player_backpack_items"));
            assertEquals(1, copied.get("player_waypoints"));
            assertFalse(copied.containsKey("sf_schema_version"),
                "The destination writes its own schema version row");

            assertArrayEquals(SqliteFixture.blobPayload(), readItem(destination, 0),
                "A serialized item must never take a text detour");
            assertNull(readItem(destination, 1), "An empty slot must stay NULL, not become a blob");
            assertEquals(SqliteFixture.TRICKY_NAME, readWaypointName(destination),
                "A quote, a brace and a newline in a waypoint name must survive");
        }
    }

    @Test
    @DisplayName("Row counts match the source table for table, ignoring the version row")
    void rowCountsMatchTheSource() throws SQLException {
        try (Connection source = open("counted-source.db"); Connection destination = open("counted-target.db")) {
            SqliteFixture.createSchema(source);
            SqliteFixture.seed(source);
            SqliteFixture.createSchema(destination);

            destination.setAutoCommit(false);
            SqlTableCopier.copyAll(source, StorageProviderType.SQLITE, destination, StorageProviderType.SQLITE);
            destination.commit();

            assertEquals(
                SqlTableCopier.countAll(source, StorageProviderType.SQLITE),
                SqlTableCopier.countAll(destination, StorageProviderType.SQLITE));

            assertEquals(SqliteFixture.SEEDED_ROWS - 1, SqliteFixture.countRows(destination),
                "Only the schema version row is missing from the destination");
        }
    }

    @Test
    @DisplayName("A double, a float and every other column keep their exact value")
    void everyColumnKeepsItsValue() throws SQLException {
        try (Connection source = open("typed-source.db"); Connection destination = open("typed-target.db")) {
            SqliteFixture.createSchema(source);
            SqliteFixture.seed(source);
            SqliteFixture.createSchema(destination);

            destination.setAutoCommit(false);
            SqlTableCopier.copyAll(source, StorageProviderType.SQLITE, destination, StorageProviderType.SQLITE);
            destination.commit();

            try (Statement statement = destination.createStatement();
                 ResultSet rs = statement.executeQuery(
                     "SELECT uuid, waypoint_id, world, x, y, z, yaw, pitch FROM player_waypoints")) {

                assertTrue(rs.next());
                assertEquals(SqliteFixture.PLAYER_UUID, rs.getString("uuid"));
                assertEquals(SqliteFixture.WAYPOINT_ID, rs.getString("waypoint_id"));
                assertEquals("world", rs.getString("world"));
                assertEquals(SqliteFixture.X, rs.getDouble("x"), 0.0D);
                assertEquals(SqliteFixture.Y, rs.getDouble("y"), 0.0D);
                assertEquals(SqliteFixture.Z, rs.getDouble("z"), 0.0D);
                assertEquals(SqliteFixture.YAW, rs.getFloat("yaw"), 0.0F);
                assertEquals(SqliteFixture.PITCH, rs.getFloat("pitch"), 0.0F);
                assertFalse(rs.next());
            }

            try (Statement statement = destination.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT size FROM player_backpacks")) {

                assertTrue(rs.next());
                assertEquals(27, rs.getInt("size"));
            }
        }
    }

    @Test
    @DisplayName("A rollback leaves the destination exactly as it was, so a failure loses nothing")
    void rollbackDiscardsTheWholeCopy() throws SQLException {
        try (Connection source = open("rollback-source.db"); Connection destination = open("rollback-target.db")) {
            SqliteFixture.createSchema(source);
            SqliteFixture.seed(source);
            SqliteFixture.createSchema(destination);

            destination.setAutoCommit(false);
            SqlTableCopier.copyAll(source, StorageProviderType.SQLITE, destination, StorageProviderType.SQLITE);
            destination.rollback();

            assertEquals(0, SqliteFixture.countRows(destination),
                "Nothing may survive a rolled back migration");
        }
    }

    @Test
    @DisplayName("Clearing an overwrite target empties the copied tables and spares the version row")
    void clearSparesTheSchemaVersion() throws SQLException {
        try (Connection connection = open("cleared.db")) {
            SqliteFixture.createSchema(connection);
            SqliteFixture.seed(connection);

            connection.setAutoCommit(false);
            int removed = SqlTableCopier.clear(connection, StorageProviderType.SQLITE);
            connection.commit();

            assertEquals(SqliteFixture.SEEDED_ROWS - 1, removed);
            assertEquals(1, SqliteFixture.countRows(connection),
                "The schema version row must be left alone");

            for (Integer count : SqlTableCopier.countAll(connection, StorageProviderType.SQLITE).values()) {
                assertEquals(0, count);
            }
        }
    }

    @Test
    @DisplayName("A copy covers every managed table except the version row, with no edit needed here")
    void copiedTablesFollowTheSchema() {
        assertEquals(4, SqlTableCopier.getCopiedTables().size());

        for (TableDefinition table : SqlTableCopier.getCopiedTables()) {
            assertFalse("sf_schema_version".equalsIgnoreCase(table.tableName()));
            assertNotNull(table.getDdl(StorageProviderType.SQLITE));
        }
    }

    @Test
    @DisplayName("An empty source copies nothing rather than failing")
    void emptySourceCopiesNothing() throws SQLException {
        try (Connection source = open("blank-source.db"); Connection destination = open("blank-target.db")) {
            SqliteFixture.createSchema(source);
            SqliteFixture.createSchema(destination);

            destination.setAutoCommit(false);
            Map<String, Integer> copied = SqlTableCopier.copyAll(
                source, StorageProviderType.SQLITE, destination, StorageProviderType.SQLITE);
            destination.commit();

            for (Integer count : copied.values()) {
                assertEquals(0, count);
            }

            assertEquals(0, SqliteFixture.countRows(destination));
        }
    }

    private Connection open(String name) throws SQLException {
        return FakeStorageProvider.sqlite(workspace.resolve(name).toFile()).connect();
    }

    private byte[] readItem(Connection connection, int slot) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT item_data FROM player_backpack_items WHERE slot = ?")) {
            ps.setInt(1, slot);

            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Slot " + slot + " did not arrive at the destination");
                return rs.getBytes(1);
            }
        }
    }

    private String readWaypointName(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM player_waypoints")) {

            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
