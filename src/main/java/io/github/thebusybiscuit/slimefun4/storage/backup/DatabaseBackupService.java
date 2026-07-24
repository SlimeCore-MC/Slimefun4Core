package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code <id>.json} (or {@code .zip}) — the row dump</li>
 *   <li>{@code <id>.meta.json} — the {@link BackupMetadata}</li>
 * </ul>
 *
 * <p>The row dump is always a provider-agnostic JSON document with this
 * top-level structure:
 * <pre>
 * {
 *   "tables": {
 *     "player_researches": [ {"uuid":"...","research_id":1}, ... ],
 *     ...
 *   }
 * }
 * </pre>
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class DatabaseBackupService {

    private DatabaseBackupService() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates a backup of the current database state.
     *
     * @param provider  The active storage provider (must be available)
     * @param dbConfig  The parsed {@code database.yml}
     * @param automatic {@code true} if triggered by the scheduler, {@code false} for manual
     * @return A {@link BackupEntry} describing the newly created backup
     * @throws IOException If any I/O error occurs
     */
    @Nonnull
    public static BackupEntry createBackup(
            @Nonnull StorageProvider provider,
            @Nonnull Config dbConfig,
            boolean automatic) throws IOException {

        String backupDir = dbConfig.getString("backup.directory");
        if (backupDir == null || backupDir.isBlank()) {
            backupDir = "data-storage/Slimefun/backups";
        }
        boolean compress = dbConfig.getBoolean("backup.compress");

        File dir = new File(backupDir);
        Files.createDirectories(dir.toPath());

        String backupId   = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();

        // --- Build JSON row dump ---
        Map<String, Integer> rowCounts = new HashMap<>();
        String jsonContent = buildJsonDump(provider, rowCounts);

        // --- Write data file ---
        File dataFile;
        if (compress) {
            dataFile = new File(dir, backupId + ".zip");
            writeZip(dataFile, backupId + ".json", jsonContent);
        } else {
            dataFile = new File(dir, backupId + ".json");
            Files.writeString(dataFile.toPath(), jsonContent, StandardCharsets.UTF_8);
        }

        // --- Compute checksum ---
        String checksum = sha256Hex(dataFile);

        // --- Build + write metadata ---
        String sfVersion = Slimefun.instance().getDescription().getVersion();
        BackupMetadata meta = new BackupMetadata(
            backupId, provider.getProviderName(), createdAt,
            rowCounts, checksum, sfVersion, automatic);

        File metaFile = new File(dir, backupId + ".meta.json");
        Files.writeString(metaFile.toPath(), serializeMetadata(meta), StandardCharsets.UTF_8);

        Slimefun.logger().log(Level.INFO,
            "[Storage] Backup created: {0} ({1} total rows, checksum {2}...)",
            new Object[] { backupId, meta.totalRows(), checksum.substring(0, 8) });

        // --- Purge old backups ---
        int keepLast = dbConfig.getInt("backup.keep-last");
        if (keepLast > 0) {
            purgeOldBackups(dir, keepLast);
        }

        return new BackupEntry(dataFile, meta);
    }

    /**
     * Lists all backups found in the configured backup directory, sorted
     * newest-first.
     *
     * @param dbConfig The parsed {@code database.yml}
     * @return An ordered list of backups
     */
    @Nonnull
    public static List<BackupEntry> listBackups(@Nonnull Config dbConfig) {
        String backupDir = dbConfig.getString("backup.directory");
        if (backupDir == null || backupDir.isBlank()) {
            backupDir = "data-storage/Slimefun/backups";
        }

        File dir = new File(backupDir);
        if (!dir.isDirectory()) {
            return List.of();
        }

        File[] metaFiles = dir.listFiles((d, name) -> name.endsWith(".meta.json"));
        if (metaFiles == null) {
            return List.of();
        }

        List<BackupEntry> entries = new ArrayList<>();
        for (File metaFile : metaFiles) {
            try {
                String json  = Files.readString(metaFile.toPath(), StandardCharsets.UTF_8);
                BackupMetadata meta = parseMetadata(json);

                // Find the associated data file
                String id = meta.backupId();
                File dataFile = new File(dir, id + ".zip");
                if (!dataFile.exists()) {
                    dataFile = new File(dir, id + ".json");
                }
                entries.add(new BackupEntry(dataFile, meta));
            } catch (Exception x) {
                Slimefun.logger().log(Level.WARNING, x,
                    () -> "[Storage] Skipping unreadable backup meta: " + metaFile.getName());
            }
        }

        entries.sort(Comparator.comparing(e -> e.metadata().createdAt(), Comparator.reverseOrder()));
        return entries;
    }

    /**
     * Deletes a backup (both the data file and the {@code .meta.json}).
     *
     * @param entry The backup to delete
     */
    public static void deleteBackup(@Nonnull BackupEntry entry) {
        File dataFile = entry.dataFile();
        if (dataFile.exists()) {
            dataFile.delete();
        }
        String id = entry.metadata().backupId();
        File metaFile = new File(dataFile.getParentFile(), id + ".meta.json");
        if (metaFile.exists()) {
            metaFile.delete();
        }
    }

    /**
     * Verifies the integrity of a backup by recomputing its SHA-256 checksum.
     *
     * @param entry The backup to verify
     * @return {@code true} if the checksum matches the stored value
     */
    public static boolean verifyBackup(@Nonnull BackupEntry entry) {
        if (!entry.exists()) {
            return false;
        }
        try {
            String actual = sha256Hex(entry.dataFile());
            return actual.equalsIgnoreCase(entry.metadata().checksum());
        } catch (IOException x) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private — JSON dump
    // -------------------------------------------------------------------------

    @Nonnull
    private static String buildJsonDump(
            @Nonnull StorageProvider provider,
            @Nonnull Map<String, Integer> rowCountsOut) {

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"tables\": {\n");

        List<TableDefinition> tables = SchemaManager.getTables();
        boolean firstTable = true;

        try (Connection conn = openConnection(provider)) {
            for (TableDefinition table : tables) {
                if (!firstTable) sb.append(",\n");
                firstTable = false;

                sb.append("    \"").append(table.tableName()).append("\": [\n");

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM " + table.tableName())) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int colCount = meta.getColumnCount();
                    boolean firstRow = true;
                    int rowCount = 0;

                    while (rs.next()) {
                        if (!firstRow) sb.append(",\n");
                        firstRow = false;
                        rowCount++;

                        sb.append("      {");
                        for (int i = 1; i <= colCount; i++) {
                            if (i > 1) sb.append(", ");
                            String colName = meta.getColumnName(i);
                            Object value   = rs.getObject(i);
                            sb.append('"').append(escapeJson(colName)).append("\": ");
                            appendJsonValue(sb, value);
                        }
                        sb.append("}");
                    }

                    rowCountsOut.put(table.tableName(), rowCount);
                } catch (Exception x) {
                    Slimefun.logger().log(Level.WARNING, x,
                        () -> "[Storage] Failed to dump table: " + table.tableName());
                    rowCountsOut.put(table.tableName(), 0);
                }

                sb.append("\n    ]");
            }
        } catch (Exception x) {
            Slimefun.logger().log(Level.SEVERE, "[Storage] Failed to open connection for backup.", x);
        }

        sb.append("\n  }\n}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private — IO helpers
    // -------------------------------------------------------------------------

    private static void writeZip(@Nonnull File zipFile,
                                 @Nonnull String entryName,
                                 @Nonnull String content) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry(entryName));
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
        }
    }

    @Nonnull
    private static String sha256Hex(@Nonnull File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException x) {
            throw new IOException("SHA-256 not available", x);
        }
    }

    private static void purgeOldBackups(@Nonnull File dir, int keepLast) {
        File[] metaFiles = dir.listFiles((d, name) -> name.endsWith(".meta.json"));
        if (metaFiles == null || metaFiles.length <= keepLast) {
            return;
        }

        // Sort oldest first (by last modified timestamp on the meta file)
        Arrays.sort(metaFiles, Comparator.comparingLong(File::lastModified));

        int toDelete = metaFiles.length - keepLast;
        for (int i = 0; i < toDelete; i++) {
            File metaFile = metaFiles[i];
            String name   = metaFile.getName().replace(".meta.json", "");
            File jsonFile = new File(dir, name + ".json");
            File zipFile  = new File(dir, name + ".zip");
            metaFile.delete();
            if (jsonFile.exists()) jsonFile.delete();
            if (zipFile.exists()) zipFile.delete();
            Slimefun.logger().log(Level.INFO,
                "[Storage] Purged old backup: {0}", name);
        }
    }

    // -------------------------------------------------------------------------
    // Private — JSON helpers (no external library)
    // -------------------------------------------------------------------------

    @Nonnull
    private static String serializeMetadata(@Nonnull BackupMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"backupId\": ").append('"').append(escapeJson(meta.backupId())).append("\",\n");
        sb.append("  \"provider\": ").append('"').append(escapeJson(meta.provider())).append("\",\n");
        sb.append("  \"createdAt\": ").append('"').append(meta.createdAt().toString()).append("\",\n");
        sb.append("  \"slimefunVersion\": ").append('"').append(escapeJson(meta.slimefunVersion())).append("\",\n");
        sb.append("  \"checksum\": ").append('"').append(escapeJson(meta.checksum())).append("\",\n");
        sb.append("  \"automatic\": ").append(meta.automatic()).append(",\n");
        sb.append("  \"rowCounts\": {\n");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : meta.rowCounts().entrySet()) {
            if (!first) sb.append(",\n");
            first = false;
            sb.append("    \"").append(escapeJson(entry.getKey())).append("\": ").append(entry.getValue());
        }
        sb.append("\n  }\n}");
        return sb.toString();
    }

    @Nonnull
    private static BackupMetadata parseMetadata(@Nonnull String json) {
        // Minimal hand-rolled parser for the simple structure we write in serializeMetadata
        String backupId  = extractString(json, "backupId");
        String provider  = extractString(json, "provider");
        String createdAt = extractString(json, "createdAt");
        String sfVersion = extractString(json, "slimefunVersion");
        String checksum  = extractString(json, "checksum");
        boolean auto     = json.contains("\"automatic\": true");

        Map<String, Integer> rowCounts = new HashMap<>();
        int rcStart = json.indexOf("\"rowCounts\"");
        if (rcStart >= 0) {
            int braceOpen  = json.indexOf('{', rcStart);
            int braceClose = json.indexOf('}', braceOpen);
            if (braceOpen >= 0 && braceClose > braceOpen) {
                String rcBlock = json.substring(braceOpen + 1, braceClose);
                for (String kv : rcBlock.split(",")) {
                    kv = kv.trim();
                    int colon = kv.lastIndexOf(':');
                    if (colon < 0) continue;
                    String key = kv.substring(0, colon).trim().replace("\"", "");
                    String val = kv.substring(colon + 1).trim();
                    try {
                        rowCounts.put(key, Integer.parseInt(val.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        return new BackupMetadata(
            backupId != null ? backupId : "unknown",
            provider != null ? provider : "Unknown",
            createdAt != null ? Instant.parse(createdAt) : Instant.EPOCH,
            rowCounts, checksum != null ? checksum : "",
            sfVersion != null ? sfVersion : "?", auto);
    }

    @Nonnull
    private static String escapeJson(@Nonnull String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void appendJsonValue(@Nonnull StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof byte[] bytes) {
            // Binary data — encode as hex string
            StringBuilder hex = new StringBuilder(bytes.length * 2 + 2);
            hex.append('"');
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            hex.append('"');
            sb.append(hex);
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    @Nonnull
    private static String extractString(@Nonnull String json, @Nonnull String key) {
        String needle = '"' + key + "\": \"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    // -------------------------------------------------------------------------
    // Private — connection helper
    // -------------------------------------------------------------------------

    /**
     * Opens a JDBC {@link Connection} from the provider via a reflective trick —
     * we piggyback on the ping method to reuse the pool without exposing getConnection().
     *
     * <p>For production correctness we call {@code getSchemaVersion()} which opens a
     * connection internally. However for the dump we need an actual open connection.
     * We obtain it by casting to the concrete provider types that expose a package-private
     * {@code getConnection()} or by using their {@link java.sql.DataSource} directly
     * if available. As a portable fallback we declare a helper that is overridden
     * per-provider.
     *
     * <p>Temporary workaround: obtain connection via DataSource field (reflective access).
     * A cleaner approach would be to add {@code Connection openConnection()} to
     * {@link StorageProvider}. For now this method is sufficient.
     */
    private static Connection openConnection(StorageProvider provider) throws Exception {
        // Use reflection to access the HikariDataSource inside the provider
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
