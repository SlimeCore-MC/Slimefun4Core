package io.github.thebusybiscuit.slimefun4.storage.backend.mariadb;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backend.mysql.MySQLProvider;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import org.apache.commons.lang.Validate;

import javax.annotation.Nonnull;

/**
 * MariaDB-backed {@link io.github.thebusybiscuit.slimefun4.storage.StorageProvider}.
 *
 * <p>Extends {@link MySQLProvider} — the SQL dialect is identical; only the
 * JDBC URL scheme ({@code jdbc:mariadb://}), the JDBC driver class, and the
 * config section ({@code mariadb.*}) differ.
 *
 * <p>Configuration is read from {@code database.yml} under the {@code mariadb.*}
 * and {@code pool.*} keys.
 *
 * @author SlimefunCore Contributors
 * @see MySQLProvider
 */
@Beta
public class MariaDBProvider extends MySQLProvider {

    /**
     * Creates a new {@link MariaDBProvider}.
     *
     * @param plugin   The current {@link Slimefun} instance
     * @param dbConfig The parsed {@code database.yml} config
     */
    public MariaDBProvider(@Nonnull Slimefun plugin, @Nonnull Config dbConfig) {
        super(plugin, dbConfig);
        Validate.notNull(plugin, "Plugin must not be null");
        Validate.notNull(dbConfig, "Database config must not be null");
    }

    @Override
    public @Nonnull String getProviderName() {
        return "MariaDB";
    }

    @Override
    public @Nonnull StorageProviderType getProviderType() {
        return StorageProviderType.MARIADB;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code "mariadb"} so configuration is read from
     * {@code mariadb.host}, {@code mariadb.port}, etc.
     */
    @Override
    @Nonnull
    protected String getConfigSection() {
        return "mariadb";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses the {@code jdbc:mariadb://} scheme required by the MariaDB Connector/J driver.
     */
    @Override
    @Nonnull
    protected String buildJdbcUrl(@Nonnull String host, int port,
                                  @Nonnull String database, boolean ssl) {
        String sslParam = ssl ? "?useSsl=true" : "?useSsl=false";
        return "jdbc:mariadb://" + host + ":" + port + "/" + database + sslParam
            + "&characterEncoding=utf8&autoReconnect=true";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the MariaDB Connector/J driver class.
     */
    @Override
    @Nonnull
    protected String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }
}
