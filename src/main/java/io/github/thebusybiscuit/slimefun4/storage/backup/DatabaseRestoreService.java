package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.Storage;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageConsistencyChecker;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.SqlDialect;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores a backup created by {@link DatabaseBackupService}.
 *
 * <p><strong>Restore is destructive.</strong> A {@link BackupFormat#SQL_JSON}
 * restore deletes every row of every managed table inside one transaction before
 * re-inserting from the backup, and rolls back as a whole on any failure. A
 * {@link BackupFormat#LEGACY_FILES} restore replaces the live YAML tree, a
 * {@link BackupFormat#BLOCK_FILES} restore replaces the block sections of it, and a
 * {@link BackupFormat#PLAYER_FILES} restore replaces the player sections of it. All three
 * file restores take an automatic pre-restore snapshot first.
 *
 * <p>A block restore is checked afterwards: every extracted file is re-read with
 * {@link StorageConsistencyChecker#scanBlockFiles(File)} and the restore reports failure if
 * any of them no longer parses, because a truncated file in the archive would otherwise only
 * surface as a missing machine on the next start.
 *
 * <p>Run a restore with no players online. Cached player data is invalidated
 * afterwards so nothing stale is written back over the restored state.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class DatabaseRestoreService {

    private DatabaseRestoreService() {}

    /**
     * Describes the outcome of a {@link #restore} operation.
     *
     * @param success        Whether the restore completed without error
     * @param message        A human readable summary, safe to show to an operator
     * @param tablesRestored Number of tables or file sections written
     * @param rowsRestored   Total rows or files written
     */
    @Beta
    public record RestoreResult(
        boolean success,
        @Nonnull String message,
        int tablesRestored,
        int rowsRestored
    ) {}

    /**
     * Restores a backup into the given {@link StorageProvider}.
     *
     * @param entry    The backup to restore
     * @param provider The target provider, which must be available and initialized
     * @return A {@link RestoreResult} describing success or failure
     */
    @Nonnull
    public static RestoreResult restore(@Nonnull BackupEntry entry, @Nonnull StorageProvider provider) {
        if (!entry.exists()) {
            return new RestoreResult(false, "Backup data file is missing on disk", 0, 0);
        }

        if (entry.metadata().isEmpty()) {
            return new RestoreResult(false, "Backup recorded no data and cannot be restored", 0, 0);
        }

        if (!DatabaseBackupService.verifyBackup(entry)) {
            return new RestoreResult(false, "Checksum mismatch, the backup may be corrupted", 0, 0);
        }

        BackupFormat format = entry.metadata().format();
        boolean sqlTarget = provider.supportsSql();

        if (format == BackupFormat.BLOCK_FILES) {
            return restoreBlocks(entry, provider);
        }

        if (format == BackupFormat.PLAYER_FILES) {
            if (sqlTarget) {
                return new RestoreResult(false,
                    "This is a player file archive and cannot be restored into " + provider.getProviderName(), 0, 0);
            }

            return restorePlayerFiles(entry, provider);
        }

        if (format == BackupFormat.LEGACY_FILES && sqlTarget) {
            return new RestoreResult(false,
                "This is a file archive and cannot be restored into " + provider.getProviderName(), 0, 0);
        }

        if (format == BackupFormat.SQL_JSON && !sqlTarget) {
            return new RestoreResult(false,
                "This is a SQL dump and cannot be restored into " + provider.getProviderName(), 0, 0);
        }

        return format == BackupFormat.LEGACY_FILES
            ? restoreLegacy(entry, provider)
            : restoreSql(entry, provider);
    }

    @Nonnull
    private static RestoreResult restoreBlocks(@Nonnull BackupEntry entry, @Nonnull StorageProvider provider) {
        File directory = entry.dataFile().getParentFile();

        if (directory != null) {
            try {
                BackupEntry snapshot = DatabaseBackupService.createBackup(
                    provider, directory, true, 0, true, BackupFormat.BLOCK_FILES);
                log(Level.INFO, "[Storage] Pre-restore block snapshot created: {0}", snapshot.metadata().backupId());
            } catch (IOException x) {
                return new RestoreResult(false,
                    "Aborted, the pre-restore block snapshot failed: " + describe(x), 0, 0);
            }
        }

        try {
            int files = BlockStorageBackup.extractArchive(entry.dataFile(), BlockStorageBackup.defaultRoot());
            int sections = entry.metadata().rowCounts().size();

            log(Level.INFO, "[Storage] Block restore complete: {0} files from backup {1}",
                files, entry.metadata().backupId());

            StorageConsistencyChecker.BlockScanResult scan =
                StorageConsistencyChecker.scanBlockFiles(BlockStorageBackup.defaultRoot());

            if (!scan.isHealthy()) {
                for (String path : scan.unreadable()) {
                    log(Level.WARNING, "[Storage] Restored block file does not parse: {0}", path);
                }

                return new RestoreResult(false,
                    "Restored " + files + " block files but " + scan.describe()
                        + ", see the console for the paths and restore an older archive",
                    sections, files);
            }

            log(Level.INFO, "[Storage] Block integrity after restore: {0}", scan.describe());

            return new RestoreResult(true,
                "Restored " + files + " block files, " + scan.describe()
                    + ", restart the server to reload them",
                sections, files);
        } catch (IOException x) {
            log(Level.SEVERE, "[Storage] Block restore failed: {0}", describe(x));
            return new RestoreResult(false, "Restore failed: " + describe(x), 0, 0);
        }
    }

    @Nonnull
    private static RestoreResult restoreLegacy(@Nonnull BackupEntry entry, @Nonnull StorageProvider provider) {
        File directory = entry.dataFile().getParentFile();

        if (directory != null) {
            try {
                BackupEntry snapshot = DatabaseBackupService.createBackup(provider, directory, true, 0, true);
                log(Level.INFO, "[Storage] Pre-restore snapshot created: {0}", snapshot.metadata().backupId());
            } catch (IOException x) {
                return new RestoreResult(false,
                    "Aborted, the pre-restore snapshot failed: " + describe(x), 0, 0);
            }
        }

        try {
            int files = LegacyFileBackup.extractArchive(entry.dataFile(), LegacyFileBackup.defaultRoot());
            int sections = entry.metadata().rowCounts().size();
            invalidateCaches();

            log(Level.INFO, "[Storage] Restore complete: {0} files from backup {1}",
                files, entry.metadata().backupId());

            return new RestoreResult(true,
                "Restored " + files + " files from the archive, restart the server to reload them",
                sections, files);
        } catch (IOException x) {
            log(Level.SEVERE, "[Storage] Legacy restore failed: {0}", describe(x));
            return new RestoreResult(false, "Restore failed: " + describe(x), 0, 0);
        }
    }

    @Nonnull
    private static RestoreResult restorePlayerFiles(@Nonnull BackupEntry entry, @Nonnull StorageProvider provider) {
        File directory = entry.dataFile().getParentFile();

        if (directory != null) {
            try {
                BackupEntry snapshot = DatabaseBackupService.createBackup(
                    provider, directory, true, 0, true, BackupFormat.PLAYER_FILES);
                log(Level.INFO, "[Storage] Pre-restore player snapshot created: {0}", snapshot.metadata().backupId());
            } catch (IOException x) {
                return new RestoreResult(false,
                    "Aborted, the pre-restore player snapshot failed: " + describe(x), 0, 0);
            }
        }

        return restorePlayerArchive(entry.dataFile(), entry.metadata().backupId(), provider);
    }

    /**
     * Restores a player file archive from an explicit file into the legacy backend.
     *
     * <p>Replaces the {@code Players} and {@code waypoints} sections only. Block data is left
     * untouched, and no pre-restore snapshot is taken here — the caller owns that decision.
     *
     * @param archive  The archive to extract
     * @param label    An identifier used in log lines, such as a backup or checkpoint id
     * @param provider The target provider, which must be the legacy backend
     * @return A {@link RestoreResult} describing success or failure
     */
    @Nonnull
    public static RestoreResult restorePlayerArchive(
            @Nonnull File archive,
            @Nonnull String label,
            @Nonnull StorageProvider provider) {

        if (provider.supportsSql()) {
            return new RestoreResult(false,
                "This is a player file archive and cannot be restored into " + provider.getProviderName(), 0, 0);
        }

        if (!archive.isFile()) {
            return new RestoreResult(false, "Player archive is missing on disk", 0, 0);
        }

        try {
            int files = PlayerFileBackup.extractArchive(archive, PlayerFileBackup.defaultRoot());
            invalidateCaches();

            log(Level.INFO, "[Storage] Player restore complete: {0} files from {1}", files, label);

            return new RestoreResult(true,
                "Restored " + files + " player files from the archive",
                PlayerFileBackup.getSections().size(), files);
        } catch (IOException x) {
            log(Level.SEVERE, "[Storage] Player restore failed: {0}", describe(x));
            return new RestoreResult(false, "Restore failed: " + describe(x), 0, 0);
        }
    }

    @Nonnull
    private static RestoreResult restoreSql(@Nonnull BackupEntry entry, @Nonnull StorageProvider provider) {
        return restoreSqlDump(entry.dataFile(), entry.metadata().backupId(), provider);
    }

    /**
     * Restores a JSON row dump from an explicit file into a SQL provider.
     *
     * <p>Every managed table is emptied and re-inserted inside one transaction, which is rolled
     * back as a whole on any failure. No pre-restore snapshot is taken here — the caller owns
     * that decision.
     *
     * @param dumpFile The {@code .json} or {@code .zip} row dump to read
     * @param label    An identifier used in log lines, such as a backup or checkpoint id
     * @param provider The target provider, which must support SQL
     * @return A {@link RestoreResult} describing success or failure
     */
    @Nonnull
    public static RestoreResult restoreSqlDump(
            @Nonnull File dumpFile,
            @Nonnull String label,
            @Nonnull StorageProvider provider) {

        if (!provider.supportsSql()) {
            return new RestoreResult(false,
                "This is a SQL dump and cannot be restored into " + provider.getProviderName(), 0, 0);
        }

        if (!dumpFile.isFile()) {
            return new RestoreResult(false, "Row dump is missing on disk", 0, 0);
        }

        Map<String, List<Map<String, String>>> tables;

        try {
            tables = parseJsonDump(readDataFile(dumpFile));
        } catch (Exception x) {
            log(Level.SEVERE, "[Storage] Failed to parse backup data: {0}", describe(x));
            return new RestoreResult(false, "Failed to parse backup: " + describe(x), 0, 0);
        }

        StorageProviderType type = provider.getProviderType();
        int tablesRestored = 0;
        int rowsRestored = 0;

        try (Connection conn = openConnection(provider)) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                for (TableDefinition table : SchemaManager.getTables()) {
                    try (Statement delete = conn.createStatement()) {
                        delete.executeUpdate("DELETE FROM " + SqlDialect.quoteIdent(table.tableName(), type));
                    }

                    rowsRestored += insertRows(conn, table, tables.getOrDefault(table.tableName(), List.of()), type);
                    tablesRestored++;
                }

                conn.commit();
            } catch (Exception x) {
                conn.rollback();
                log(Level.SEVERE, "[Storage] Restore failed and was rolled back: {0}", describe(x));
                return new RestoreResult(false, "Restore failed and was rolled back: " + describe(x), 0, 0);
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (Exception x) {
            log(Level.SEVERE, "[Storage] Could not open a connection for restore: {0}", describe(x));
            return new RestoreResult(false, "Cannot open database connection: " + describe(x), 0, 0);
        }

        invalidateCaches();
        log(Level.INFO, "[Storage] Restore complete: {0} tables, {1} rows from {2}",
            tablesRestored, rowsRestored, label);

        return new RestoreResult(true,
            "Restored " + tablesRestored + " tables and " + rowsRestored + " rows",
            tablesRestored, rowsRestored);
    }

    private static int insertRows(
            @Nonnull Connection conn,
            @Nonnull TableDefinition table,
            @Nonnull List<Map<String, String>> rows,
            @Nonnull StorageProviderType type) throws SQLException {

        if (rows.isEmpty()) {
            return 0;
        }

        Map<String, TableDefinition.ColumnDefinition> schema = new LinkedHashMap<>();

        for (TableDefinition.ColumnDefinition column : table.columns()) {
            schema.put(column.name().toLowerCase(Locale.ROOT), column);
        }

        List<Map<String, String>> normalized = new ArrayList<>(rows.size());
        Set<String> present = new LinkedHashSet<>();

        for (Map<String, String> row : rows) {
            Map<String, String> lowered = new LinkedHashMap<>();

            for (Map.Entry<String, String> cell : row.entrySet()) {
                String key = cell.getKey().toLowerCase(Locale.ROOT);

                if (schema.containsKey(key)) {
                    lowered.put(key, cell.getValue());
                    present.add(key);
                }
            }

            normalized.add(lowered);
        }

        if (present.isEmpty()) {
            return 0;
        }

        List<String> columns = new ArrayList<>(present);
        StringBuilder sql = new StringBuilder("INSERT INTO ")
            .append(SqlDialect.quoteIdent(table.tableName(), type))
            .append(" (");

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }

            sql.append(SqlDialect.quoteIdent(schema.get(columns.get(i)).name(), type));
        }

        sql.append(") VALUES (").append("?, ".repeat(columns.size() - 1)).append("?)");

        return executeBatch(conn, sql.toString(), columns, schema, normalized, type);
    }

    private static int executeBatch(
            @Nonnull Connection conn,
            @Nonnull String sql,
            @Nonnull List<String> columns,
            @Nonnull Map<String, TableDefinition.ColumnDefinition> schema,
            @Nonnull List<Map<String, String>> rows,
            @Nonnull StorageProviderType type) throws SQLException {

        int inserted = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map<String, String> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    String key = columns.get(i);
                    SqlDialect.bindValue(ps, i + 1, row.get(key), schema.get(key).getType(type));
                }

                ps.addBatch();
            }

            for (int count : ps.executeBatch()) {
                inserted += count > 0 ? count : 1;
            }
        }

        return inserted;
    }

    @Nonnull
    private static String readDataFile(@Nonnull File file) throws IOException {
        if (!file.getName().endsWith(".zip")) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry zipEntry;

            while ((zipEntry = zis.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && zipEntry.getName().endsWith(".json")) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }

                zis.closeEntry();
            }
        }

        throw new IOException("No JSON entry found inside " + file.getName());
    }

    @Nonnull
    private static Map<String, List<Map<String, String>>> parseJsonDump(@Nonnull String json) {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        int tablesKey = json.indexOf("\"tables\"");

        if (tablesKey < 0) {
            return result;
        }

        int outerOpen = json.indexOf('{', tablesKey + "\"tables\"".length());

        if (outerOpen < 0) {
            return result;
        }

        int outerClose = findMatching(json, outerOpen, '{', '}');

        if (outerClose < 0) {
            return result;
        }

        String block = json.substring(outerOpen + 1, outerClose);
        int pos = 0;

        while (pos < block.length()) {
            int nameStart = block.indexOf('"', pos);

            if (nameStart < 0) {
                break;
            }

            int nameEnd = findStringEnd(block, nameStart);

            if (nameEnd < 0) {
                break;
            }

            String tableName = DatabaseBackupService.unescapeJson(block.substring(nameStart + 1, nameEnd));
            int arrayOpen = block.indexOf('[', nameEnd);

            if (arrayOpen < 0) {
                break;
            }

            int arrayClose = findMatching(block, arrayOpen, '[', ']');

            if (arrayClose < 0) {
                break;
            }

            result.put(tableName, parseRowArray(block.substring(arrayOpen + 1, arrayClose)));
            pos = arrayClose + 1;
        }

        return result;
    }

    @Nonnull
    private static List<Map<String, String>> parseRowArray(@Nonnull String arrayBlock) {
        List<Map<String, String>> rows = new ArrayList<>();
        int pos = 0;

        while (pos < arrayBlock.length()) {
            int objectOpen = arrayBlock.indexOf('{', pos);

            if (objectOpen < 0) {
                break;
            }

            int objectClose = findMatching(arrayBlock, objectOpen, '{', '}');

            if (objectClose < 0) {
                break;
            }

            rows.add(parseObject(arrayBlock.substring(objectOpen + 1, objectClose)));
            pos = objectClose + 1;
        }

        return rows;
    }

    @Nonnull
    private static Map<String, String> parseObject(@Nonnull String objectBlock) {
        Map<String, String> row = new LinkedHashMap<>();
        int pos = 0;

        while (pos < objectBlock.length()) {
            int keyStart = objectBlock.indexOf('"', pos);

            if (keyStart < 0) {
                break;
            }

            int keyEnd = findStringEnd(objectBlock, keyStart);

            if (keyEnd < 0) {
                break;
            }

            String key = DatabaseBackupService.unescapeJson(objectBlock.substring(keyStart + 1, keyEnd));
            int colon = objectBlock.indexOf(':', keyEnd + 1);

            if (colon < 0) {
                break;
            }

            int valueStart = colon + 1;

            while (valueStart < objectBlock.length() && Character.isWhitespace(objectBlock.charAt(valueStart))) {
                valueStart++;
            }

            if (valueStart >= objectBlock.length()) {
                break;
            }

            if (objectBlock.charAt(valueStart) == '"') {
                int valueEnd = findStringEnd(objectBlock, valueStart);

                if (valueEnd < 0) {
                    break;
                }

                row.put(key, DatabaseBackupService.unescapeJson(objectBlock.substring(valueStart + 1, valueEnd)));
                pos = valueEnd + 1;
            } else {
                int valueEnd = valueStart;

                while (valueEnd < objectBlock.length()
                        && objectBlock.charAt(valueEnd) != ','
                        && objectBlock.charAt(valueEnd) != '}') {
                    valueEnd++;
                }

                String literal = objectBlock.substring(valueStart, valueEnd).trim();
                row.put(key, "null".equals(literal) ? null : literal);
                pos = valueEnd + 1;
            }
        }

        return row;
    }

    private static int findMatching(@Nonnull String text, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }

                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return i;
            }
        }

        return -1;
    }

    private static int findStringEnd(@Nonnull String text, int quoteIndex) {
        boolean escaped = false;

        for (int i = quoteIndex + 1; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }

        return -1;
    }

    @Nonnull
    private static Connection openConnection(@Nonnull StorageProvider provider) throws SQLException {
        Optional<Connection> connection = provider.borrowConnection();

        return connection.orElseThrow(() -> new SQLException(
            "Storage provider serves no JDBC connections: " + provider.getProviderName()));
    }

    /**
     * Drops every cached player entry so nothing stale is written back over restored data.
     *
     * @return The number of cached entries that were dropped
     */
    public static int invalidateCaches() {
        Slimefun instance = Slimefun.instance();

        if (instance == null) {
            return 0;
        }

        Storage storage = Slimefun.getPlayerStorage();

        if (storage instanceof StorageCache cache) {
            int dropped = cache.invalidateAll();
            log(Level.INFO, "[Storage] Invalidated {0} cached player entries after restore.", dropped);
            return dropped;
        }

        return 0;
    }

    @Nonnull
    private static String describe(@Nonnull Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    }

    private static void log(@Nonnull Level level, @Nonnull String message, @Nonnull Object... parameters) {
        if (Slimefun.instance() != null) {
            Slimefun.logger().log(level, message, parameters);
        }
    }
}
