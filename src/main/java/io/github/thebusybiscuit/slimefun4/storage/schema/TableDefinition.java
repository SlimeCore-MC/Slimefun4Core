package io.github.thebusybiscuit.slimefun4.storage.schema;

import com.google.common.annotations.Beta;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Describes the expected structure of one database table: its name, columns,
 * and the DDL statement used to create it for each provider dialect.
 *
 * <p>Used by {@link SchemaManager} during initialization and verification,
 * and by {@link SchemaRepair} when a column is found missing.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record TableDefinition(
    @Nonnull String tableName,
    @Nonnull List<ColumnDefinition> columns,
    @Nonnull String sqliteDdl,
    @Nonnull String mysqlDdl,
    @Nonnull String postgresqlDdl
) {

    /**
     * Returns the correct CREATE TABLE DDL for the given provider type.
     *
     * @param type The provider type to get DDL for
     * @return The DDL string
     */
    @Nonnull
    public String getDdl(@Nonnull StorageProviderType type) {
        return switch (type) {
            case MYSQL, MARIADB -> mysqlDdl;
            case POSTGRESQL     -> postgresqlDdl;
            default             -> sqliteDdl; // SQLite and LEGACY
        };
    }

    /**
     * Describes a single column within a {@link TableDefinition}.
     *
     * @param name          Column name (case-insensitive for comparison)
     * @param sqliteType    SQLite type string
     * @param mysqlType     MySQL/MariaDB type string
     * @param postgresqlType PostgreSQL type string
     * @param nullable      Whether the column allows NULL values
     */
    @Beta
    public record ColumnDefinition(
        @Nonnull String name,
        @Nonnull String sqliteType,
        @Nonnull String mysqlType,
        @Nonnull String postgresqlType,
        boolean nullable
    ) {
        /**
         * Returns the column type string for the given provider.
         *
         * @param type Provider type
         * @return SQL type string
         */
        @Nonnull
        public String getType(@Nonnull StorageProviderType type) {
            return switch (type) {
                case MYSQL, MARIADB -> mysqlType;
                case POSTGRESQL     -> postgresqlType;
                default             -> sqliteType;
            };
        }
    }
}
