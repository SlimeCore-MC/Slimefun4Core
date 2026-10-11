package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates, lists, verifies, and purges database backups.
 *
 * <p>Backups are stored in the directory configured by {@code backup.directory}
 * in {@code database.yml}. Each backup consists of two files:
 * <ul>
 *   <li>{@code <id>.json} / {@code <id>.zip} — the row dump or file archive</li>
 *   <li>{@code <id>.meta.json} — the {@link BackupMetadata}</li>
 * </ul>
 *
 * <p>SQL providers produce a provider-agnostic JSON row dump. The legacy YAML
 * backend produces a byte-exact ZIP of its file tree instead, because it has no
 * JDBC connection to read from.
 *
 * <p>A backup that could not read its source never becomes a file on disk, and a
 * failed or empty backup never triggers retention.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class DatabaseBackupService {

    private static final String DEFAULT_DIRECTORY = "data-storage/Slimefun/backups";
    private static final int LEGACY_RETENTION_CAP = 5;
    private static final int MINIMUM_RETAINED = 1;

    private static volatile boolean legacyRetentionWarned = false;

    private DatabaseBackupService() {}

    /**
     * Creates a backup of the current storage state.
     *
     * @param provider  The active storage provider (must be available)
     * @param dbConfig  The parsed {@code database.yml}
     * @param automatic {@code true} if triggered by the scheduler, {@code false} for manual
     * @return A {@link BackupEntry} describing the newly created backup
     * @throws IOException If the source could not be read or the archive not written
     */
    @Nonnull
    public static BackupEntry createBackup(
            @Nonnull StorageProvider provider,
            @Nonnull Config dbConfig,
            boolean automatic) throws IOException {

        return createBackup(
            provider,
            resolveDirectory(dbConfig),
            dbConfig.getBoolean("backup.compress"),
            dbConfig.getInt("backup.keep-last"),
            automatic);
    }

    /**
     * Creates a backup with explicit settings, bypassing {@code database.yml}.
     *
     * <p>Used for pre-restore snapshots, where retention must never remove anything.
     *
     * @param provider  The active storage provider
     * @param directory The directory to write the backup into
     * @param compress  Whether a SQL dump should be zipped
     * @param keepLast  Retention limit, or {@code 0} to skip retention entirely
     * @param automatic {@code true} if this was not triggered by an operator
     * @return A {@link BackupEntry} describing the newly created backup
     * @throws IOException If the source could not be read or the archive not written
     */
    @Nonnull
    public static BackupEntry createBackup(
            @Nonnull StorageProvider provider,
            @Nonnull File directory,
            boolean compress,
            int keepLast,
            boolean automatic) throws IOException {

        return createBackup(
            provider,
            directory,
            compress,
            keepLast,
            automatic,
            provider.supportsSql() ? BackupFormat.SQL_JSON : BackupFormat.LEGACY_FILES);
    }

    /**
     * Creates a backup in an explicitly chosen format.
     *
     * <p>Only {@link BackupFormat#BLOCK_FILES} may be requested for a provider whose own
     * format differs, because block data lives in files on every provider. Asking for a
     * SQL dump from a file backend, or a legacy file archive from a SQL backend, is a
     * programming error and is rejected.
     *
     * @param provider  The active storage provider
     * @param directory The directory to write the backup into
     * @param compress  Whether a SQL dump should be zipped
     * @param keepLast  Retention limit, or {@code 0} to skip retention entirely
     * @param automatic {@code true} if this was not triggered by an operator
     * @param format    The archive shape to produce
     * @return A {@link BackupEntry} describing the newly created backup
     * @throws IOException If the source could not be read or the archive not written
     */
    @Nonnull
    public static BackupEntry createBackup(
            @Nonnull StorageProvider provider,
            @Nonnull File directory,
            boolean compress,
            int keepLast,
            boolean automatic,
            @Nonnull BackupFormat format) throws IOException {

        if (format == BackupFormat.SQL_JSON && !provider.supportsSql()) {
            throw new IOException("Cannot take a SQL dump of " + provider.getProviderName());
        }

        if (format == BackupFormat.LEGACY_FILES && provider.supportsSql()) {
            throw new IOException("Cannot take a legacy file archive of " + provider.getProviderName());
        }

        if (format == BackupFormat.PLAYER_FILES && provider.supportsSql()) {
            throw new IOException("Cannot take a player file archive of " + provider.getProviderName());
        }

        Files.createDirectories(directory.toPath());

        String backupId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();

        Map<String, Integer> counts = new LinkedHashMap<>();
        File dataFile;

        switch (format) {
            case LEGACY_FILES -> {
                dataFile = new File(directory, backupId + ".zip");
                counts.putAll(LegacyFileBackup.writeArchive(dataFile, LegacyFileBackup.defaultRoot()));
            }
            case BLOCK_FILES -> {
                dataFile = new File(directory, backupId + ".zip");
                counts.putAll(BlockStorageBackup.writeArchive(dataFile, BlockStorageBackup.defaultRoot()));
            }
            case PLAYER_FILES -> {
                dataFile = new File(directory, backupId + ".zip");
                counts.putAll(PlayerFileBackup.writeArchive(dataFile, PlayerFileBackup.defaultRoot()));
            }
            default -> dataFile = writeSqlDump(provider, compress, directory, backupId, counts);
        }

        int totalEntries = counts.values().stream().mapToInt(Integer::intValue).sum();

        if (totalEntries <= 0) {
            Files.deleteIfExists(dataFile.toPath());
            throw new IOException("Backup aborted: no data was captured from " + provider.getProviderName());
        }

        String checksum = sha256Hex(dataFile);
        String version = resolveSlimefunVersion();

        BackupMetadata meta = new BackupMetadata(
            backupId, provider.getProviderName(), createdAt, counts, checksum, version, automatic, format);

        File metaFile = new File(directory, backupId + ".meta.json");
        Files.writeString(metaFile.toPath(), serializeMetadata(meta), StandardCharsets.UTF_8);

        log(Level.INFO, "[Storage] Backup created: {0} ({1} entries, {2}, checksum {3})",
            backupId, totalEntries, format.getDisplayName(), shortChecksum(checksum));

        purgeOldBackups(directory, keepLast, format);

        return new BackupEntry(dataFile, meta);
    }

    /**
     * Lists all backups found in the configured backup directory, newest first.
     *
     * @param dbConfig The parsed {@code database.yml}
     * @return An ordered list of backups
     */
    @Nonnull
    public static List<BackupEntry> listBackups(@Nonnull Config dbConfig) {
        return listBackups(resolveDirectory(dbConfig));
    }

    /**
     * Lists all backups found in the given directory, newest first.
     *
     * @param directory The backup directory
     * @return An ordered list of backups
     */
    @Nonnull
    public static List<BackupEntry> listBackups(@Nonnull File directory) {
        if (!directory.isDirectory()) {
            return List.of();
        }

        File[] metaFiles = directory.listFiles((d, name) -> name.endsWith(".meta.json"));

        if (metaFiles == null) {
            return List.of();
        }

        List<BackupEntry> entries = new ArrayList<>();

        for (File metaFile : metaFiles) {
            try {
                BackupMetadata meta = parseMetadata(Files.readString(metaFile.toPath(), StandardCharsets.UTF_8));
                entries.add(new BackupEntry(resolveDataFile(directory, meta), meta));
            } catch (Exception x) {
                log(Level.WARNING, "[Storage] Skipping unreadable backup meta: {0}", metaFile.getName());
            }
        }

        entries.sort(Comparator.comparing((BackupEntry e) -> e.metadata().createdAt()).reversed());
        return entries;
    }

    /**
     * Deletes a backup, both the data file and its metadata sidecar.
     *
     * @param entry The backup to delete
     */
    public static void deleteBackup(@Nonnull BackupEntry entry) {
        File dataFile = entry.dataFile();
        File directory = dataFile.getParentFile();

        deleteQuietly(dataFile);

        if (directory != null) {
            deleteQuietly(new File(directory, entry.metadata().backupId() + ".meta.json"));
        }
    }

    /**
     * Verifies the integrity of a backup by recomputing its SHA-256 checksum.
     *
     * @param entry The backup to verify
     * @return {@code true} if the file exists, is non-empty and matches its checksum
     */
    public static boolean verifyBackup(@Nonnull BackupEntry entry) {
        if (!entry.exists() || entry.metadata().isEmpty()) {
            return false;
        }

        try {
            return sha256Hex(entry.dataFile()).equalsIgnoreCase(entry.metadata().checksum());
        } catch (IOException x) {
            return false;
        }
    }

    /**
     * Resolves the configured backup directory.
     *
     * @param dbConfig The parsed {@code database.yml}
     * @return The backup directory, never {@code null}
     */
    @Nonnull
    public static File resolveDirectory(@Nonnull Config dbConfig) {
        String configured = dbConfig.getString("backup.directory");

        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_DIRECTORY;
        }

        return new File(configured);
    }

    @Nonnull
    private static File resolveDataFile(@Nonnull File directory, @Nonnull BackupMetadata meta) {
        File zip = new File(directory, meta.backupId() + ".zip");
        return zip.exists() ? zip : new File(directory, meta.backupId() + ".json");
    }

    /**
     * Writes a provider-agnostic JSON row dump to an explicit file.
     *
     * <p>This is the row-dump writer used by {@link #createBackup}, exposed so that a caller
     * owning its own directory layout — the checkpoint system — can place the dump at a fixed
     * path instead of the flat {@code <id>.json} naming used by the backup directory.
     *
     * <p>Only Slimefun's own managed tables are read, so a dump can never contain another
     * plugin's data, Slimefun block files or Minecraft world data.
     *
     * @param provider The active storage provider, which must support SQL
     * @param target   The file to write, created along with its parent directories
     * @param compress {@code true} to write a ZIP holding one JSON entry
     * @return A map of table name to the number of rows captured for it
     * @throws IOException If the database could not be read or the file not written
     */
    @Nonnull
    public static Map<String, Integer> writeSqlDump(
            @Nonnull StorageProvider provider,
            @Nonnull File target,
            boolean compress) throws IOException {

        if (!provider.supportsSql()) {
            throw new IOException("Cannot take a SQL dump of " + provider.getProviderName());
        }

        File parent = target.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        String json = buildJsonDump(provider, counts);

        if (compress) {
            writeZip(target, stripExtension(target.getName()) + ".json", json);
        } else {
            Files.writeString(target.toPath(), json, StandardCharsets.UTF_8);
        }

        return counts;
    }

    @Nonnull
    private static File writeSqlDump(
            @Nonnull StorageProvider provider,
            boolean compress,
            @Nonnull File directory,
            @Nonnull String backupId,
            @Nonnull Map<String, Integer> countsOut) throws IOException {

        File dataFile = new File(directory, backupId + (compress ? ".zip" : ".json"));
        countsOut.putAll(writeSqlDump(provider, dataFile, compress));
        return dataFile;
    }

    @Nonnull
    private static String stripExtension(@Nonnull String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    @Nonnull
    private static String buildJsonDump(
            @Nonnull StorageProvider provider,
            @Nonnull Map<String, Integer> rowCountsOut) throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"tables\": {\n");

        List<TableDefinition> tables = SchemaManager.getTables();
        boolean firstTable = true;

        try (Connection conn = openConnection(provider)) {
            for (TableDefinition table : tables) {
                if (!firstTable) {
                    sb.append(",\n");
                }

                firstTable = false;
                sb.append("    \"").append(table.tableName()).append("\": [\n");
                rowCountsOut.put(table.tableName(), dumpTable(conn, table, sb));
                sb.append("\n    ]");
            }
        } catch (SQLException x) {
            throw new IOException("Could not read database for backup: " + x.getMessage(), x);
        }

        sb.append("\n  }\n}");
        return sb.toString();
    }

    private static int dumpTable(
            @Nonnull Connection conn,
            @Nonnull TableDefinition table,
            @Nonnull StringBuilder sb) throws SQLException {

        int rowCount = 0;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + table.tableName())) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            while (rs.next()) {
                if (rowCount > 0) {
                    sb.append(",\n");
                }

                rowCount++;
                sb.append("      {");

                for (int i = 1; i <= colCount; i++) {
                    if (i > 1) {
                        sb.append(", ");
                    }

                    sb.append('"').append(escapeJson(meta.getColumnName(i))).append("\": ");
                    appendJsonValue(sb, rs.getObject(i));
                }

                sb.append('}');
            }
        }

        return rowCount;
    }

    @Nonnull
    private static Connection openConnection(@Nonnull StorageProvider provider) throws SQLException {
        Optional<Connection> connection = provider.borrowConnection();

        return connection.orElseThrow(() -> new SQLException(
            "Storage provider serves no JDBC connections: " + provider.getProviderName()));
    }

    static void purgeOldBackups(
            @Nonnull File directory,
            int keepLast,
            @Nonnull BackupFormat format) {

        if (keepLast <= 0) {
            return;
        }

        int effective = Math.max(MINIMUM_RETAINED, keepLast);

        if (format != BackupFormat.SQL_JSON && effective > LEGACY_RETENTION_CAP) {
            effective = LEGACY_RETENTION_CAP;
            warnLegacyRetentionOnce(keepLast, effective);
        }

        List<BackupEntry> healthy = new ArrayList<>();

        for (BackupEntry entry : listBackups(directory)) {
            if (entry.metadata().format() == format && entry.exists() && !entry.metadata().isEmpty()) {
                healthy.add(entry);
            }
        }

        if (healthy.size() <= effective) {
            return;
        }

        for (BackupEntry stale : healthy.subList(effective, healthy.size())) {
            deleteBackup(stale);
            log(Level.INFO, "[Storage] Purged old backup: {0}", stale.metadata().backupId());
        }
    }

    private static synchronized void warnLegacyRetentionOnce(int configured, int effective) {
        if (legacyRetentionWarned) {
            return;
        }

        legacyRetentionWarned = true;
        log(Level.INFO,
            "[Storage] File archives are large; retention reduced from {0} to {1} for file backups.",
            configured, effective);
    }

    private static void writeZip(@Nonnull File zipFile, @Nonnull String entryName, @Nonnull String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
        }
    }

    /**
     * Computes the SHA-256 checksum of a file as lowercase hexadecimal.
     *
     * @param file The file to hash
     * @return The hex encoded digest
     * @throws IOException If the file could not be read
     */
    @Nonnull
    public static String sha256Hex(@Nonnull File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }

            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException x) {
            throw new IOException("SHA-256 not available", x);
        }
    }

    @Nonnull
    private static String toHex(@Nonnull byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }

        return hex.toString();
    }

    @Nonnull
    private static String shortChecksum(@Nonnull String checksum) {
        return checksum.length() > 8 ? checksum.substring(0, 8) : checksum;
    }

    private static void deleteQuietly(@Nonnull File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException x) {
            log(Level.WARNING, "[Storage] Could not delete backup file: {0}", file.getName());
        }
    }

    @Nonnull
    private static String serializeMetadata(@Nonnull BackupMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"backupId\": \"").append(escapeJson(meta.backupId())).append("\",\n");
        sb.append("  \"provider\": \"").append(escapeJson(meta.provider())).append("\",\n");
        sb.append("  \"createdAt\": \"").append(meta.createdAt()).append("\",\n");
        sb.append("  \"slimefunVersion\": \"").append(escapeJson(meta.slimefunVersion())).append("\",\n");
        sb.append("  \"checksum\": \"").append(escapeJson(meta.checksum())).append("\",\n");
        sb.append("  \"format\": \"").append(meta.format().name()).append("\",\n");
        sb.append("  \"automatic\": ").append(meta.automatic()).append(",\n");
        sb.append("  \"rowCounts\": {\n");

        boolean first = true;

        for (Map.Entry<String, Integer> entry : meta.rowCounts().entrySet()) {
            if (!first) {
                sb.append(",\n");
            }

            first = false;
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": ").append(entry.getValue());
        }

        sb.append("\n  }\n}");
        return sb.toString();
    }

    @Nonnull
    private static BackupMetadata parseMetadata(@Nonnull String json) {
        String backupId = extractString(json, "backupId");
        String provider = extractString(json, "provider");
        String createdAt = extractString(json, "createdAt");
        String version = extractString(json, "slimefunVersion");
        String checksum = extractString(json, "checksum");
        boolean automatic = json.replace(" ", "").contains("\"automatic\":true");

        Map<String, Integer> rowCounts = parseRowCounts(json);
        Instant instant = Instant.EPOCH;

        if (createdAt != null) {
            try {
                instant = Instant.parse(createdAt);
            } catch (Exception ignored) {
            }
        }

        return new BackupMetadata(
            backupId != null ? backupId : "unknown",
            provider != null ? provider : "Unknown",
            instant,
            rowCounts,
            checksum != null ? checksum : "",
            version != null ? version : "?",
            automatic,
            BackupFormat.fromName(extractString(json, "format")));
    }

    @Nonnull
    private static Map<String, Integer> parseRowCounts(@Nonnull String json) {
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        int start = json.indexOf("\"rowCounts\"");

        if (start < 0) {
            return rowCounts;
        }

        int braceOpen = json.indexOf('{', start);
        int braceClose = json.indexOf('}', braceOpen);

        if (braceOpen < 0 || braceClose <= braceOpen) {
            return rowCounts;
        }

        for (String pair : json.substring(braceOpen + 1, braceClose).split(",")) {
            String trimmed = pair.trim();
            int colon = trimmed.lastIndexOf(':');

            if (colon < 0) {
                continue;
            }

            String key = trimmed.substring(0, colon).trim().replace("\"", "");

            try {
                rowCounts.put(key, Integer.parseInt(trimmed.substring(colon + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }

        return rowCounts;
    }

    @Nullable
    private static String extractString(@Nonnull String json, @Nonnull String key) {
        String needle = '"' + key + "\": \"";
        int start = json.indexOf(needle);

        if (start < 0) {
            return null;
        }

        start += needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    @Nonnull
    static String escapeJson(@Nonnull String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }

        return sb.toString();
    }

    @Nonnull
    static String unescapeJson(@Nonnull String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }

        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;

        while (i < value.length()) {
            char c = value.charAt(i++);

            if (c != '\\' || i >= value.length()) {
                sb.append(c);
                continue;
            }

            char escaped = value.charAt(i++);

            switch (escaped) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'u' -> {
                    if (i + 4 <= value.length()) {
                        try {
                            sb.append((char) Integer.parseInt(value.substring(i, i + 4), 16));
                            i += 4;
                        } catch (NumberFormatException x) {
                            sb.append(escaped);
                        }
                    } else {
                        sb.append(escaped);
                    }
                }
                default -> sb.append(escaped);
            }
        }

        return sb.toString();
    }

    private static void appendJsonValue(@Nonnull StringBuilder sb, @Nullable Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof byte[] bytes) {
            sb.append('"').append(toHex(bytes)).append('"');
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    @Nonnull
    private static String resolveSlimefunVersion() {
        Slimefun instance = Slimefun.instance();
        return instance == null ? "?" : instance.getDescription().getVersion();
    }

    private static void log(@Nonnull Level level, @Nonnull String message, @Nonnull Object... parameters) {
        Slimefun instance = Slimefun.instance();

        if (instance != null) {
            Slimefun.logger().log(level, message, parameters);
        }
    }
}
