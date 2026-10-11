package io.github.thebusybiscuit.slimefun4.storage.schema;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.provider.SchemaVersion;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

/**
 * Manages the database schema for all SQL-backed {@link io.github.thebusybiscuit.slimefun4.storage.StorageProvider}
 * implementations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Defines the canonical list of all expected {@link TableDefinition} instances</li>
 *   <li>Creates tables if they are missing ({@link #initialize})</li>
 *   <li>Verifies that all expected columns exist in the live database ({@link #verify})</li>
 *   <li>Reads the current schema version from {@code sf_schema_version} ({@link #getCurrentVersion})</li>
 * </ul>
 *
 * <p>All methods require an active, open {@link Connection}. They do <em>not</em> manage
 * connection lifecycle — callers are responsible for opening and closing connections.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class SchemaManager {

    /** The schema version written during initial database creation. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    // Canonical table definitions
    private static final TableDefinition TABLE_SCHEMA_VERSION = new TableDefinition(
        "sf_schema_version",
        List.of(
            new TableDefinition.ColumnDefinition("version",    "INTEGER", "INT",          "INT",  false),
            new TableDefinition.ColumnDefinition("applied_at", "TEXT",    "VARCHAR(64)",  "TEXT", false)
        ),
        // SQLite DDL
        """
        CREATE TABLE IF NOT EXISTS sf_schema_version (
            version    INTEGER      NOT NULL,
            applied_at TEXT         NOT NULL
        )
        """,
        // MySQL DDL
        """
        CREATE TABLE IF NOT EXISTS sf_schema_version (
            version    INT          NOT NULL,
            applied_at VARCHAR(64)  NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        // PostgreSQL DDL
        """
        CREATE TABLE IF NOT EXISTS sf_schema_version (
            version    INT  NOT NULL,
            applied_at TEXT NOT NULL
        )
        """
    );

    private static final TableDefinition TABLE_PLAYER_RESEARCHES = new TableDefinition(
        "player_researches",
        List.of(
            new TableDefinition.ColumnDefinition("uuid",        "TEXT",    "VARCHAR(36)", "TEXT", false),
            new TableDefinition.ColumnDefinition("research_id", "INTEGER", "INT",         "INT",  false)
        ),
        // SQLite DDL
        """
        CREATE TABLE IF NOT EXISTS player_researches (
            uuid        TEXT    NOT NULL,
            research_id INTEGER NOT NULL,
            PRIMARY KEY (uuid, research_id)
        )
        """,
        // MySQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_researches (
            uuid        VARCHAR(36) NOT NULL,
            research_id INT         NOT NULL,
            PRIMARY KEY (uuid, research_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        // PostgreSQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_researches (
            uuid        TEXT NOT NULL,
            research_id INT  NOT NULL,
            PRIMARY KEY (uuid, research_id)
        )
        """
    );

    private static final TableDefinition TABLE_PLAYER_BACKPACKS = new TableDefinition(
        "player_backpacks",
        List.of(
            new TableDefinition.ColumnDefinition("uuid",        "TEXT",    "VARCHAR(36)", "TEXT", false),
            new TableDefinition.ColumnDefinition("backpack_id", "INTEGER", "INT",         "INT",  false),
            new TableDefinition.ColumnDefinition("size",        "INTEGER", "INT",         "INT",  false)
        ),
        // SQLite DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpacks (
            uuid        TEXT    NOT NULL,
            backpack_id INTEGER NOT NULL,
            size        INTEGER NOT NULL,
            PRIMARY KEY (uuid, backpack_id)
        )
        """,
        // MySQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpacks (
            uuid        VARCHAR(36) NOT NULL,
            backpack_id INT         NOT NULL,
            size        INT         NOT NULL,
            PRIMARY KEY (uuid, backpack_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        // PostgreSQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpacks (
            uuid        TEXT NOT NULL,
            backpack_id INT  NOT NULL,
            size        INT  NOT NULL,
            PRIMARY KEY (uuid, backpack_id)
        )
        """
    );

    private static final TableDefinition TABLE_PLAYER_BACKPACK_ITEMS = new TableDefinition(
        "player_backpack_items",
        List.of(
            new TableDefinition.ColumnDefinition("uuid",        "TEXT",    "VARCHAR(36)",  "TEXT",  false),
            new TableDefinition.ColumnDefinition("backpack_id", "INTEGER", "INT",          "INT",   false),
            new TableDefinition.ColumnDefinition("slot",        "INTEGER", "INT",          "INT",   false),
            new TableDefinition.ColumnDefinition("item_data",   "BLOB",    "MEDIUMBLOB",   "BYTEA", true)
        ),
        // SQLite DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpack_items (
            uuid        TEXT    NOT NULL,
            backpack_id INTEGER NOT NULL,
            slot        INTEGER NOT NULL,
            item_data   BLOB,
            PRIMARY KEY (uuid, backpack_id, slot)
        )
        """,
        // MySQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpack_items (
            uuid        VARCHAR(36) NOT NULL,
            backpack_id INT         NOT NULL,
            slot        INT         NOT NULL,
            item_data   MEDIUMBLOB,
            PRIMARY KEY (uuid, backpack_id, slot)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        // PostgreSQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_backpack_items (
            uuid        TEXT NOT NULL,
            backpack_id INT  NOT NULL,
            slot        INT  NOT NULL,
            item_data   BYTEA,
            PRIMARY KEY (uuid, backpack_id, slot)
        )
        """
    );

    private static final TableDefinition TABLE_PLAYER_WAYPOINTS = new TableDefinition(
        "player_waypoints",
        List.of(
            new TableDefinition.ColumnDefinition("uuid",        "TEXT",   "VARCHAR(36)", "TEXT",             false),
            new TableDefinition.ColumnDefinition("waypoint_id", "TEXT",   "VARCHAR(64)", "TEXT",             false),
            new TableDefinition.ColumnDefinition("name",        "TEXT",   "VARCHAR(128)","TEXT",             false),
            new TableDefinition.ColumnDefinition("world",       "TEXT",   "VARCHAR(64)", "TEXT",             false),
            new TableDefinition.ColumnDefinition("x",           "DOUBLE", "DOUBLE",      "DOUBLE PRECISION", false),
            new TableDefinition.ColumnDefinition("y",           "DOUBLE", "DOUBLE",      "DOUBLE PRECISION", false),
            new TableDefinition.ColumnDefinition("z",           "DOUBLE", "DOUBLE",      "DOUBLE PRECISION", false),
            new TableDefinition.ColumnDefinition("yaw",         "FLOAT",  "FLOAT",       "REAL",             false),
            new TableDefinition.ColumnDefinition("pitch",       "FLOAT",  "FLOAT",       "REAL",             false)
        ),
        // SQLite DDL
        """
        CREATE TABLE IF NOT EXISTS player_waypoints (
            uuid        TEXT   NOT NULL,
            waypoint_id TEXT   NOT NULL,
            name        TEXT   NOT NULL,
            world       TEXT   NOT NULL,
            x           DOUBLE NOT NULL,
            y           DOUBLE NOT NULL,
            z           DOUBLE NOT NULL,
            yaw         FLOAT  NOT NULL,
            pitch       FLOAT  NOT NULL,
            PRIMARY KEY (uuid, waypoint_id)
        )
        """,
        // MySQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_waypoints (
            uuid        VARCHAR(36)  NOT NULL,
            waypoint_id VARCHAR(64)  NOT NULL,
            name        VARCHAR(128) NOT NULL,
            world       VARCHAR(64)  NOT NULL,
            x           DOUBLE       NOT NULL,
            y           DOUBLE       NOT NULL,
            z           DOUBLE       NOT NULL,
            yaw         FLOAT        NOT NULL,
            pitch       FLOAT        NOT NULL,
            PRIMARY KEY (uuid, waypoint_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """,
        // PostgreSQL DDL
        """
        CREATE TABLE IF NOT EXISTS player_waypoints (
            uuid        TEXT             NOT NULL,
            waypoint_id TEXT             NOT NULL,
            name        TEXT             NOT NULL,
            world       TEXT             NOT NULL,
            x           DOUBLE PRECISION NOT NULL,
            y           DOUBLE PRECISION NOT NULL,
            z           DOUBLE PRECISION NOT NULL,
            yaw         REAL             NOT NULL,
            pitch       REAL             NOT NULL,
            PRIMARY KEY (uuid, waypoint_id)
        )
        """
    );

    /**
     * Canonical, ordered list of all tables managed by this schema.
     * The ordering matters: {@code sf_schema_version} is always first so it is
     * created before any data tables.
     */
    private static final List<TableDefinition> TABLES = List.of(
        TABLE_SCHEMA_VERSION,
        TABLE_PLAYER_RESEARCHES,
        TABLE_PLAYER_BACKPACKS,
        TABLE_PLAYER_BACKPACK_ITEMS,
        TABLE_PLAYER_WAYPOINTS
    );
    // Constructor (utility class)
    private SchemaManager() {
        throw new UnsupportedOperationException("SchemaManager is a utility class");
    }
    // Public API
    /**
     * Returns the canonical list of all managed {@link TableDefinition} instances.
     *
     * @return An unmodifiable list of table definitions, in creation order
     */
    @Nonnull
    public static List<TableDefinition> getTables() {
        return TABLES;
    }

    /**
     * Creates all managed tables that do not yet exist in the database, then writes
     * schema version {@value #CURRENT_SCHEMA_VERSION} into {@code sf_schema_version}
     * if no version row is present yet.
     *
     * <p>The connection is not committed by this method — the caller controls the
     * transaction boundary.
     *
     * @param connection The active database connection
     * @param type       The provider dialect to use when selecting DDL statements
     * @throws SQLException If any DDL statement fails
     */
    public static void initialize(
            @Nonnull Connection connection,
            @Nonnull StorageProviderType type) throws SQLException {

        try (Statement statement = connection.createStatement()) {
            for (TableDefinition table : TABLES) {
                statement.execute(table.getDdl(type));
            }
        }

        // Only insert the version row once — idempotent
        SchemaVersion existing = getCurrentVersion(connection);
        if (existing == SchemaVersion.NONE) {
            String appliedAt = Instant.now().toString();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO sf_schema_version (version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, CURRENT_SCHEMA_VERSION);
                ps.setString(2, appliedAt);
                ps.executeUpdate();
            }
            Slimefun.logger().log(Level.INFO,
                "[Storage] Schema version {0} applied at {1}",
                new Object[] { CURRENT_SCHEMA_VERSION, appliedAt });
        }
    }

    /**
     * Verifies that every expected table and column exists in the live database.
     *
     * @param connection The active database connection
     * @param type       The provider dialect (controls how column metadata is queried)
     * @return A list of issue strings; empty means the schema is intact.
     *         Each issue has the format {@literal "Table 'X' missing column 'Y'"}
     *         or {@literal "Table 'X' is missing"}.
     */
    @Nonnull
    public static List<String> verify(
            @Nonnull Connection connection,
            @Nonnull StorageProviderType type) {

        List<String> issues = new ArrayList<>();

        for (TableDefinition table : TABLES) {
            Set<String> existingColumns = fetchExistingColumns(connection, type, table.tableName());
            if (existingColumns == null) {
                issues.add("Table '" + table.tableName() + "' is missing");
                continue;
            }

            for (TableDefinition.ColumnDefinition col : table.columns()) {
                if (!existingColumns.contains(col.name().toLowerCase(Locale.ROOT))) {
                    issues.add("Table '" + table.tableName() + "' missing column '" + col.name() + "'");
                }
            }
        }

        return issues;
    }

    /**
     * Reads the current schema version from the {@code sf_schema_version} table.
     *
     * @param connection The active database connection
     * @return The most-recently inserted {@link SchemaVersion} row, or
     *         {@link SchemaVersion#NONE} if the table does not yet exist or is empty
     */
    @Nonnull
    public static SchemaVersion getCurrentVersion(@Nonnull Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, applied_at FROM sf_schema_version ORDER BY version DESC LIMIT 1")) {
            if (rs.next()) {
                return new SchemaVersion(rs.getInt("version"), rs.getString("applied_at"));
            }
            return SchemaVersion.NONE;
        } catch (SQLException x) {
            // Table does not exist yet — that is expected on a fresh database
            return SchemaVersion.NONE;
        }
    }
    // Private helpers
    /**
     * Fetches the set of column names (lower-case) that exist in the given table.
     *
     * @param connection The active database connection
     * @param type       The provider dialect
     * @param tableName  The table to inspect
     * @return A set of lower-case column names, or {@code null} if the table is
     *         absent (only possible for SQLite where we rely on a different API)
     */
    private static Set<String> fetchExistingColumns(
            @Nonnull Connection connection,
            @Nonnull StorageProviderType type,
            @Nonnull String tableName) {

        try {
            Set<String> columns = new HashSet<>();

            switch (type) {
                case SQLITE -> {
                    // PRAGMA table_info returns nothing (no rows) if the table is absent
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT name FROM pragma_table_info(?)")) {
                        ps.setString(1, tableName);
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean found = false;
                            while (rs.next()) {
                                found = true;
                                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
                            }
                            if (!found) {
                                return null; // table absent
                            }
                        }
                    }
                }
                case MYSQL, MARIADB -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?")) {
                        ps.setString(1, tableName);
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean found = false;
                            while (rs.next()) {
                                found = true;
                                columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                            }
                            if (!found) {
                                return null; // table absent
                            }
                        }
                    }
                }
                case POSTGRESQL -> {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ?")) {
                        ps.setString(1, tableName);
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean found = false;
                            while (rs.next()) {
                                found = true;
                                columns.add(rs.getString("column_name").toLowerCase(Locale.ROOT));
                            }
                            if (!found) {
                                return null; // table absent
                            }
                        }
                    }
                }
                default -> {
                    // LEGACY or unknown — skip verification
                    return Collections.emptySet();
                }
            }

            return columns;
        } catch (SQLException x) {
            Slimefun.logger().log(Level.WARNING, x,
                () -> "[Storage] Failed to query column metadata for table '" + tableName + "'");
            return null;
        }
    }
}
