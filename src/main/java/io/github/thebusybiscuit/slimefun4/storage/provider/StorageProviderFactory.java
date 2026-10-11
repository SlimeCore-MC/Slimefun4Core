package io.github.thebusybiscuit.slimefun4.storage.provider;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.Storage;
import io.github.thebusybiscuit.slimefun4.storage.StorageException;
import io.github.thebusybiscuit.slimefun4.storage.backend.legacy.LegacyStorage;
import io.github.thebusybiscuit.slimefun4.storage.backend.mariadb.MariaDBProvider;
import io.github.thebusybiscuit.slimefun4.storage.backend.mysql.MySQLProvider;
import io.github.thebusybiscuit.slimefun4.storage.backend.postgresql.PostgreSQLProvider;
import io.github.thebusybiscuit.slimefun4.storage.backend.sqlite.SQLiteStorage;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;

import javax.annotation.Nonnull;

import java.util.Locale;
import java.util.UUID;

/**
 * Factory that reads the {@code provider} key from {@code database.yml} and
 * instantiates the appropriate {@link StorageProvider}.
 *
 * <p>The rest of the codebase should call this factory rather than directly
 * instantiating provider classes.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class StorageProviderFactory {

    private StorageProviderFactory() {}

    /**
     * Creates the configured {@link StorageProvider}.
     *
     * <p>If the provider key is {@code "legacy"} (or missing / unknown), a
     * {@link LegacyStorageAdapter} wrapping the existing YAML backend is returned.
     * This adapter fully implements {@link StorageProvider} so callers never need
     * to special-case the legacy backend.
     *
     * @param plugin   The current {@link Slimefun} instance
     * @param dbConfig The parsed {@code database.yml} config
     * @return A ready-to-initialize {@link StorageProvider}
     */
    @Nonnull
    public static StorageProvider create(@Nonnull Slimefun plugin, @Nonnull Config dbConfig) {
        String providerKey = dbConfig.getString("provider");
        String type = providerKey == null ? "legacy" : providerKey.trim().toLowerCase(Locale.ROOT);

        return switch (type) {
            case "sqlite"               -> new SQLiteStorage(plugin, dbConfig);
            case "mysql"                -> new MySQLProvider(plugin, dbConfig);
            case "mariadb"              -> new MariaDBProvider(plugin, dbConfig);
            case "postgresql", "postgres" -> new PostgreSQLProvider(plugin, dbConfig);
            default                     -> new LegacyStorageAdapter(new LegacyStorage());
        };
    }

    /**
     * Creates a {@link LegacyStorageAdapter} wrapping the given {@link LegacyStorage}.
     * Used by {@link io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService}
     * for the fallback provider path.
     *
     * @param legacy The legacy storage to wrap
     * @return A {@link StorageProvider} adapter
     */
    @Nonnull
    public static StorageProvider LegacyStorageAdapter_create(@Nonnull LegacyStorage legacy) {
        return new LegacyStorageAdapter(legacy);
    }
    // Legacy adapter — wraps LegacyStorage as a StorageProvider
    /**
     * Adapts the existing {@link LegacyStorage} (YAML-based) to the
     * {@link StorageProvider} interface so it can be used interchangeably with
     * SQL-backed providers.
     */
    static final class LegacyStorageAdapter implements StorageProvider {

        private final LegacyStorage delegate;

        LegacyStorageAdapter(@Nonnull LegacyStorage delegate) {
            this.delegate = delegate;
        }

        @Override public @Nonnull String getProviderName()         { return "Legacy YAML"; }
        @Override public @Nonnull StorageProviderType getProviderType() { return StorageProviderType.LEGACY; }
        @Override public long pingMillis()                         { return 0L; }
        @Override public @Nonnull StoragePoolStats getPoolStats()  { return new StoragePoolStats(0, 0, 0, 0); }
        @Override public @Nonnull SchemaVersion getSchemaVersion() { return SchemaVersion.NONE; }

        @Override public PlayerData loadPlayerData(UUID uuid)              { return delegate.loadPlayerData(uuid); }
        @Override public void savePlayerData(UUID uuid, PlayerData data)   { delegate.savePlayerData(uuid, data); }
        @Override public void initialize() throws StorageException         { delegate.initialize(); }
        @Override public void shutdown()                                   { delegate.shutdown(); }
        @Override public boolean isAvailable()                             { return delegate.isAvailable(); }
        @Override public String getBackendName()                           { return "Legacy YAML"; }
    }
}
