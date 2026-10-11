package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.SqlDialect;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies every managed table from one SQL backend into another, row for row.
 *
 * <p>Values never become text on the way across. Each column moves through
 * {@link SqlDialect#transfer}, so {@code player_backpack_items.item_data} is carried as
 * {@code byte[]} and a serialized {@code ItemStack} arrives byte for byte identical. No
 * Bukkit class is touched, which is what lets this path run without resolving a single
 * research id, backpack size or world name.
 *
 * <p>Tables come from {@link SchemaManager#getTables()}, so a future table is copied without
 * an edit here. Only {@code sf_schema_version} is left out, because the destination writes
 * its own version row during {@code SchemaManager.initialize}.
 *
 * <p>Nothing here commits or rolls back. The caller owns the destination transaction so a
 * whole migration succeeds or is discarded as one unit.
 *
 * @author SlimefunCore Contributors
 */
public final class SqlTableCopier {

    private static final String SCHEMA_VERSION_TABLE = "sf_schema_version";

    private SqlTableCopier() {}

    /**
     * @return Every managed table a copy moves, in schema order
     */
    @Nonnull
    public static List<TableDefinition> getCopiedTables() {
        List<TableDefinition> tables = new ArrayList<>();

        for (TableDefinition table : SchemaManager.getTables()) {
            if (!SCHEMA_VERSION_TABLE.equalsIgnoreCase(table.tableName())) {
                tables.add(table);
            }
        }

        return List.copyOf(tables);
    }

    /**
     * Copies every managed table.
     *
     * @param source          An open connection to read from
     * @param sourceType      The provider type of the source
     * @param destination     An open connection to write to, inside a transaction
     * @param destinationType The provider type of the destination
     * @return A map of table name to the number of rows copied, in schema order
     * @throws SQLException If any row could not be read or written
     */
    @Nonnull
    public static Map<String, Integer> copyAll(
        @Nonnull Connection source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull Connection destination,
        @Nonnull StorageProviderType destinationType
    ) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (TableDefinition table : getCopiedTables()) {
            counts.put(table.tableName(),
                copyTable(source, sourceType, destination, destinationType, table));
        }

        return counts;
    }

    /**
     * Copies one table.
     *
     * @param source          An open connection to read from
     * @param sourceType      The provider type of the source
     * @param destination     An open connection to write to, inside a transaction
     * @param destinationType The provider type of the destination
     * @param table           The table to copy
     * @return How many rows were copied
     * @throws SQLException If any row could not be read or written
     */
    public static int copyTable(
        @Nonnull Connection source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull Connection destination,
        @Nonnull StorageProviderType destinationType,
        @Nonnull TableDefinition table
    ) throws SQLException {
        List<TableDefinition.ColumnDefinition> columns = table.columns();
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder insert = new StringBuilder("INSERT INTO ")
            .append(SqlDialect.quoteIdent(table.tableName(), destinationType))
            .append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                select.append(", ");
                insert.append(", ");
            }

            select.append(SqlDialect.quoteIdent(columns.get(i).name(), sourceType));
            insert.append(SqlDialect.quoteIdent(columns.get(i).name(), destinationType));
        }

        select.append(" FROM ").append(SqlDialect.quoteIdent(table.tableName(), sourceType));
        insert.append(") VALUES (").append("?, ".repeat(columns.size() - 1)).append("?)");

        int copied = 0;
        int pending = 0;

        try (Statement statement = source.createStatement();
             ResultSet rs = statement.executeQuery(select.toString());
             PreparedStatement ps = destination.prepareStatement(insert.toString())) {

            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    TableDefinition.ColumnDefinition column = columns.get(i);
                    SqlDialect.transfer(rs, column.name(), ps, i + 1, column.getType(destinationType));
                }

                ps.addBatch();
                pending++;

                if (pending >= SqlRecordStore.BATCH_SIZE) {
                    copied += countBatch(ps);
                    pending = 0;
                }
            }

            if (pending > 0) {
                copied += countBatch(ps);
            }
        }

        return copied;
    }

    /**
     * Removes every row of every copied table, used when a migration is allowed to overwrite.
     *
     * <p>{@code sf_schema_version} is left alone so the destination keeps its own version row.
     *
     * @param connection An open connection, inside a transaction
     * @param type       The provider type of the connection
     * @return How many rows were removed
     * @throws SQLException If a delete fails
     */
    public static int clear(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type
    ) throws SQLException {
        int removed = 0;

        for (TableDefinition table : getCopiedTables()) {
            String sql = "DELETE FROM " + SqlDialect.quoteIdent(table.tableName(), type);

            try (Statement statement = connection.createStatement()) {
                removed += statement.executeUpdate(sql);
            }
        }

        return removed;
    }

    /**
     * Counts the rows of every copied table, used to verify that a copy moved everything.
     *
     * @param connection An open connection
     * @param type       The provider type of the connection
     * @return A map of table name to row count, in schema order
     * @throws SQLException If a table could not be read
     */
    @Nonnull
    public static Map<String, Integer> countAll(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType type
    ) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (TableDefinition table : getCopiedTables()) {
            counts.put(table.tableName(), SqlRecordStore.countRows(connection, type, table.tableName()));
        }

        return counts;
    }

    private static int countBatch(@Nonnull PreparedStatement ps) throws SQLException {
        int rows = 0;

        for (int count : ps.executeBatch()) {
            rows += count > 0 ? count : 1;
        }

        return rows;
    }
}
