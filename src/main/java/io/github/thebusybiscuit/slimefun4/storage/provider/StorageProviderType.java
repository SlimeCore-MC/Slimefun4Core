package io.github.thebusybiscuit.slimefun4.storage.provider;

import com.google.common.annotations.Beta;

/**
 * Identifies which database technology backs a {@link io.github.thebusybiscuit.slimefun4.storage.StorageProvider}.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public enum StorageProviderType {

    /** SQLite — embedded, file-based, no server required. */
    SQLITE,

    /** MySQL — remote, server-based relational database. */
    MYSQL,

    /** MariaDB — MySQL-compatible fork with active community development. */
    MARIADB,

    /** PostgreSQL — advanced open-source relational database. */
    POSTGRESQL,

    /** Legacy YAML file-based storage (deprecated, kept for migration). */
    LEGACY
}
