package io.github.thebusybiscuit.slimefun4.storage.backend.sqlite;

import com.google.common.annotations.Beta;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.api.gps.Waypoint;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.researches.Research;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageException;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;
import io.github.thebusybiscuit.slimefun4.storage.provider.SchemaVersion;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQLite-backed player storage for Slimefun.
 */
@Beta
public class SQLiteStorage implements StorageProvider {

    private static final Set<Integer> VALID_BACKPACK_SIZES = Set.of(9, 18, 27, 36, 45, 54);
    private static final String DEFAULT_DB_PATH = "data-storage/Slimefun/slimefun.db";

    private final Slimefun plugin;
    private final File databaseFile;
    private volatile @Nullable HikariDataSource dataSource;
    private volatile boolean available = false;

    /**
     * Creates a new {@link SQLiteStorage} using the default database path.
     * Preserved for backward compatibility.
     *
     * @param plugin The current {@link Slimefun} instance
     */
    public SQLiteStorage(@Nonnull Slimefun plugin) {
        this(plugin, null);
    }

    /**
     * Creates a new {@link SQLiteStorage}, reading the database file path from
     * {@code database.yml} when {@code dbConfig} is non-null.
     *
     * @param plugin   The current {@link Slimefun} instance
     * @param dbConfig The parsed {@code database.yml} config, or {@code null} to
     *                 use the default path ({@value DEFAULT_DB_PATH})
     */
    public SQLiteStorage(@Nonnull Slimefun plugin, @Nullable Config dbConfig) {
        Validate.notNull(plugin, "Plugin must not be null");
        this.plugin = plugin;
        String path = (dbConfig != null) ? dbConfig.getString("sqlite.database") : null;
        this.databaseFile = new File(path != null ? path : DEFAULT_DB_PATH);
    }

    @Override
    public void initialize() throws StorageException {
        try {
            Files.createDirectories(databaseFile.getParentFile().toPath());
            Class.forName("org.sqlite.JDBC");

            HikariConfig config = new HikariConfig();
            config.setPoolName("Slimefun-SQLite");
            config.setJdbcUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setConnectionTestQuery("SELECT 1");

            dataSource = new HikariDataSource(config);

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }

            // Initialize and verify schema via SchemaManager
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    SchemaManager.initialize(connection, StorageProviderType.SQLITE);
                    connection.commit();
                } catch (Exception x) {
                    connection.rollback();
                    throw x;
                } finally {
                    connection.setAutoCommit(true);
                }
            }

            available = true;
        } catch (Exception x) {
            available = false;
            shutdown();
            throw new StorageException("Failed to initialize SQLite storage", x);
        }
    }

    @Override
    public void shutdown() {
        available = false;

        HikariDataSource source = dataSource;
        dataSource = null;

        if (source != null) {
            source.close();
        }
    }
    // StorageProvider — metadata
    @Override
    public @Nonnull String getProviderName() {
        return "SQLite";
    }

    @Override
    public @Nonnull StorageProviderType getProviderType() {
        return StorageProviderType.SQLITE;
    }

    @Override
    public boolean supportsSql() {
        return true;
    }

    @Override
    public @Nonnull Optional<Connection> borrowConnection() throws SQLException {
        return Optional.of(getConnection());
    }
    // StorageProvider — health checks
    @Override
    public long pingMillis() {
        if (!isAvailable()) {
            return -1L;
        }
        long start = System.currentTimeMillis();
        try (Connection conn = getConnection();
             var stmt = conn.createStatement();
             var rs   = stmt.executeQuery("SELECT 1")) {
            rs.next();
            return System.currentTimeMillis() - start;
        } catch (SQLException x) {
            return -1L;
        }
    }

    @Override
    public @Nonnull StoragePoolStats getPoolStats() {
        HikariDataSource source = dataSource;
        if (source == null) {
            return new StoragePoolStats(0, 0, 0, 1);
        }
        HikariPoolMXBean bean = source.getHikariPoolMXBean();
        if (bean == null) {
            return new StoragePoolStats(0, 0, 0, 1);
        }
        return new StoragePoolStats(
            bean.getActiveConnections(),
            bean.getIdleConnections(),
            bean.getThreadsAwaitingConnection(),
            bean.getTotalConnections()
        );
    }

    @Override
    public @Nonnull SchemaVersion getSchemaVersion() {
        if (!isAvailable()) {
            return SchemaVersion.NONE;
        }
        try (Connection conn = getConnection()) {
            return SchemaManager.getCurrentVersion(conn);
        } catch (SQLException x) {
            return SchemaVersion.NONE;
        }
    }

    @Override
    public boolean isAvailable() {
        return available && dataSource != null;
    }

    @Override
    public String getBackendName() {
        return "SQLite";
    }

    @Override
    public PlayerData loadPlayerData(@Nonnull UUID uuid) {
        ensureAvailable();

        long start = System.nanoTime();
        Set<Research> researches;
        Map<Integer, PlayerBackpack> backpacks;
        Set<Waypoint> waypoints;

        try (Connection connection = getConnection()) {
            researches = loadResearches(connection, uuid);
            backpacks = loadBackpacks(connection, uuid);
            waypoints = loadWaypoints(connection, uuid);
        } catch (SQLException x) {
            throw new IllegalStateException("Could not load player data for " + uuid, x);
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime("sqlite", true, end - start);

        return new PlayerData(researches, backpacks, waypoints);
    }

    @Override
    public void savePlayerData(@Nonnull UUID uuid, @Nonnull PlayerData data) {
        ensureAvailable();

        long start = System.nanoTime();

        if (!data.isDirty()) {
            return;
        }

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);

            try {
                if (data.isResearchesDirty()) {
                    saveResearches(connection, uuid, data.getResearches());
                }

                if (data.isBackpacksDirty()) {
                    saveBackpacks(connection, uuid, data.getBackpacks());
                }

                if (data.isWaypointsDirty()) {
                    saveWaypoints(connection, uuid, data.getWaypoints());
                }

                connection.commit();
                data.markClean();
            } catch (Exception x) {
                connection.rollback();
                throw x;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception x) {
            throw new IllegalStateException("Could not save player data for " + uuid, x);
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime("sqlite", false, end - start);
    }

    private @Nonnull Set<Research> loadResearches(@Nonnull Connection connection, @Nonnull UUID uuid) {
        Map<Integer, List<Research>> researchesById = new HashMap<>();
        for (Research research : Slimefun.getRegistry().getResearches()) {
            researchesById.computeIfAbsent(research.getID(), ignored -> new ArrayList<>()).add(research);
        }

        Set<Research> loadedResearches = new HashSet<>();

        try (
            PreparedStatement statement = connection.prepareStatement("SELECT research_id FROM player_researches WHERE uuid = ?")
        ) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int researchId = resultSet.getInt("research_id");
                    List<Research> matchedResearches = researchesById.get(researchId);

                    if (matchedResearches == null || matchedResearches.isEmpty()) {
                        plugin.getLogger().log(Level.WARNING, "Skipping unknown Research ID {0} for Player \"{1}\"", new Object[] { researchId, uuid });
                        continue;
                    }

                    loadedResearches.addAll(matchedResearches);
                }
            }
        } catch (SQLException x) {
            throw new IllegalStateException("Could not load researches for " + uuid, x);
        }

        return loadedResearches;
    }

    private @Nonnull Map<Integer, PlayerBackpack> loadBackpacks(@Nonnull Connection connection, @Nonnull UUID uuid) {
        Map<Integer, PlayerBackpack> loadedBackpacks = new HashMap<>();

        try (
            PreparedStatement backpackStatement = connection.prepareStatement("SELECT backpack_id, size FROM player_backpacks WHERE uuid = ?");
            PreparedStatement itemStatement = connection.prepareStatement("SELECT slot, item_data FROM player_backpack_items WHERE uuid = ? AND backpack_id = ?")
        ) {
            backpackStatement.setString(1, uuid.toString());

            try (ResultSet backpackResults = backpackStatement.executeQuery()) {
                while (backpackResults.next()) {
                    int backpackId = backpackResults.getInt("backpack_id");
                    int size = backpackResults.getInt("size");

                    if (!VALID_BACKPACK_SIZES.contains(size)) {
                        plugin.getLogger().log(Level.WARNING, "Skipping Backpack \"{0}\" for Player \"{1}\" because size {2} is invalid", new Object[] { backpackId, uuid, size });
                        continue;
                    }

                    HashMap<Integer, ItemStack> contents = new HashMap<>();
                    itemStatement.setString(1, uuid.toString());
                    itemStatement.setInt(2, backpackId);

                    try (ResultSet itemResults = itemStatement.executeQuery()) {
                        while (itemResults.next()) {
                            int slot = itemResults.getInt("slot");
                            byte[] itemData = itemResults.getBytes("item_data");

                            if (itemData == null) {
                                continue;
                            }

                            try {
                                contents.put(slot, ItemStack.deserializeBytes(itemData));
                            } catch (Exception itemException) {
                                plugin.getLogger().log(Level.WARNING, itemException, () -> "Skipping Backpack slot " + slot + " for Backpack \"" + backpackId + "\" of Player \"" + uuid + '"');
                            }
                        }
                    }

                    loadedBackpacks.put(backpackId, PlayerBackpack.load(uuid, backpackId, size, contents));
                }
            }
        } catch (SQLException x) {
            throw new IllegalStateException("Could not load backpacks for " + uuid, x);
        }

        return loadedBackpacks;
    }

    private @Nonnull Set<Waypoint> loadWaypoints(@Nonnull Connection connection, @Nonnull UUID uuid) {
        Set<Waypoint> loadedWaypoints = new HashSet<>();

        try (
            PreparedStatement statement = connection.prepareStatement("""
                SELECT waypoint_id, name, world, x, y, z, yaw, pitch
                FROM player_waypoints
                WHERE uuid = ?
                """)
        ) {
            statement.setString(1, uuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String worldName = resultSet.getString("world");

                    if (Bukkit.getWorld(worldName) == null) {
                        plugin.getLogger().log(Level.WARNING, "Skipping Waypoint \"{0}\" for Player \"{1}\" because world \"{2}\" is missing",
                            new Object[] { resultSet.getString("waypoint_id"), uuid, worldName });
                        continue;
                    }

                    Location location = new Location(
                        Bukkit.getWorld(worldName),
                        resultSet.getDouble("x"),
                        resultSet.getDouble("y"),
                        resultSet.getDouble("z"),
                        resultSet.getFloat("yaw"),
                        resultSet.getFloat("pitch")
                    );

                    loadedWaypoints.add(new Waypoint(
                        uuid,
                        resultSet.getString("waypoint_id"),
                        location,
                        resultSet.getString("name")
                    ));
                }
            }
        } catch (SQLException x) {
            throw new IllegalStateException("Could not load waypoints for " + uuid, x);
        }

        return loadedWaypoints;
    }

    private void saveResearches(@Nonnull Connection connection, @Nonnull UUID uuid, @Nonnull Set<Research> researches) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM player_researches WHERE uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }

        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO player_researches (uuid, research_id) VALUES (?, ?)")) {
            Set<Integer> savedResearchIds = new HashSet<>();

            for (Research research : researches) {
                if (!savedResearchIds.add(research.getID())) {
                    continue;
                }

                insert.setString(1, uuid.toString());
                insert.setInt(2, research.getID());
                insert.addBatch();
            }

            insert.executeBatch();
        }
    }

    private void saveBackpacks(@Nonnull Connection connection, @Nonnull UUID uuid, @Nonnull Map<Integer, PlayerBackpack> backpacks) throws SQLException {
        try (PreparedStatement deleteItems = connection.prepareStatement("DELETE FROM player_backpack_items WHERE uuid = ?")) {
            deleteItems.setString(1, uuid.toString());
            deleteItems.executeUpdate();
        }

        try (PreparedStatement deleteBackpacks = connection.prepareStatement("DELETE FROM player_backpacks WHERE uuid = ?")) {
            deleteBackpacks.setString(1, uuid.toString());
            deleteBackpacks.executeUpdate();
        }

        try (
            PreparedStatement insertBackpack = connection.prepareStatement("INSERT INTO player_backpacks (uuid, backpack_id, size) VALUES (?, ?, ?)");
            PreparedStatement insertItem = connection.prepareStatement("INSERT INTO player_backpack_items (uuid, backpack_id, slot, item_data) VALUES (?, ?, ?, ?)")
        ) {
            for (PlayerBackpack backpack : backpacks.values()) {
                insertBackpack.setString(1, uuid.toString());
                insertBackpack.setInt(2, backpack.getId());
                insertBackpack.setInt(3, backpack.getSize());
                insertBackpack.addBatch();

                for (int slot = 0; slot < backpack.getSize(); slot++) {
                    ItemStack item = backpack.getInventory().getItem(slot);
                    if (item == null) {
                        continue;
                    }

                    insertItem.setString(1, uuid.toString());
                    insertItem.setInt(2, backpack.getId());
                    insertItem.setInt(3, slot);
                    insertItem.setBytes(4, item.serializeAsBytes());
                    insertItem.addBatch();
                }
            }

            insertBackpack.executeBatch();
            insertItem.executeBatch();
        }
    }

    private void saveWaypoints(@Nonnull Connection connection, @Nonnull UUID uuid, @Nonnull Set<Waypoint> waypoints) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM player_waypoints WHERE uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }

        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO player_waypoints (uuid, waypoint_id, name, world, x, y, z, yaw, pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            for (Waypoint waypoint : waypoints) {
                Location location = waypoint.getLocation();

                if (location.getWorld() == null) {
                    plugin.getLogger().log(Level.WARNING, "Skipping Waypoint \"{0}\" for Player \"{1}\" because the world is null",
                        new Object[] { waypoint.getId(), uuid });
                    continue;
                }

                insert.setString(1, uuid.toString());
                insert.setString(2, waypoint.getId());
                insert.setString(3, waypoint.getName());
                insert.setString(4, location.getWorld().getName());
                insert.setDouble(5, location.getX());
                insert.setDouble(6, location.getY());
                insert.setDouble(7, location.getZ());
                insert.setFloat(8, location.getYaw());
                insert.setFloat(9, location.getPitch());
                insert.addBatch();
            }

            insert.executeBatch();
        }
    }

    private @Nonnull Connection getConnection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException("SQLite data source is not initialized");
        }

        return source.getConnection();
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException("SQLite storage is not available");
        }
    }
}
