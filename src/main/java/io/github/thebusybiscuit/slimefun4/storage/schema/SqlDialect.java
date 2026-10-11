package io.github.thebusybiscuit.slimefun4.storage.schema;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

/**
 * Dialect-aware identifier quoting and value binding shared by every component that
 * writes rows through JDBC.
 *
 * <p>Column types are the strings returned by
 * {@link TableDefinition.ColumnDefinition#getType(StorageProviderType)}, so a single
 * classification here keeps the backup, restore and migration paths in agreement about
 * what is binary, integral or floating point.
 *
 * @author SlimefunCore Contributors
 */
public final class SqlDialect {

    private SqlDialect() {}

    /**
     * Quotes a table or column name for the given provider.
     *
     * <p>MySQL and MariaDB only read double quotes as identifiers when {@code ANSI_QUOTES}
     * is enabled, which cannot be assumed, so they get backticks.
     *
     * @param identifier The unquoted identifier
     * @param type       The provider the statement will run against
     * @return The quoted identifier, with any embedded quote character doubled
     */
    @Nonnull
    public static String quoteIdent(@Nonnull String identifier, @Nonnull StorageProviderType type) {
        if (type == StorageProviderType.MYSQL || type == StorageProviderType.MARIADB) {
            return '`' + identifier.replace("`", "``") + '`';
        }

        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * Binds a textual backup value to a statement parameter using the column's declared type.
     *
     * @param ps         The statement to bind into
     * @param index      The one based parameter index
     * @param raw        The value as read from the backup, or {@code null}
     * @param columnType The declared column type for the active provider
     * @throws SQLException If the value does not fit the column type
     */
    public static void bindValue(
            @Nonnull PreparedStatement ps,
            int index,
            @Nullable String raw,
            @Nonnull String columnType) throws SQLException {

        String type = columnType.toUpperCase(Locale.ROOT);

        if (raw == null) {
            ps.setNull(index, jdbcTypeOf(type));
            return;
        }

        if (isBinary(type)) {
            ps.setBytes(index, decodeHex(raw));
            return;
        }

        String trimmed = raw.trim();

        try {
            if (isIntegral(type)) {
                ps.setLong(index, Long.parseLong(trimmed));
                return;
            }

            if (isSinglePrecision(type)) {
                ps.setFloat(index, Float.parseFloat(trimmed));
                return;
            }

            if (isDoublePrecision(type)) {
                ps.setDouble(index, Double.parseDouble(trimmed));
                return;
            }
        } catch (NumberFormatException x) {
            throw new SQLException("Backup value \"" + trimmed + "\" is not valid for a " + type + " column", x);
        }

        ps.setString(index, raw);
    }

    /**
     * Copies one column of the current {@link ResultSet} row straight into a statement
     * parameter without any textual round trip.
     *
     * <p>Binary columns move as {@code byte[]}, which is what keeps serialized
     * {@code ItemStack} data byte-exact across a provider to provider copy.
     *
     * @param source     The result set positioned on a row
     * @param column     The column name to read
     * @param target     The statement to bind into
     * @param index      The one based parameter index
     * @param columnType The declared column type of the destination
     * @throws SQLException If the column cannot be read or bound
     */
    public static void transfer(
            @Nonnull ResultSet source,
            @Nonnull String column,
            @Nonnull PreparedStatement target,
            int index,
            @Nonnull String columnType) throws SQLException {

        String type = columnType.toUpperCase(Locale.ROOT);

        if (isBinary(type)) {
            byte[] bytes = source.getBytes(column);

            if (bytes == null) {
                target.setNull(index, Types.VARBINARY);
            } else {
                target.setBytes(index, bytes);
            }

            return;
        }

        if (isIntegral(type)) {
            long value = source.getLong(column);

            if (source.wasNull()) {
                target.setNull(index, Types.BIGINT);
            } else {
                target.setLong(index, value);
            }

            return;
        }

        if (isSinglePrecision(type)) {
            float value = source.getFloat(column);

            if (source.wasNull()) {
                target.setNull(index, Types.REAL);
            } else {
                target.setFloat(index, value);
            }

            return;
        }

        if (isDoublePrecision(type)) {
            double value = source.getDouble(column);

            if (source.wasNull()) {
                target.setNull(index, Types.DOUBLE);
            } else {
                target.setDouble(index, value);
            }

            return;
        }

        String value = source.getString(column);

        if (value == null) {
            target.setNull(index, Types.VARCHAR);
        } else {
            target.setString(index, value);
        }
    }

    /**
     * @param type An upper case column type
     * @return Whether the type stores raw bytes
     */
    public static boolean isBinary(@Nonnull String type) {
        return type.contains("BLOB") || type.contains("BYTEA") || type.contains("BINARY");
    }

    /**
     * @param type An upper case column type
     * @return Whether the type stores whole numbers
     */
    public static boolean isIntegral(@Nonnull String type) {
        return type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
            || type.startsWith("TINYINT") || type.startsWith("SERIAL");
    }

    /**
     * @param type An upper case column type
     * @return Whether the type stores single precision floating point values
     */
    public static boolean isSinglePrecision(@Nonnull String type) {
        return type.startsWith("REAL") || type.startsWith("FLOAT");
    }

    /**
     * @param type An upper case column type
     * @return Whether the type stores double precision floating point values
     */
    public static boolean isDoublePrecision(@Nonnull String type) {
        return type.startsWith("DOUBLE") || type.startsWith("NUMERIC") || type.startsWith("DECIMAL");
    }

    /**
     * Maps a declared column type onto the {@link Types} constant used for null binding.
     *
     * @param type An upper case column type
     * @return The matching {@link Types} constant
     */
    public static int jdbcTypeOf(@Nonnull String type) {
        if (isBinary(type)) {
            return Types.VARBINARY;
        }

        if (isIntegral(type)) {
            return Types.BIGINT;
        }

        if (isSinglePrecision(type)) {
            return Types.REAL;
        }

        if (isDoublePrecision(type)) {
            return Types.DOUBLE;
        }

        return Types.VARCHAR;
    }

    /**
     * Decodes the hexadecimal representation a binary column is stored as inside a JSON dump.
     *
     * @param hex The hexadecimal text
     * @return The decoded bytes
     * @throws SQLException If the text is not valid hexadecimal
     */
    @Nonnull
    public static byte[] decodeHex(@Nonnull String hex) throws SQLException {
        String value = hex.trim();

        if (value.isEmpty()) {
            return new byte[0];
        }

        if ((value.length() & 1) != 0) {
            throw new SQLException("Binary backup value has an odd number of hex digits");
        }

        byte[] bytes = new byte[value.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);

            if (high < 0 || low < 0) {
                throw new SQLException("Binary backup value is not valid hexadecimal");
            }

            bytes[i] = (byte) ((high << 4) | low);
        }

        return bytes;
    }
}
