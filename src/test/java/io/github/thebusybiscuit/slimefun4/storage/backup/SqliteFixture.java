package io.github.thebusybiscuit.slimefun4.storage.backup;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Builds the managed schema and a known row set inside a throwaway SQLite database.
 *
 * <p>{@link SchemaManager#initialize} is deliberately not used: it logs through the
 * plugin logger, which does not exist in a plain JUnit run. The DDL itself comes from
 * the very same {@link TableDefinition} instances the production code uses.
 */
public final class SqliteFixture {

    public static final String PLAYER_UUID = "3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30";
    public static final String WAYPOINT_ID = "wp-1";

    public static final String TRICKY_NAME = "Home \"North\"\n} ] \\ \t end";

    public static final double X = 1.5D;
    public static final double Y = -2.25D;
    public static final double Z = 3.75D;
    public static final float YAW = 12.5F;
    public static final float PITCH = 0.25F;

    public static final int SEEDED_ROWS = 6;

    private SqliteFixture() {}

    public static void createSchema(@Nonnull Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (TableDefinition table : SchemaManager.getTables()) {
                statement.execute(table.getDdl(StorageProviderType.SQLITE));
            }
        }
    }

    @Nonnull
    public static byte[] blobPayload() {
        byte[] payload = new byte[256];

        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        return payload;
    }

    public static void seed(@Nonnull Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO sf_schema_version (version, applied_at) VALUES (?, ?)")) {
            ps.setInt(1, SchemaManager.CURRENT_SCHEMA_VERSION);
            ps.setString(2, "2026-09-04T00:00:00Z");
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_researches (uuid, research_id) VALUES (?, ?)")) {
            ps.setString(1, PLAYER_UUID);
            ps.setInt(2, 3);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_backpacks (uuid, backpack_id, size) VALUES (?, ?, ?)")) {
            ps.setString(1, PLAYER_UUID);
            ps.setInt(2, 1);
            ps.setInt(3, 27);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_backpack_items (uuid, backpack_id, slot, item_data) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, PLAYER_UUID);
            ps.setInt(2, 1);
            ps.setInt(3, 0);
            ps.setBytes(4, blobPayload());
            ps.executeUpdate();

            ps.setString(1, PLAYER_UUID);
            ps.setInt(2, 1);
            ps.setInt(3, 1);
            ps.setNull(4, Types.VARBINARY);
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_waypoints (uuid, waypoint_id, name, world, x, y, z, yaw, pitch)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, PLAYER_UUID);
            ps.setString(2, WAYPOINT_ID);
            ps.setString(3, TRICKY_NAME);
            ps.setString(4, "world");
            ps.setDouble(5, X);
            ps.setDouble(6, Y);
            ps.setDouble(7, Z);
            ps.setFloat(8, YAW);
            ps.setFloat(9, PITCH);
            ps.executeUpdate();
        }
    }

    public static void wipe(@Nonnull Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (TableDefinition table : SchemaManager.getTables()) {
                statement.executeUpdate("DELETE FROM " + table.tableName());
            }
        }
    }

    public static int countRows(@Nonnull Connection connection) throws SQLException {
        int rows = 0;

        try (Statement statement = connection.createStatement()) {
            for (TableDefinition table : SchemaManager.getTables()) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table.tableName())) {
                    if (rs.next()) {
                        rows += rs.getInt(1);
                    }
                }
            }
        }

        return rows;
    }
}
