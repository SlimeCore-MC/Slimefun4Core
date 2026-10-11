package io.github.thebusybiscuit.slimefun4.storage.backend.mysql;

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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * MySQL-backed {@link StorageProvider} for Slimefun player data.
 *
 * <p>All configuration is read from {@code database.yml} under the {@code mysql.*}
 * (or overridden by a subclass via {@link #getConfigSection()}) and {@code pool.*} keys.
 *
 * <p>{@link MariaDBProvider} extends this class and overrides only the JDBC URL
 * scheme and config-section name.
 *
 * @author SlimefunCore Contributors
 * @see MariaDBProvider
 */
@Beta
public class MySQLProvider implements StorageProvider {

    private static final Set<Integer> VALID_BACKPACK_SIZES = Set.of(9, 18, 27, 36, 45, 54);

    private final Slimefun plugin;
    private final Config dbConfig;

    private volatile @Nullable HikariDataSource dataSource;
    private volatile boolean available = false;

    /**
     * Creates a new {@link MySQLProvider}.
     *
     * @param plugin   The current {@link Slimefun} instance
     * @param dbConfig The parsed {@code database.yml} config
     */
    public MySQLProvider(@Nonnull Slimefun plugin, @Nonnull Config dbConfig) {
        Validate.notNull(plugin, "Plugin must not be null");
        Validate.notNull(dbConfig, "Database config must not be null");
        this.plugin = plugin;
        this.dbConfig = dbConfig;
    }
    // StorageProvider — metadata
    @Override
    public @Nonnull String getProviderName() {
        return "MySQL";
    }

    @Override
    public @Nonnull StorageProviderType getProviderType() {
        return StorageProviderType.MYSQL;
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
            return new StoragePoolStats(0, 0, 0, 0);
        }
        HikariPoolMXBean bean = source.getHikariPoolMXBean();
        if (bean == null) {
            return new StoragePoolStats(0, 0, 0, source.getMaximumPoolSize());
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
    // Storage — lifecycle
    @Override
    public void initialize() throws StorageException {
        String section = getConfigSection();
        String host     = dbConfig.getString(section + ".host");
        int    port     = dbConfig.getInt(section    + ".port");
        String database = dbConfig.getString(section + ".database");
        String username = dbConfig.getString(section + ".username");
        String password = dbConfig.getString(section + ".password");
        boolean ssl     = dbConfig.getBoolean(section + ".ssl");

        if (host == null || database == null || username == null) {
            throw new StorageException("Incomplete " + section + " configuration in database.yml");
        }

        try {
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("Slimefun-" + getProviderName());
            cfg.setJdbcUrl(buildJdbcUrl(host, port, database, ssl));
            cfg.setDriverClassName(getDriverClassName());
            cfg.setUsername(username);
            cfg.setPassword(password != null ? password : "");

            // Pool settings
            cfg.setMaximumPoolSize(getInt("pool.maximum-pool-size", 10));
            cfg.setMinimumIdle(getInt("pool.minimum-idle", 2));
            cfg.setConnectionTimeout(getLong("pool.connection-timeout", 30_000L));
            cfg.setIdleTimeout(getLong("pool.idle-timeout", 600_000L));
            cfg.setMaxLifetime(getLong("pool.max-lifetime", 1_800_000L));
            long leakThreshold = getLong("pool.leak-detection-threshold", 60_000L);
            if (leakThreshold > 0) {
                cfg.setLeakDetectionThreshold(leakThreshold);
            }
            cfg.setConnectionTestQuery("SELECT 1");

            dataSource = new HikariDataSource(cfg);

            // Initialize / verify schema
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    SchemaManager.initialize(conn, getProviderType());
                    conn.commit();
                } catch (Exception x) {
                    conn.rollback();
                    throw x;
                } finally {
                    conn.setAutoCommit(true);
                }
            }

            available = true;
        } catch (Exception x) {
            available = false;
            shutdown();
            throw new StorageException("Failed to initialize " + getProviderName() + " storage", x);
        }
    }

    @Override
    public void shutdown() {
        available = false;
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null && !source.isClosed()) {
            source.close();
        }
    }

    @Override
    public boolean isAvailable() {
        return available && dataSource != null && !dataSource.isClosed();
    }
    // Storage — data operations
    @Override
    public PlayerData loadPlayerData(@Nonnull UUID uuid) {
        ensureAvailable();
        long start = System.nanoTime();

        Set<Research>                 researches;
        Map<Integer, PlayerBackpack>  backpacks;
        Set<Waypoint>                 waypoints;

        try (Connection conn = getConnection()) {
            researches = loadResearches(conn, uuid);
            backpacks  = loadBackpacks(conn, uuid);
            waypoints  = loadWaypoints(conn, uuid);
        } catch (SQLException x) {
            throw new IllegalStateException("Could not load player data for " + uuid, x);
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime(
            getProviderName().toLowerCase(), true, end - start);

        return new PlayerData(researches, backpacks, waypoints);
    }

    @Override
    public void savePlayerData(@Nonnull UUID uuid, @Nonnull PlayerData data) {
        ensureAvailable();

        if (!data.isDirty()) {
            return;
        }

        long start = System.nanoTime();

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (data.isResearchesDirty()) {
                    saveResearches(conn, uuid, data.getResearches());
                }
                if (data.isBackpacksDirty()) {
                    saveBackpacks(conn, uuid, data.getBackpacks());
                }
                if (data.isWaypointsDirty()) {
                    saveWaypoints(conn, uuid, data.getWaypoints());
                }
                conn.commit();
                data.markClean();
            } catch (Exception x) {
                conn.rollback();
                throw x;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception x) {
            throw new IllegalStateException("Could not save player data for " + uuid, x);
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime(
            getProviderName().toLowerCase(), false, end - start);
    }
    // Protected extension points (overridden by MariaDBProvider)
    /**
     * Returns the YAML config section key used to read connection parameters.
     * Override in subclasses (e.g. {@code "mariadb"}).
     *
     * @return Config section name
     */
    @Nonnull
    protected String getConfigSection() {
        return "mysql";
    }

    /**
     * Builds the JDBC URL for this provider.
     *
     * @param host     Database host
     * @param port     Database port
     * @param database Database/schema name
     * @param ssl      Whether SSL is required
     * @return Full JDBC URL string
     */
    @Nonnull
    protected String buildJdbcUrl(@Nonnull String host, int port,
                                  @Nonnull String database, boolean ssl) {
        String sslParam = ssl
            ? "useSSL=true&requireSSL=true"
            : "useSSL=false&allowPublicKeyRetrieval=true";
        return "jdbc:mysql://" + host + ":" + port + "/" + database
            + "?characterEncoding=utf8&autoReconnect=true&" + sslParam;
    }

    /**
     * Returns the JDBC driver class name to load.
     *
     * @return Fully-qualified driver class name
     */
    @Nonnull
    protected String getDriverClassName() {
        return "com.mysql.cj.jdbc.Driver";
    }
    // Private — load helpers
    private @Nonnull Set<Research> loadResearches(
            @Nonnull Connection conn, @Nonnull UUID uuid) throws SQLException {

        Map<Integer, List<Research>> byId = new HashMap<>();
        for (Research r : Slimefun.getRegistry().getResearches()) {
            byId.computeIfAbsent(r.getID(), ignored -> new ArrayList<>()).add(r);
        }

        Set<Research> result = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT research_id FROM player_researches WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("research_id");
                    List<Research> matches = byId.get(id);
                    if (matches == null || matches.isEmpty()) {
                        plugin.getLogger().log(Level.WARNING,
                            "Skipping unknown Research ID {0} for Player \"{1}\"",
                            new Object[] { id, uuid });
                        continue;
                    }
                    result.addAll(matches);
                }
            }
        }
        return result;
    }

    private @Nonnull Map<Integer, PlayerBackpack> loadBackpacks(
            @Nonnull Connection conn, @Nonnull UUID uuid) throws SQLException {

        Map<Integer, PlayerBackpack> result = new HashMap<>();

        try (PreparedStatement bpStmt = conn.prepareStatement(
                "SELECT backpack_id, size FROM player_backpacks WHERE uuid = ?");
             PreparedStatement itemStmt = conn.prepareStatement(
                "SELECT slot, item_data FROM player_backpack_items WHERE uuid = ? AND backpack_id = ?")) {

            bpStmt.setString(1, uuid.toString());

            try (ResultSet bpRs = bpStmt.executeQuery()) {
                while (bpRs.next()) {
                    int backpackId = bpRs.getInt("backpack_id");
                    int size       = bpRs.getInt("size");

                    if (!VALID_BACKPACK_SIZES.contains(size)) {
                        plugin.getLogger().log(Level.WARNING,
                            "Skipping Backpack \"{0}\" for Player \"{1}\" because size {2} is invalid",
                            new Object[] { backpackId, uuid, size });
                        continue;
                    }

                    HashMap<Integer, ItemStack> contents = new HashMap<>();
                    itemStmt.setString(1, uuid.toString());
                    itemStmt.setInt(2, backpackId);

                    try (ResultSet itemRs = itemStmt.executeQuery()) {
                        while (itemRs.next()) {
                            int    slot     = itemRs.getInt("slot");
                            byte[] itemData = itemRs.getBytes("item_data");
                            if (itemData == null) {
                                continue;
                            }
                            try {
                                contents.put(slot, ItemStack.deserializeBytes(itemData));
                            } catch (Exception ex) {
                                plugin.getLogger().log(Level.WARNING, ex,
                                    () -> "Skipping Backpack slot " + slot
                                        + " for Backpack \"" + backpackId
                                        + "\" of Player \"" + uuid + '"');
                            }
                        }
                    }

                    result.put(backpackId, PlayerBackpack.load(uuid, backpackId, size, contents));
                }
            }
        }
        return result;
    }

    private @Nonnull Set<Waypoint> loadWaypoints(
            @Nonnull Connection conn, @Nonnull UUID uuid) throws SQLException {

        Set<Waypoint> result = new HashSet<>();

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT waypoint_id, name, world, x, y, z, yaw, pitch "
                + "FROM player_waypoints WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String worldName = rs.getString("world");
                    if (Bukkit.getWorld(worldName) == null) {
                        plugin.getLogger().log(Level.WARNING,
                            "Skipping Waypoint \"{0}\" for Player \"{1}\" because world \"{2}\" is missing",
                            new Object[] { rs.getString("waypoint_id"), uuid, worldName });
                        continue;
                    }
                    Location loc = new Location(
                        Bukkit.getWorld(worldName),
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch")
                    );
                    result.add(new Waypoint(uuid, rs.getString("waypoint_id"), loc, rs.getString("name")));
                }
            }
        }
        return result;
    }
    // Private — save helpers
    private void saveResearches(@Nonnull Connection conn, @Nonnull UUID uuid,
                                @Nonnull Set<Research> researches) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM player_researches WHERE uuid = ?")) {
            del.setString(1, uuid.toString());
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO player_researches (uuid, research_id) VALUES (?, ?)")) {
            Set<Integer> saved = new HashSet<>();
            for (Research r : researches) {
                if (!saved.add(r.getID())) {
                    continue;
                }
                ins.setString(1, uuid.toString());
                ins.setInt(2, r.getID());
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private void saveBackpacks(@Nonnull Connection conn, @Nonnull UUID uuid,
                               @Nonnull Map<Integer, PlayerBackpack> backpacks) throws SQLException {
        try (PreparedStatement delItems = conn.prepareStatement(
                "DELETE FROM player_backpack_items WHERE uuid = ?")) {
            delItems.setString(1, uuid.toString());
            delItems.executeUpdate();
        }
        try (PreparedStatement delBp = conn.prepareStatement(
                "DELETE FROM player_backpacks WHERE uuid = ?")) {
            delBp.setString(1, uuid.toString());
            delBp.executeUpdate();
        }
        try (PreparedStatement insBp   = conn.prepareStatement(
                 "INSERT INTO player_backpacks (uuid, backpack_id, size) VALUES (?, ?, ?)");
             PreparedStatement insItem = conn.prepareStatement(
                 "INSERT INTO player_backpack_items (uuid, backpack_id, slot, item_data) VALUES (?, ?, ?, ?)")) {

            for (PlayerBackpack bp : backpacks.values()) {
                insBp.setString(1, uuid.toString());
                insBp.setInt(2, bp.getId());
                insBp.setInt(3, bp.getSize());
                insBp.addBatch();

                for (int slot = 0; slot < bp.getSize(); slot++) {
                    ItemStack item = bp.getInventory().getItem(slot);
                    if (item == null) {
                        continue;
                    }
                    insItem.setString(1, uuid.toString());
                    insItem.setInt(2, bp.getId());
                    insItem.setInt(3, slot);
                    insItem.setBytes(4, item.serializeAsBytes());
                    insItem.addBatch();
                }
            }
            insBp.executeBatch();
            insItem.executeBatch();
        }
    }

    private void saveWaypoints(@Nonnull Connection conn, @Nonnull UUID uuid,
                               @Nonnull Set<Waypoint> waypoints) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM player_waypoints WHERE uuid = ?")) {
            del.setString(1, uuid.toString());
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO player_waypoints "
                + "(uuid, waypoint_id, name, world, x, y, z, yaw, pitch) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {

            for (Waypoint wp : waypoints) {
                Location loc = wp.getLocation();
                if (loc.getWorld() == null) {
                    plugin.getLogger().log(Level.WARNING,
                        "Skipping Waypoint \"{0}\" for Player \"{1}\" because the world is null",
                        new Object[] { wp.getId(), uuid });
                    continue;
                }
                ins.setString(1, uuid.toString());
                ins.setString(2, wp.getId());
                ins.setString(3, wp.getName());
                ins.setString(4, loc.getWorld().getName());
                ins.setDouble(5, loc.getX());
                ins.setDouble(6, loc.getY());
                ins.setDouble(7, loc.getZ());
                ins.setFloat(8, loc.getYaw());
                ins.setFloat(9, loc.getPitch());
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }
    // Private — utilities
    @Nonnull
    private Connection getConnection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException(getProviderName() + " data source is not initialized");
        }
        return source.getConnection();
    }

    private void ensureAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(getProviderName() + " storage is not available");
        }
    }

    private int getInt(@Nonnull String key, int defaultValue) {
        int val = dbConfig.getInt(key);
        return val <= 0 ? defaultValue : val;
    }

    private long getLong(@Nonnull String key, long defaultValue) {
        long val = dbConfig.getLong(key);
        return val <= 0 ? defaultValue : val;
    }
}
