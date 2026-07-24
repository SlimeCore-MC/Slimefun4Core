package io.github.thebusybiscuit.slimefun4.storage.schema;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs a database schema by adding any columns reported as missing by
 * {@link SchemaManager#verify}.
 *
 * <p>This class is intentionally conservative:
 * <ul>
 *   <li>It only ever <em>adds</em> columns — it never drops or renames them.</li>
 *   <li>It never drops or truncates tables.</li>
 *   <li>Every repair action is logged at {@link Level#INFO}.</li>
 * </ul>
 *
 * <p>Issue strings produced by {@link SchemaManager#verify} have the format
 * {@code Table 'X' missing column 'Y'} or {@code Table 'X' is missing}.
 * Only the "missing column" variant is actionable here; missing tables are left
 * for {@link SchemaManager#initialize} to create.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class SchemaRepair {

    /**
     * Pattern that matches issue strings of the form:
     * {@code Table 'tableName' missing column 'columnName'}
     */
    private static final Pattern MISSING_COLUMN_PATTERN =
        Pattern.compile("^Table '([^']+)' missing column '([^']+)'$");

    // -------------------------------------------------------------------------
    // Constructor (utility class)
    // -------------------------------------------------------------------------

    private SchemaRepair() {
        throw new UnsupportedOperationException("SchemaRepair is a utility class");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to repair each issue in the supplied list by executing an
     * {@code ALTER TABLE … ADD COLUMN} statement for every missing column.
     *
     * <p>SQLite does not support {@code IF NOT EXISTS} on {@code ADD COLUMN},
     * so for SQLite each ALTER is wrapped in an individual try-catch and any
     * {@link SQLException} is silently swallowed (the column may already exist
     * if the repair runs twice). For MySQL/MariaDB and PostgreSQL the
     * {@code IF NOT EXISTS} clause is used instead.
     *
     * @param connection The active database connection (auto-commit is left as-is)
     * @param type       The provider dialect (controls ALTER syntax)
     * @param issues     The issue list from {@link SchemaManager#verify}
     * @return The number of columns successfully added
     */
    public static int repair(
            @Nonnull Connection connection,
            @Nonnull StorageProviderType type,
            @Nonnull List<String> issues) {

        int repaired = 0;

        for (String issue : issues) {
            Matcher matcher = MISSING_COLUMN_PATTERN.matcher(issue);
            if (!matcher.matches()) {
                // "Table 'X' is missing" — handled by initialize(), skip here
                Slimefun.logger().log(Level.INFO,
                    "[Storage] Skipping non-column issue during repair: {0}", issue);
                continue;
            }

            String tableName  = matcher.group(1);
            String columnName = matcher.group(2);

            // Look up the ColumnDefinition so we know the correct SQL type
            Optional<TableDefinition.ColumnDefinition> colDefOpt =
                findColumnDefinition(tableName, columnName);

            if (colDefOpt.isEmpty()) {
                Slimefun.logger().log(Level.WARNING,
                    "[Storage] Cannot repair unknown column ''{0}'' in table ''{1}'' — no definition found",
                    new Object[] { columnName, tableName });
                continue;
            }

            TableDefinition.ColumnDefinition colDef = colDefOpt.get();
            String sqlType = colDef.getType(type);

            boolean added = addColumn(connection, type, tableName, columnName, sqlType, colDef.nullable());
            if (added) {
                repaired++;
            }
        }

        return repaired;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Finds a {@link TableDefinition.ColumnDefinition} by table name and column name.
     *
     * @param tableName  Table to look in
     * @param columnName Column to find (case-insensitive)
     * @return An optional containing the definition, or empty if not found
     */
    @Nonnull
    private static Optional<TableDefinition.ColumnDefinition> findColumnDefinition(
            @Nonnull String tableName,
            @Nonnull String columnName) {

        for (TableDefinition table : SchemaManager.getTables()) {
            if (!table.tableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            for (TableDefinition.ColumnDefinition col : table.columns()) {
                if (col.name().equalsIgnoreCase(columnName)) {
                    return Optional.of(col);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Executes an {@code ALTER TABLE … ADD COLUMN} for the given column.
     *
     * <ul>
     *   <li>For <strong>SQLite</strong>: uses a plain {@code ADD COLUMN} inside
     *       a try-catch (SQLite ≥ 3.20 will throw if the column already exists).</li>
     *   <li>For <strong>MySQL / MariaDB</strong> and <strong>PostgreSQL</strong>:
     *       uses {@code ADD COLUMN IF NOT EXISTS}.</li>
     * </ul>
     *
     * @param connection The active database connection
     * @param type       The provider dialect
     * @param tableName  Target table
     * @param columnName Column to add
     * @param sqlType    Full SQL type string (e.g. {@code VARCHAR(36) NOT NULL})
     * @param nullable   Whether the column allows NULL (appends {@code NOT NULL} if false
     *                   and the dialect supports it on ADD COLUMN)
     * @return {@code true} if the column was added successfully
     */
    private static boolean addColumn(
            @Nonnull Connection connection,
            @Nonnull StorageProviderType type,
            @Nonnull String tableName,
            @Nonnull String columnName,
            @Nonnull String sqlType,
            boolean nullable) {

        String nullConstraint = nullable ? "" : " NOT NULL DEFAULT ''";

        return switch (type) {
            case SQLITE -> addColumnSqlite(connection, tableName, columnName, sqlType, nullable);
            case MYSQL, MARIADB -> executeAlter(connection,
                "ALTER TABLE `" + tableName + "` ADD COLUMN IF NOT EXISTS `" + columnName + "` "
                + sqlType + nullConstraint,
                tableName, columnName);
            case POSTGRESQL -> executeAlter(connection,
                "ALTER TABLE \"" + tableName + "\" ADD COLUMN IF NOT EXISTS \"" + columnName + "\" "
                + sqlType + (nullable ? "" : " NOT NULL DEFAULT ''"),
                tableName, columnName);
            default -> {
                Slimefun.logger().log(Level.WARNING,
                    "[Storage] Repair not supported for provider type {0}", type);
                yield false;
            }
        };
    }

    /**
     * Adds a column for SQLite using a plain {@code ADD COLUMN} wrapped in a try-catch.
     * SQLite does not support {@code IF NOT EXISTS} on {@code ALTER TABLE ADD COLUMN}.
     *
     * <p>Note: SQLite does not allow {@code NOT NULL} without a default on new columns
     * (for existing rows), so we always add the column as nullable. This is acceptable
     * because repair is a rare recovery path.
     */
    private static boolean addColumnSqlite(
            @Nonnull Connection connection,
            @Nonnull String tableName,
            @Nonnull String columnName,
            @Nonnull String sqlType,
            boolean nullable) {

        String sql = "ALTER TABLE \"" + tableName + "\" ADD COLUMN \"" + columnName + "\" " + sqlType;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            Slimefun.logger().log(Level.INFO,
                "[Storage] Repair: added column ''{0}'' to table ''{1}'' (SQLite)",
                new Object[] { columnName, tableName });
            return true;
        } catch (SQLException x) {
            // Most likely "duplicate column name" — treat as a no-op
            Slimefun.logger().log(Level.FINE,
                "[Storage] Repair: ALTER TABLE for ''{0}''.'{1}'' skipped (may already exist): {2}",
                new Object[] { tableName, columnName, x.getMessage() });
            return false;
        }
    }

    /**
     * Executes a single ALTER TABLE statement and logs the result.
     *
     * @param connection The active database connection
     * @param sql        The ALTER TABLE SQL to execute
     * @param tableName  Table name (for log messages)
     * @param columnName Column name (for log messages)
     * @return {@code true} if the statement executed without error
     */
    private static boolean executeAlter(
            @Nonnull Connection connection,
            @Nonnull String sql,
            @Nonnull String tableName,
            @Nonnull String columnName) {

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            Slimefun.logger().log(Level.INFO,
                "[Storage] Repair: added column ''{0}'' to table ''{1}''",
                new Object[] { columnName, tableName });
            return true;
        } catch (SQLException x) {
            Slimefun.logger().log(Level.WARNING, x,
                () -> "[Storage] Repair failed for column '" + columnName
                      + "' in table '" + tableName + "'");
            return false;
        }
    }
}
