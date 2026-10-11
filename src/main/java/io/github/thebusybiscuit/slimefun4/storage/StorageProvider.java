package io.github.thebusybiscuit.slimefun4.storage;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.storage.provider.SchemaVersion;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Extension of {@link Storage} that represents a concrete, configurable
 * database provider (SQLite, MySQL, MariaDB, PostgreSQL).
 *
 * <p>Implementations must be thread-safe and must never block the Minecraft
 * main thread during any I/O operation.
 *
 * <p>The rest of Slimefun4Core interacts exclusively with this interface —
 * never with provider-specific classes directly. Switching providers requires
 * changing one configuration value in {@code database.yml}.
 *
 * @author SlimefunCore Contributors
 *
 * @see Storage
 * @see StorageProviderType
 */
@Beta
public interface StorageProvider extends Storage {

    /**
     * 
     *
     * @return e.g. {@literal "SQLite"}, {@literal "MySQL"}
     */
    @Nonnull String getProviderName();

    /**
     * Returns the {@link StorageProviderType} that identifies this provider.
     *
     * @return The provider type enum constant
     */
    @Nonnull StorageProviderType getProviderType();

    /**
     * Measures the round-trip latency to the database by executing a trivial
     * query (e.g. {@code SELECT 1}).
     *
     * @return Latency in milliseconds, or {@code -1} if the check failed
     */
    long pingMillis();

    /**
     * Returns a snapshot of the current HikariCP connection pool statistics.
     *
     * @return An immutable {@link StoragePoolStats} snapshot
     */
    @Nonnull StoragePoolStats getPoolStats();

    /**
     * Returns the schema version currently applied to the database.
     *
     * @return The current {@link SchemaVersion}, or {@link SchemaVersion#NONE}
     *         if the schema version table has not been created yet
     */
    @Nonnull SchemaVersion getSchemaVersion();

    /**
     * Returns whether this provider is backed by a SQL database and can therefore
     * serve JDBC connections through {@link #borrowConnection()}.
     *
     * @return {@code true} for SQL-backed providers, {@code false} for file storage
     */
    default boolean supportsSql() {
        return false;
    }

    /**
     * Borrows a pooled JDBC {@link Connection} from this provider.
     *
     * <p>The caller owns the returned connection and must close it. Providers that
     * are not SQL-backed return {@link Optional#empty()} rather than throwing, so
     * callers can branch on storage shape without reflection or instanceof chains.
     *
     * @return A live connection, or {@link Optional#empty()} if this provider has none
     * @throws SQLException If the provider is SQL-backed but the pool cannot serve a connection
     */
    @Nonnull
    default Optional<Connection> borrowConnection() throws SQLException {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Overridden to refine the return type to {@link StorageProvider}
     * for use in display methods (e.g. startup banner).
     */
    @Override
    default String getBackendName() {
        return getProviderName();
    }
}
