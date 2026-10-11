package io.github.thebusybiscuit.slimefun4.storage.schema;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dialect helper the backup, restore and migration paths all share.
 *
 * <p>These rules used to exist three times over as private copies. A regression in any of
 * them would corrupt identifiers or bind an item blob as text, so they are pinned here.
 */
class TestSqlDialect {

    @Test
    @DisplayName("MySQL and MariaDB get backticks, every other provider gets double quotes")
    void quotingMatchesTheProvider() {
        assertEquals("`uuid`", SqlDialect.quoteIdent("uuid", StorageProviderType.MYSQL));
        assertEquals("`uuid`", SqlDialect.quoteIdent("uuid", StorageProviderType.MARIADB));
        assertEquals("\"uuid\"", SqlDialect.quoteIdent("uuid", StorageProviderType.POSTGRESQL));
        assertEquals("\"uuid\"", SqlDialect.quoteIdent("uuid", StorageProviderType.SQLITE));
        assertEquals("\"uuid\"", SqlDialect.quoteIdent("uuid", StorageProviderType.LEGACY));
    }

    @Test
    @DisplayName("An embedded quote character is doubled rather than escaped away")
    void embeddedQuotesAreDoubled() {
        assertEquals("`we``ird`", SqlDialect.quoteIdent("we`ird", StorageProviderType.MYSQL));
        assertEquals("\"we\"\"ird\"", SqlDialect.quoteIdent("we\"ird", StorageProviderType.POSTGRESQL));
        assertEquals("\"back`tick\"", SqlDialect.quoteIdent("back`tick", StorageProviderType.SQLITE));
        assertEquals("`double\"quote`", SqlDialect.quoteIdent("double\"quote", StorageProviderType.MYSQL));
    }

    @Test
    @DisplayName("Every managed column type is classified into exactly one family")
    void everyManagedColumnTypeIsClassified() {
        for (StorageProviderType type : StorageProviderType.values()) {
            if (type == StorageProviderType.LEGACY) {
                continue;
            }

            for (TableDefinition table : SchemaManager.getTables()) {
                for (TableDefinition.ColumnDefinition column : table.columns()) {
                    String declared = column.getType(type).toUpperCase(java.util.Locale.ROOT);
                    int families = 0;

                    families += SqlDialect.isBinary(declared) ? 1 : 0;
                    families += SqlDialect.isIntegral(declared) ? 1 : 0;
                    families += SqlDialect.isSinglePrecision(declared) ? 1 : 0;
                    families += SqlDialect.isDoublePrecision(declared) ? 1 : 0;

                    assertTrue(families <= 1,
                        table.tableName() + '.' + column.name() + " on " + type
                            + " is declared " + declared + " and matches " + families + " families");

                    if ("item_data".equals(column.name())) {
                        assertTrue(SqlDialect.isBinary(declared),
                            "item_data must be binary on " + type + " or item bytes become text");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("The binary family covers every dialect's blob spelling")
    void binaryFamilyCoversEveryDialect() {
        assertTrue(SqlDialect.isBinary("BLOB"));
        assertTrue(SqlDialect.isBinary("MEDIUMBLOB"));
        assertTrue(SqlDialect.isBinary("BYTEA"));
        assertTrue(SqlDialect.isBinary("VARBINARY(255)"));
        assertFalse(SqlDialect.isBinary("TEXT"));
        assertFalse(SqlDialect.isBinary("VARCHAR(36)"));
    }

    @Test
    @DisplayName("Numeric families are separated so a float column never binds as a double")
    void numericFamiliesAreSeparate() {
        assertTrue(SqlDialect.isIntegral("INT"));
        assertTrue(SqlDialect.isIntegral("INTEGER"));
        assertTrue(SqlDialect.isIntegral("BIGINT"));
        assertTrue(SqlDialect.isIntegral("SMALLINT"));
        assertTrue(SqlDialect.isIntegral("SERIAL"));
        assertFalse(SqlDialect.isIntegral("DOUBLE"));

        assertTrue(SqlDialect.isSinglePrecision("REAL"));
        assertTrue(SqlDialect.isSinglePrecision("FLOAT"));
        assertFalse(SqlDialect.isSinglePrecision("DOUBLE PRECISION"));

        assertTrue(SqlDialect.isDoublePrecision("DOUBLE"));
        assertTrue(SqlDialect.isDoublePrecision("DOUBLE PRECISION"));
        assertTrue(SqlDialect.isDoublePrecision("NUMERIC"));
        assertTrue(SqlDialect.isDoublePrecision("DECIMAL(10,2)"));
        assertFalse(SqlDialect.isDoublePrecision("REAL"));
    }

    @Test
    @DisplayName("Null binding picks the JDBC type the column family expects")
    void nullBindingPicksTheColumnFamily() {
        assertEquals(Types.VARBINARY, SqlDialect.jdbcTypeOf("MEDIUMBLOB"));
        assertEquals(Types.BIGINT, SqlDialect.jdbcTypeOf("INTEGER"));
        assertEquals(Types.REAL, SqlDialect.jdbcTypeOf("REAL"));
        assertEquals(Types.DOUBLE, SqlDialect.jdbcTypeOf("DOUBLE PRECISION"));
        assertEquals(Types.VARCHAR, SqlDialect.jdbcTypeOf("TEXT"));
    }

    @Test
    @DisplayName("Hex decoding round trips every byte value and rejects malformed input")
    void hexDecodingIsExact() throws SQLException {
        byte[] payload = new byte[256];
        StringBuilder hex = new StringBuilder();

        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
            hex.append(String.format("%02x", i));
        }

        assertArrayEquals(payload, SqlDialect.decodeHex(hex.toString()));
        assertArrayEquals(new byte[0], SqlDialect.decodeHex("   "));
        assertArrayEquals(new byte[] {(byte) 0xAB}, SqlDialect.decodeHex("AB"));

        assertThrows(SQLException.class, () -> SqlDialect.decodeHex("abc"));
        assertThrows(SQLException.class, () -> SqlDialect.decodeHex("zz"));
    }
}
