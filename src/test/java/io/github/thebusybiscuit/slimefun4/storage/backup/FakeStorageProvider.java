package io.github.thebusybiscuit.slimefun4.storage.backup;

import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;
import io.github.thebusybiscuit.slimefun4.storage.provider.SchemaVersion;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * A minimal {@link StorageProvider} used by the backup and restore tests.
 *
 * <p>Three shapes are needed: a real SQLite file, a SQL provider whose pool cannot
 * serve a connection, and a file backend that serves none at all. No Bukkit server
 * is involved, so these tests run without MockBukkit.
 */
public final class FakeStorageProvider implements StorageProvider {

    private final StorageProviderType type;
    private final String name;
    private final boolean sql;

    @Nullable
    private final File database;

    private FakeStorageProvider(StorageProviderType type, String name, boolean sql, @Nullable File database) {
        this.type = type;
        this.name = name;
        this.sql = sql;
        this.database = database;
    }

    @Nonnull
    public static FakeStorageProvider sqlite(@Nonnull File database) {
        return new FakeStorageProvider(StorageProviderType.SQLITE, "SQLite", true, database);
    }

    @Nonnull
    public static FakeStorageProvider unreachableSql() {
        return new FakeStorageProvider(StorageProviderType.MYSQL, "MySQL", true, null);
    }

    @Nonnull
    public static FakeStorageProvider legacy() {
        return new FakeStorageProvider(StorageProviderType.LEGACY, "Legacy YAML", false, null);
    }

    @Nonnull
    public Connection connect() throws SQLException {
        if (database == null) {
            throw new SQLException("This provider has no database file");
        }

        return DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
    }

    @Override
    public boolean supportsSql() {
        return sql;
    }

    @Override
    @Nonnull
    public Optional<Connection> borrowConnection() throws SQLException {
        if (!sql) {
            return Optional.empty();
        }

        if (database == null) {
            throw new SQLException("Connection pool is exhausted");
        }

        return Optional.of(connect());
    }

    @Override
    @Nonnull
    public String getProviderName() {
        return name;
    }

    @Override
    @Nonnull
    public StorageProviderType getProviderType() {
        return type;
    }

    @Override
    public long pingMillis() {
        return 0L;
    }

    @Override
    @Nonnull
    public StoragePoolStats getPoolStats() {
        return new StoragePoolStats(0, 1, 0, 1);
    }

    @Override
    @Nonnull
    public SchemaVersion getSchemaVersion() {
        return SchemaVersion.NONE;
    }

    @Override
    public PlayerData loadPlayerData(UUID uuid) {
        throw new UnsupportedOperationException("Player data is not part of these tests");
    }

    @Override
    public void savePlayerData(UUID uuid, PlayerData data) {
        throw new UnsupportedOperationException("Player data is not part of these tests");
    }
}
