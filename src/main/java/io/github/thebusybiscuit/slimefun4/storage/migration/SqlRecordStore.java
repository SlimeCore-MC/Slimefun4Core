package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SqlDialect;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes {@link PlayerRecords} straight over JDBC, bypassing
 * {@code loadPlayerData} and {@code savePlayerData} entirely.
 *
 * <p>The provider read path resolves every research id against the registry, whitelists
 * backpack sizes, deserializes items and requires a loaded world for waypoints, dropping
 * anything that fails. This store does none of that: rows move as raw integers, raw bytes
 * and raw world names, so a migration cannot quietly shrink a player's data.
 *
 * <p>Statements are built with {@link SqlDialect#quoteIdent} so the same code is correct
 * on MySQL, MariaDB, PostgreSQL and SQLite.
 *
 * <p>One conversion is unavoidable: {@code player_waypoints.name} is declared
 * {@code NOT NULL}, while the legacy YAML layout may omit a waypoint name entirely. A
 * missing name is written as an empty string rather than aborting, because an absent
 * optional label is not lost data.
 *
 * @author SlimefunCore Contributors
 */
public final class SqlRecordStore {

    /**
     * How many rows are queued before a batch is sent to the destination.
     */
    public static final int BATCH_SIZE = 500;

    private static final String RESEARCHES = "player_researches";
    private static final String BACKPACKS = "player_backpacks";
    private static final String BACKPACK_ITEMS = "player_backpack_items";
    private static final String WAYPOINTS = "player_waypoints";

    private static final List<String> PLAYER_TABLES =
        List.of(RESEARCHES, BACKPACKS, BACKPACK_ITEMS, WAYPOINTS);

    private SqlRecordStore() {}

    /**
     * @return Every managed table that carries player data, in write order
     */
    @Nonnull
    public static List<String> getPlayerTables() {
        return PLAYER_TABLES;
    }

    /**
     * Reads everything one player owns.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @param uuid       The player to read
     * @return The player's records, empty if the backend holds nothing for them
     * @throws SQLException If a row could not be read, or holds a null where the schema forbids one
     */
    @Nonnull
    public static PlayerRecords read(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull UUID uuid
    ) throws SQLException {
        PlayerRecords records = new PlayerRecords(uuid);

        readResearches(connection, type, uuid, records);
        readBackpacks(connection, type, uuid, records);
        readWaypoints(connection, type, uuid, records);

        return records;
    }

    private static void readResearches(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        String sql = "SELECT " + ident("research_id", type)
            + " FROM " + ident(RESEARCHES, type)
            + " WHERE " + ident("uuid", type) + " = ?"
            + " ORDER BY " + ident("research_id", type);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.addResearch(rs.getInt(1));
                }
            }
        }
    }

    private static void readBackpacks(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        String sql = "SELECT " + ident("backpack_id", type) + ", " + ident("size", type)
            + " FROM " + ident(BACKPACKS, type)
            + " WHERE " + ident("uuid", type) + " = ?"
            + " ORDER BY " + ident("backpack_id", type);

        Map<Integer, Integer> sizes = new LinkedHashMap<>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sizes.put(rs.getInt(1), rs.getInt(2));
                }
            }
        }

        if (sizes.isEmpty()) {
            return;
        }

        String itemSql = "SELECT " + ident("slot", type) + ", " + ident("item_data", type)
            + " FROM " + ident(BACKPACK_ITEMS, type)
            + " WHERE " + ident("uuid", type) + " = ?"
            + " AND " + ident("backpack_id", type) + " = ?"
            + " ORDER BY " + ident("slot", type);

        try (PreparedStatement ps = connection.prepareStatement(itemSql)) {
            for (Map.Entry<Integer, Integer> backpack : sizes.entrySet()) {
                Map<Integer, byte[]> items = new LinkedHashMap<>();

                ps.setString(1, uuid.toString());
                ps.setInt(2, backpack.getKey());

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.put(rs.getInt(1), rs.getBytes(2));
                    }
                }

                records.addBackpack(new PlayerRecords.BackpackRecord(
                    backpack.getKey(), backpack.getValue(), items));
            }
        }
    }

    private static void readWaypoints(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        String sql = "SELECT " + ident("waypoint_id", type) + ", " + ident("name", type)
            + ", " + ident("world", type) + ", " + ident("x", type) + ", " + ident("y", type)
            + ", " + ident("z", type) + ", " + ident("yaw", type) + ", " + ident("pitch", type)
            + " FROM " + ident(WAYPOINTS, type)
            + " WHERE " + ident("uuid", type) + " = ?"
            + " ORDER BY " + ident("waypoint_id", type);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String waypointId = rs.getString(1);
                    String world = rs.getString(3);

                    if (waypointId == null || world == null) {
                        throw new SQLException("Waypoint row of player " + uuid
                            + " has no id or no world and cannot be migrated safely");
                    }

                    records.addWaypoint(new PlayerRecords.WaypointRecord(
                        waypointId,
                        rs.getString(2),
                        world,
                        rs.getDouble(4),
                        rs.getDouble(5),
                        rs.getDouble(6),
                        rs.getFloat(7),
                        rs.getFloat(8)
                    ));
                }
            }
        }
    }

    /**
     * Writes everything one player owns.
     *
     * <p>The caller owns the transaction: nothing here commits or rolls back, so a whole
     * migration can be discarded as one unit.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @param records    The records to insert
     * @return How many rows were inserted
     * @throws SQLException If any insert fails
     */
    public static int write(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        int rows = 0;

        rows += writeResearches(connection, type, records);
        rows += writeBackpacks(connection, type, records);
        rows += writeWaypoints(connection, type, records);

        return rows;
    }

    private static int writeResearches(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        if (records.getResearches().isEmpty()) {
            return 0;
        }

        String sql = "INSERT INTO " + ident(RESEARCHES, type)
            + " (" + ident("uuid", type) + ", " + ident("research_id", type) + ")"
            + " VALUES (?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int researchId : records.getResearches()) {
                ps.setString(1, records.getUuid().toString());
                ps.setInt(2, researchId);
                ps.addBatch();
            }

            return countBatch(ps);
        }
    }

    private static int writeBackpacks(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        if (records.getBackpacks().isEmpty()) {
            return 0;
        }

        String uuid = records.getUuid().toString();
        String backpackSql = "INSERT INTO " + ident(BACKPACKS, type)
            + " (" + ident("uuid", type) + ", " + ident("backpack_id", type)
            + ", " + ident("size", type) + ")"
            + " VALUES (?, ?, ?)";

        int rows = 0;

        try (PreparedStatement ps = connection.prepareStatement(backpackSql)) {
            for (PlayerRecords.BackpackRecord backpack : records.getBackpacks()) {
                ps.setString(1, uuid);
                ps.setInt(2, backpack.id());
                ps.setInt(3, backpack.size());
                ps.addBatch();
            }

            rows += countBatch(ps);
        }

        return rows + writeBackpackItems(connection, type, records);
    }

    private static int writeBackpackItems(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        String uuid = records.getUuid().toString();
        String sql = "INSERT INTO " + ident(BACKPACK_ITEMS, type)
            + " (" + ident("uuid", type) + ", " + ident("backpack_id", type)
            + ", " + ident("slot", type) + ", " + ident("item_data", type) + ")"
            + " VALUES (?, ?, ?, ?)";

        int rows = 0;
        int pending = 0;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (PlayerRecords.BackpackRecord backpack : records.getBackpacks()) {
                for (Map.Entry<Integer, byte[]> item : backpack.items().entrySet()) {
                    ps.setString(1, uuid);
                    ps.setInt(2, backpack.id());
                    ps.setInt(3, item.getKey());

                    if (item.getValue() == null) {
                        ps.setNull(4, Types.VARBINARY);
                    } else {
                        ps.setBytes(4, item.getValue());
                    }

                    ps.addBatch();
                    pending++;

                    if (pending >= BATCH_SIZE) {
                        rows += countBatch(ps);
                        pending = 0;
                    }
                }
            }

            if (pending > 0) {
                rows += countBatch(ps);
            }
        }

        return rows;
    }

    private static int writeWaypoints(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull PlayerRecords records
    ) throws SQLException {
        if (records.getWaypoints().isEmpty()) {
            return 0;
        }

        String uuid = records.getUuid().toString();
        String sql = "INSERT INTO " + ident(WAYPOINTS, type)
            + " (" + ident("uuid", type) + ", " + ident("waypoint_id", type)
            + ", " + ident("name", type) + ", " + ident("world", type)
            + ", " + ident("x", type) + ", " + ident("y", type) + ", " + ident("z", type)
            + ", " + ident("yaw", type) + ", " + ident("pitch", type) + ")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (PlayerRecords.WaypointRecord waypoint : records.getWaypoints()) {
                ps.setString(1, uuid);
                ps.setString(2, waypoint.id());
                ps.setString(3, waypoint.name() == null ? "" : waypoint.name());
                ps.setString(4, waypoint.world());
                ps.setDouble(5, waypoint.x());
                ps.setDouble(6, waypoint.y());
                ps.setDouble(7, waypoint.z());
                ps.setFloat(8, waypoint.yaw());
                ps.setFloat(9, waypoint.pitch());
                ps.addBatch();
            }

            return countBatch(ps);
        }
    }

    /**
     * Removes every row one player owns, used when a migration is allowed to overwrite.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @param uuid       The player to clear
     * @return How many rows were removed
     * @throws SQLException If a delete fails
     */
    public static int delete(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull UUID uuid
    ) throws SQLException {
        int rows = 0;

        for (String table : PLAYER_TABLES) {
            String sql = "DELETE FROM " + ident(table, type)
                + " WHERE " + ident("uuid", type) + " = ?";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                rows += ps.executeUpdate();
            }
        }

        return rows;
    }

    /**
     * Counts the rows of one table.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @param table      The table to count
     * @return The row count
     * @throws SQLException If the table could not be read
     */
    public static int countRows(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type,
        @Nonnull String table
    ) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + ident(table, type);

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {

            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Counts every player row a backend holds, used to decide whether a destination is empty.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @return The total row count across every player table
     * @throws SQLException If a table could not be read
     */
    public static int countPlayerRows(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type
    ) throws SQLException {
        int rows = 0;

        for (String table : PLAYER_TABLES) {
            rows += countRows(connection, type, table);
        }

        return rows;
    }

    private static int countBatch(@Nonnull PreparedStatement ps) throws SQLException {
        int rows = 0;

        for (int count : ps.executeBatch()) {
            rows += count > 0 ? count : 1;
        }

        return rows;
    }

    @Nonnull
    private static String ident(@Nonnull String identifier, @Nonnull StorageProviderType type) {
        return SqlDialect.quoteIdent(identifier, type);
    }
}
