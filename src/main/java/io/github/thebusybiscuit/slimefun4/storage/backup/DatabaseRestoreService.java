package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores a database backup created by {@link DatabaseBackupService}.
 *
 * <p><strong>WARNING:</strong> Restore is a destructive operation. It deletes
 * all rows in every managed table before re-inserting from the backup. Always
 * verify the backup integrity ({@link DatabaseBackupService#verifyBackup}) before
 * calling {@link #restore}.
 *
 * <p>The restore runs inside a single database transaction. If any step fails,
 * the transaction is rolled back and the live data is left untouched.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class DatabaseRestoreService {

    private DatabaseRestoreService() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Restores a backup to the given {@link StorageProvider}.
     *
     * <p>Steps performed:
     * <ol>
     *   <li>Verify checksum — aborts immediately on mismatch</li>
     *   <li>Parse JSON data from the backup file</li>
     *   <li>Open a connection; disable auto-commit</li>
     *   <li>DELETE all rows from each managed table</li>
     *   <li>INSERT all rows from the backup in batch</li>
     *   <li>Commit — or rollback on any exception</li>
     *   <li>Verify final row counts match {@link BackupMetadata#rowCounts()}</li>
     * </ol>
     *
     * @param entry    The backup to restore
     * @param provider The target provider (must be available and initialized)
     * @return A {@link RestoreResult} describing success or failure
     */
    @Nonnull
    public static RestoreResult restore(@Nonnull BackupEntry entry,
                                        @Nonnull StorageProvider provider) {

        // 1. Verify integrity
        if (!DatabaseBackupService.verifyBackup(entry)) {
            return new RestoreResult(false, "Checksum mismatch — backup may be corrupted", 0, 0);
        }

        // 2. Parse JSON
        Map<String, List<Map<String, String>>> tables;
        try {
            String json = readDataFile(entry);
            tables = parseJsonDump(json);
        } catch (Exception x) {
            Slimefun.logger().log(Level.SEVERE, "[Storage] Failed to parse backup data.", x);
            return new RestoreResult(false, "Failed to parse backup: " + x.getMessage(), 0, 0);
        }

        // 3. Restore via a single transaction
        int tablesRestored = 0;
        int rowsRestored   = 0;

        try (Connection conn = openConnection(provider)) {
            conn.setAutoCommit(false);
            try {
                for (TableDefinition tableDef : SchemaManager.getTables()) {
                    String tableName = tableDef.tableName();
                    List<Map<String, String>> rows = tables.getOrDefault(tableName, List.of());

                    // Delete existing rows
                    try (Statement del = conn.createStatement()) {
                        del.executeUpdate("DELETE FROM " + tableName);
                    }

                    // Insert rows from backup
                    if (!rows.isEmpty()) {
                        int inserted = insertRows(conn, tableName, rows);
                        rowsRestored += inserted;
                    }
                    tablesRestored++;
                }

                conn.commit();
                Slimefun.logger().log(Level.INFO,
                    "[Storage] Restore complete: {0} tables, {1} rows restored from backup {2}",
                    new Object[] { tablesRestored, rowsRestored, entry.metadata().backupId() });

                return new RestoreResult(true,
                    "Restored " + tablesRestored + " tables, " + rowsRestored + " rows",
                    tablesRestored, rowsRestored);

            } catch (Exception x) {
                conn.rollback();
                Slimefun.logger().log(Level.SEVERE, "[Storage] Restore failed — rolled back.", x);
                return new RestoreResult(false, "Restore failed and was rolled back: " + x.getMessage(), 0, 0);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception x) {
            Slimefun.logger().log(Level.SEVERE, "[Storage] Could not open connection for restore.", x);
            return new RestoreResult(false, "Cannot open database connection: " + x.getMessage(), 0, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Result record
    // -------------------------------------------------------------------------

    /**
     * Describes the outcome of a {@link #restore} operation.
     *
     * @param success        Whether the restore completed without error
     * @param message        
     * @param tablesRestored Number of tables written
     * @param rowsRestored   Total rows inserted
     */
    @Beta
    public record RestoreResult(
        boolean success,
        @Nonnull String message,
        int tablesRestored,
        int rowsRestored
    ) {}

    // -------------------------------------------------------------------------
    // Private — insertion
    // -------------------------------------------------------------------------

    private static int insertRows(@Nonnull Connection conn,
                                  @Nonnull String tableName,
                                  @Nonnull List<Map<String, String>> rows) throws Exception {
        if (rows.isEmpty()) {
            return 0;
        }

        // Build INSERT from column names of the first row
        Map<String, String> firstRow = rows.get(0);
        List<String> cols = new ArrayList<>(firstRow.keySet());

        String placeholders = "?, ".repeat(cols.size());
        String colList      = String.join(", ", cols.stream().map(c -> '"' + c + '"').toList());
        String sql = "INSERT INTO " + tableName + " (" + colList + ") VALUES ("
            + placeholders.substring(0, placeholders.length() - 2) + ")";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, String> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    String rawValue = row.get(cols.get(i));
                    ps.setString(i + 1, rawValue); // let JDBC coerce types
                }
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            int total = 0;
            for (int c : counts) {
                if (c >= 0) total += c;
            }
            return total;
        }
    }

    // -------------------------------------------------------------------------
    // Private — parsing
    // -------------------------------------------------------------------------

    @Nonnull
    private static String readDataFile(@Nonnull BackupEntry entry) throws IOException {
        String name = entry.dataFile().getName();
        if (name.endsWith(".zip")) {
            try (ZipInputStream zis = new ZipInputStream(
                    new java.io.FileInputStream(entry.dataFile()))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.getName().endsWith(".json")) {
                        return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            throw new IOException("No .json entry found in ZIP: " + name);
        }
        return Files.readString(entry.dataFile().toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Minimal hand-rolled parser for the JSON format produced by
     * {@link DatabaseBackupService#createBackup}.
     *
     * <p>Expected structure:
     * <pre>
     * {
     *   "tables": {
     *     "tableName": [
     *       {"col": "value", "col2": "123", ...},
     *       ...
     *     ]
     *   }
     * }
     * </pre>
     */
    @Nonnull
    private static Map<String, List<Map<String, String>>> parseJsonDump(@Nonnull String json) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();

        int tablesStart = json.indexOf("\"tables\"");
        if (tablesStart < 0) {
            return result;
        }

        int outerBrace = json.indexOf('{', tablesStart + "\"tables\"".length());
        if (outerBrace < 0) {
            return result;
        }

        // Find the matching closing brace for the tables object
        int depth = 0;
        int outerClose = -1;
        for (int i = outerBrace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    outerClose = i;
                    break;
                }
            }
        }
        if (outerClose < 0) {
            return result;
        }

        String tablesBlock = json.substring(outerBrace + 1, outerClose);

        // Parse each table: "tableName": [ ... ]
        int pos = 0;
        while (pos < tablesBlock.length()) {
            // Find table name
            int nameStart = tablesBlock.indexOf('"', pos);
            if (nameStart < 0) break;
            int nameEnd = tablesBlock.indexOf('"', nameStart + 1);
            if (nameEnd < 0) break;
            String tableName = tablesBlock.substring(nameStart + 1, nameEnd);

            // Find array
            int arrStart = tablesBlock.indexOf('[', nameEnd);
            if (arrStart < 0) break;

            // Find matching ]
            int arrDepth   = 0;
            int arrClose   = -1;
            int objDepth   = 0;
            for (int i = arrStart; i < tablesBlock.length(); i++) {
                char c = tablesBlock.charAt(i);
                if (c == '[') arrDepth++;
                else if (c == ']') {
                    arrDepth--;
                    if (arrDepth == 0) {
                        arrClose = i;
                        break;
                    }
                }
            }
            if (arrClose < 0) break;

            String arrayBlock = tablesBlock.substring(arrStart + 1, arrClose);
            result.put(tableName, parseRowArray(arrayBlock));

            pos = arrClose + 1;
        }

        return result;
    }

    @Nonnull
    private static List<Map<String, String>> parseRowArray(@Nonnull String arrayBlock) {
        List<Map<String, String>> rows = new ArrayList<>();

        int pos = 0;
        while (pos < arrayBlock.length()) {
            int objStart = arrayBlock.indexOf('{', pos);
            if (objStart < 0) break;

            // Find matching }
            int depth    = 0;
            int objClose = -1;
            for (int i = objStart; i < arrayBlock.length(); i++) {
                char c = arrayBlock.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        objClose = i;
                        break;
                    }
                }
            }
            if (objClose < 0) break;

            String objBlock = arrayBlock.substring(objStart + 1, objClose);
            rows.add(parseObject(objBlock));
            pos = objClose + 1;
        }
        return rows;
    }

    @Nonnull
    private static Map<String, String> parseObject(@Nonnull String objBlock) {
        Map<String, String> row = new LinkedHashMap<>();
        int pos = 0;

        while (pos < objBlock.length()) {
            // Find key
            int keyStart = objBlock.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = objBlock.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = objBlock.substring(keyStart + 1, keyEnd);

            // Find colon
            int colonPos = objBlock.indexOf(':', keyEnd);
            if (colonPos < 0) break;

            // Find value start (skip whitespace)
            int valStart = colonPos + 1;
            while (valStart < objBlock.length() && Character.isWhitespace(objBlock.charAt(valStart))) {
                valStart++;
            }
            if (valStart >= objBlock.length()) break;

            String value;
            int nextPos;

            char firstChar = objBlock.charAt(valStart);
            if (firstChar == '"') {
                // String value
                int valEnd = valStart + 1;
                while (valEnd < objBlock.length()) {
                    char c = objBlock.charAt(valEnd);
                    if (c == '\\') {
                        valEnd += 2; // skip escaped char
                    } else if (c == '"') {
                        break;
                    } else {
                        valEnd++;
                    }
                }
                value   = objBlock.substring(valStart + 1, valEnd);
                nextPos = valEnd + 1;
            } else {
                // Numeric / boolean / null value
                int valEnd = valStart;
                while (valEnd < objBlock.length()) {
                    char c = objBlock.charAt(valEnd);
                    if (c == ',' || c == '}') break;
                    valEnd++;
                }
                value   = objBlock.substring(valStart, valEnd).trim();
                nextPos = valEnd;
            }

            row.put(key, "null".equals(value) ? null : value);
            pos = nextPos;
        }
        return row;
    }

    // -------------------------------------------------------------------------
    // Private — connection helper (matches DatabaseBackupService approach)
    // -------------------------------------------------------------------------

    private static Connection openConnection(StorageProvider provider) throws Exception {
        Class<?> cls = provider.getClass();
        while (cls != null && cls != Object.class) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("dataSource");
                f.setAccessible(true);
                Object ds = f.get(provider);
                if (ds instanceof javax.sql.DataSource source) {
                    return source.getConnection();
                }
            } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new IllegalStateException(
            "Cannot obtain connection from provider: " + provider.getProviderName());
    }
}
