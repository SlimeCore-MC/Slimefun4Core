package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backup.WorldBackup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.World;

/**
 * Creates, lists and restores world backups.
 *
 * <p>World backups are a separate pool from checkpoints. They live under
 * {@code slimefun-checkpoints/worlds/}, have their own retention and their own opt-in toggle, so a
 * multi-gigabyte world archive never bloats the tight all-or-nothing checkpoint verify. Each backup
 * is one {@code world-NNN} directory holding a {@code worlds.zip} and a {@code metadata.json}.
 *
 * <p>Capture saves each target world on the main thread, then zips the region files off-thread.
 * Restore never touches a loaded world: it verifies the archive, refuses to run while players are
 * online, and unpacks the files into a pending staging directory with a marker. The staged worlds
 * are applied on the next startup by {@link #applyPendingRestore(Slimefun)}, before the server loads
 * its worlds — the one point where overwriting world files is safe.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public class WorldBackupService {

    /**
     * Fixed prefix of every world backup directory name.
     */
    public static final String ID_PREFIX = "world-";

    /**
     * Name of the subdirectory holding world backups inside the checkpoint directory.
     */
    public static final String WORLDS_DIRECTORY = "worlds";

    /**
     * Default retention limit for world backups.
     */
    public static final int DEFAULT_KEEP = 3;

    /**
     * Default interval between automatic world backups, in hours (four times per day).
     */
    public static final int DEFAULT_INTERVAL_HOURS = 6;

    private static final int MINIMUM_RETAINED = 1;
    private static final int SEQUENCE_DIGITS = 3;
    private static final String PENDING_DIRECTORY = "world-restore-pending";
    private static final String PENDING_MARKER = "restore.marker";

    private final Slimefun plugin;
    private final Config dbConfig;
    private final CheckpointService checkpointService;
    private final AtomicBoolean busy = new AtomicBoolean();

    private volatile @Nullable WorldBackupResult lastResult;

    /**
     * The outcome of a world backup creation attempt.
     *
     * @param success        Whether the backup succeeded
     * @param message        A short description, safe to show to an operator
     * @param snapshotId     The id of the created backup, or {@code null} on failure
     * @param steps          The human readable steps taken
     * @param timestamp      When the attempt finished
     * @param automatic      Whether the scheduler made this attempt
     * @param durationMillis How long the attempt took
     * @param purged         The ids removed by retention
     */
    public record WorldBackupResult(
        boolean success,
        @Nonnull String message,
        @Nullable String snapshotId,
        @Nonnull List<String> steps,
        @Nonnull Instant timestamp,
        boolean automatic,
        long durationMillis,
        @Nonnull List<String> purged
    ) {}

    /**
     * The outcome of a world restore attempt.
     *
     * @param success    Whether the restore was staged
     * @param message    A short description, safe to show to an operator
     * @param snapshotId The id staged for restore, or {@code null} on failure
     * @param steps      The human readable steps taken
     */
    public record WorldRestoreResult(
        boolean success,
        @Nonnull String message,
        @Nullable String snapshotId,
        @Nonnull List<String> steps
    ) {}

    /**
     * Creates the service.
     *
     * @param plugin            The plugin instance, used for the data folder, server and logger
     * @param dbConfig          The {@code database.yml} configuration holding the {@code world-backup} block
     * @param checkpointService The checkpoint service, reused only to resolve the checkpoint root directory
     */
    public WorldBackupService(@Nonnull Slimefun plugin, @Nonnull Config dbConfig, @Nonnull CheckpointService checkpointService) {
        this.plugin = plugin;
        this.dbConfig = dbConfig;
        this.checkpointService = checkpointService;
    }

    /**
     * Returns whether world backups are enabled.
     *
     * @return {@code true} only when {@code world-backup.enabled} is explicitly {@code true}
     */
    public boolean isEnabled() {
        return dbConfig.getBoolean("world-backup.enabled");
    }

    /**
     * Returns whether automatic world backups are scheduled.
     *
     * @return {@code true} when {@code world-backup.automatic} is set
     */
    public boolean isAutomatic() {
        return dbConfig.getBoolean("world-backup.automatic");
    }

    /**
     * Returns the interval between automatic world backups.
     *
     * @return The configured number of hours, or {@value #DEFAULT_INTERVAL_HOURS} when unset or not positive
     */
    public int getIntervalHours() {
        int hours = dbConfig.contains("world-backup.interval-hours")
            ? dbConfig.getInt("world-backup.interval-hours") : DEFAULT_INTERVAL_HOURS;
        return hours > 0 ? hours : DEFAULT_INTERVAL_HOURS;
    }

    /**
     * Returns the fixed UTC clock times at which automatic world backups should run.
     *
     * <p>When this list holds valid {@code HH:mm} times, the scheduler fires at each of them instead
     * of on the {@link #getIntervalHours() hour interval}. An empty or absent list falls back to the
     * interval.
     *
     * @return The configured time strings, never {@code null}
     */
    @Nonnull
    public List<String> getScheduleTimes() {
        List<String> times = dbConfig.getStringList("world-backup.times");
        return times == null ? List.of() : times;
    }

    /**
     * Returns the retention limit for world backups.
     *
     * @return The configured maximum, never below {@value #MINIMUM_RETAINED}
     */
    public int getKeep() {
        int keep = dbConfig.contains("world-backup.keep")
            ? dbConfig.getInt("world-backup.keep") : DEFAULT_KEEP;
        return Math.max(MINIMUM_RETAINED, keep);
    }

    /**
     * Returns the configured world names, or an empty list to auto-detect.
     *
     * @return The configured names, possibly empty
     */
    @Nonnull
    public List<String> getConfiguredWorlds() {
        List<String> configured = dbConfig.getStringList("world-backup.worlds");
        return configured == null ? List.of() : configured;
    }

    /**
     * Returns the outcome of the newest creation attempt.
     *
     * @return The last result, or {@code null} when none was attempted
     */
    @Nullable
    public WorldBackupResult getLastResult() {
        return lastResult;
    }

    /**
     * Returns whether a world backup operation is currently running.
     *
     * @return {@code true} while a creation holds the lock
     */
    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Returns the world backup root directory.
     *
     * @return The {@code worlds} directory inside the checkpoint directory
     */
    @Nonnull
    public File getDirectory() {
        return new File(checkpointService.getDirectory(), WORLDS_DIRECTORY);
    }

    /**
     * Formats a sequence number as a world backup id.
     *
     * @param sequence The sequence number
     * @return The id, for example {@literal "world-003"}
     */
    @Nonnull
    public static String formatId(int sequence) {
        return ID_PREFIX + String.format("%0" + SEQUENCE_DIGITS + "d", sequence);
    }
    /**
     * Lists every valid world backup, newest first.
     *
     * @return The world backups in descending sequence order, possibly empty
     */
    @Nonnull
    public List<WorldSnapshot> list() {
        List<WorldSnapshot> snapshots = new ArrayList<>();
        File root = getDirectory();

        if (!root.isDirectory()) {
            return snapshots;
        }

        File[] children = root.listFiles();

        if (children == null) {
            return snapshots;
        }

        for (File child : children) {
            if (!child.isDirectory() || parseSequence(child.getName()) < 0) {
                continue;
            }

            WorldSnapshot snapshot = read(child);

            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }

        snapshots.sort(Comparator.comparingInt(WorldSnapshot::sequence).reversed());
        return snapshots;
    }

    /**
     * Returns the newest valid world backup.
     *
     * @return The newest snapshot, or {@code null} when none exist
     */
    @Nullable
    public WorldSnapshot newest() {
        List<WorldSnapshot> snapshots = list();
        return snapshots.isEmpty() ? null : snapshots.get(0);
    }

    /**
     * Resolves an operator supplied reference to a world backup.
     *
     * <p>Accepts {@literal "world-003"}, {@literal "003"} and {@literal "3"}, and falls back to a
     * unique prefix match.
     *
     * @param reference The reference to resolve
     * @return The matching snapshot, or {@code null} when nothing matches
     */
    @Nullable
    public WorldSnapshot find(@Nonnull String reference) {
        String trimmed = reference.trim();

        if (trimmed.isEmpty()) {
            return null;
        }

        String wanted = trimmed.toLowerCase(Locale.ROOT);

        if (!wanted.startsWith(ID_PREFIX)) {
            try {
                wanted = formatId(Integer.parseInt(wanted));
            } catch (NumberFormatException x) {
                wanted = ID_PREFIX + wanted;
            }
        }

        List<WorldSnapshot> snapshots = list();

        for (WorldSnapshot snapshot : snapshots) {
            if (snapshot.id().equalsIgnoreCase(wanted)) {
                return snapshot;
            }
        }

        for (WorldSnapshot snapshot : snapshots) {
            if (snapshot.id().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return snapshot;
            }
        }

        return null;
    }

    private static int parseSequence(@Nonnull String directoryName) {
        if (!directoryName.toLowerCase(Locale.ROOT).startsWith(ID_PREFIX)) {
            return -1;
        }

        String digits = directoryName.substring(ID_PREFIX.length());

        if (digits.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return -1;
            }
        }

        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException x) {
            return -1;
        }
    }

    private int nextSequence(@Nonnull File root) {
        int highest = 0;
        File[] children = root.listFiles();

        if (children != null) {
            for (File child : children) {
                int sequence = parseSequence(child.getName());

                if (sequence > highest) {
                    highest = sequence;
                }
            }
        }

        return highest + 1;
    }

    @Nullable
    private WorldSnapshot read(@Nonnull File directory) {
        File metadataFile = new File(directory, WorldSnapshot.METADATA_FILE);

        if (!metadataFile.isFile()) {
            return null;
        }

        try {
            String json = Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8);
            return parseMetadata(directory, json);
        } catch (IOException | RuntimeException x) {
            plugin.getLogger().log(Level.WARNING,
                "Could not read world backup " + directory.getName() + ": " + describe(x));
            return null;
        }
    }
    /**
     * Creates a new world backup.
     *
     * <p>The target worlds are saved on the main thread first, then their region files are zipped,
     * checksummed and verified off-thread before retention removes anything. On any error the
     * partially written directory is deleted.
     *
     * <p>This performs blocking file work and a synchronous save hop, so it must not be called on
     * the main thread.
     *
     * @param automatic Whether the scheduler requested this backup
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public WorldBackupResult create(boolean automatic) {
        long started = System.currentTimeMillis();
        List<String> steps = new ArrayList<>();

        if (!isEnabled()) {
            return finish(false, "World backups are disabled in database.yml", null, steps, automatic, started, List.of());
        }

        if (!busy.compareAndSet(false, true)) {
            return finish(false, "Another world backup operation is already running", null, steps, automatic, started, List.of());
        }

        File directory = null;

        try {
            List<File> worlds = saveAndResolveWorlds();

            if (!WorldBackup.hasData(worlds)) {
                return finish(false, "No world data found to back up", null, steps, automatic, started, List.of());
            }

            steps.add("Saved and resolved " + worlds.size() + " world folder(s)");

            File root = getDirectory();
            Files.createDirectories(root.toPath());

            int sequence = nextSequence(root);
            String id = formatId(sequence);
            directory = new File(root, id);

            if (directory.exists()) {
                throw new IOException("Directory already exists: " + directory.getName());
            }

            Files.createDirectories(directory.toPath());

            File archive = new File(directory, WorldBackup.ARCHIVE_FILE);
            Map<String, Integer> counts = WorldBackup.writeArchive(archive, worlds);
            long bytes = archive.length();
            String checksum = DatabaseBackupService.sha256Hex(archive);
            steps.add("Wrote " + WorldBackup.ARCHIVE_FILE + " (" + counts.values().stream().mapToInt(Integer::intValue).sum() + " files)");

            List<String> worldNames = new ArrayList<>(counts.keySet());
            CheckpointComponent component = new CheckpointComponent(
                WorldBackup.ARCHIVE_FILE, BackupFormat.WORLD_FILES, checksum, bytes, counts,
                CheckpointStatus.OK, String.join(", ", worldNames));

            WorldSnapshot snapshot = new WorldSnapshot(directory, id, sequence, Instant.now(),
                Slimefun.getVersion(), plugin.getServer().getVersion(), worldNames, component);

            File metadataFile = new File(directory, WorldSnapshot.METADATA_FILE);
            Files.writeString(metadataFile.toPath(), writeMetadata(snapshot), StandardCharsets.UTF_8);

            WorldSnapshot reread = parseMetadata(directory,
                Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8));

            if (!reread.id().equals(id)) {
                throw new IOException("Metadata did not survive a read back");
            }

            verifyChecksum(reread);
            steps.add("Verified checksum of " + WorldBackup.ARCHIVE_FILE);

            List<String> purged = new ArrayList<>();
            prune(getKeep(), purged, steps);

            plugin.getLogger().log(Level.INFO, "Created world backup " + id + " (" + String.join(", ", worldNames) + ")");
            return finish(true, id + " created: " + String.join(", ", worldNames), id, steps, automatic, started, purged);
        } catch (IOException | RuntimeException x) {
            if (directory != null) {
                WorldBackup.deleteRecursively(directory);
                steps.add("Removed the incomplete directory " + directory.getName());
            }

            String message = "World backup failed: " + describe(x);
            plugin.getLogger().log(Level.SEVERE, message);
            return finish(false, message, null, steps, automatic, started, List.of());
        } finally {
            busy.set(false);
        }
    }

    @Nonnull
    private WorldBackupResult finish(boolean success, @Nonnull String message, @Nullable String snapshotId,
            @Nonnull List<String> steps, boolean automatic, long started, @Nonnull List<String> purged) {
        WorldBackupResult result = new WorldBackupResult(success, message, snapshotId,
            List.copyOf(steps), Instant.now(), automatic, System.currentTimeMillis() - started, List.copyOf(purged));
        lastResult = result;
        return result;
    }
    @Nonnull
    private List<File> saveAndResolveWorlds() {
        Callable<List<File>> task = () -> {
            List<File> resolved = WorldBackup.resolveWorlds(plugin.getServer(), getConfiguredWorlds());

            for (File directory : resolved) {
                World world = plugin.getServer().getWorld(directory.getName());

                if (world != null) {
                    world.save();
                }
            }

            return resolved;
        };

        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, task).get();
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while saving worlds");
        } catch (java.util.concurrent.ExecutionException x) {
            throw new IllegalStateException("Could not save worlds: " + describe(x));
        }
    }

    private void verifyChecksum(@Nonnull WorldSnapshot snapshot) throws IOException {
        File file = snapshot.archiveFile();

        if (file == null || !file.isFile()) {
            throw new IOException("World archive file is missing");
        }

        if (!DatabaseBackupService.sha256Hex(file).equalsIgnoreCase(snapshot.archive().checksum())) {
            throw new IOException("Checksum mismatch for " + file.getName());
        }
    }

    private void prune(int keep, @Nonnull List<String> purged, @Nonnull List<String> steps) {
        List<WorldSnapshot> snapshots = list();

        if (snapshots.size() <= keep) {
            return;
        }

        for (int i = keep; i < snapshots.size(); i++) {
            WorldSnapshot old = snapshots.get(i);
            WorldBackup.deleteRecursively(old.directory());
            purged.add(old.id());
        }

        if (!purged.isEmpty()) {
            steps.add("Retention removed " + purged.size() + " old backup(s): " + String.join(", ", purged));
        }
    }

    /**
     * Stages a world backup to be restored on the next startup.
     *
     * <p>Nothing live is touched: the archive is verified and unpacked into a pending directory with
     * a marker, and {@link #applyPendingRestore(Slimefun)} moves the staged worlds over the live ones
     * before they load. The caller must ensure no players are online.
     *
     * <p>This performs blocking file work and must not be called on the main thread.
     *
     * @param snapshot The world backup to stage
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public WorldRestoreResult restore(@Nonnull WorldSnapshot snapshot) {
        List<String> steps = new ArrayList<>();

        if (!busy.compareAndSet(false, true)) {
            return new WorldRestoreResult(false, "Another world backup operation is already running", null, List.copyOf(steps));
        }

        try {
            File archive = snapshot.archiveFile();

            if (!snapshot.isRestorable() || archive == null) {
                return new WorldRestoreResult(false, "World backup " + snapshot.id() + " has no restorable archive", null, List.copyOf(steps));
            }

            verifyChecksum(snapshot);
            steps.add("Verified checksum of " + archive.getName());

            File pending = new File(plugin.getDataFolder(), PENDING_DIRECTORY);
            WorldBackup.deleteRecursively(pending);
            int files = WorldBackup.extractInto(archive, pending);
            steps.add("Unpacked " + files + " file(s) into " + pending.getName());

            File marker = new File(pending, PENDING_MARKER);
            Files.writeString(marker.toPath(), snapshot.id() + System.lineSeparator() + Instant.now(), StandardCharsets.UTF_8);
            steps.add("Wrote restore marker; the restore applies on the next startup");

            plugin.getLogger().log(Level.INFO, "Staged world restore " + snapshot.id() + "; it will apply on the next startup");
            return new WorldRestoreResult(true, snapshot.id() + " staged for restore", snapshot.id(), List.copyOf(steps));
        } catch (IOException | RuntimeException x) {
            String message = "World restore failed: " + describe(x);
            plugin.getLogger().log(Level.SEVERE, message);
            return new WorldRestoreResult(false, message, null, List.copyOf(steps));
        } finally {
            busy.set(false);
        }
    }

    /**
     * Applies a staged world restore, if one is pending.
     *
     * <p>Called from {@link Slimefun#onLoad()} before the server loads its worlds. When a marker is
     * present, each staged world folder is moved over its live counterpart under the server world
     * container, then the pending directory is removed. This is the only point where overwriting
     * world files is safe, so the restore command only stages files and defers to this method.
     *
     * @param plugin The plugin instance, used for the data folder, server and logger
     */
    public static void applyPendingRestore(@Nonnull Slimefun plugin) {
        File pending = new File(plugin.getDataFolder(), PENDING_DIRECTORY);
        File marker = new File(pending, PENDING_MARKER);

        if (!marker.isFile()) {
            return;
        }

        String id = "?";

        try {
            id = Files.readString(marker.toPath(), StandardCharsets.UTF_8).split("\\R", 2)[0].trim();
        } catch (IOException ignored) {
        }

        try {
            Files.deleteIfExists(marker.toPath());
            int replaced = WorldBackup.applyStagedWorlds(pending, plugin.getServer().getWorldContainer());
            WorldBackup.deleteRecursively(pending);
            plugin.getLogger().log(Level.INFO,
                "[WorldBackup] Applied staged world restore " + id + ": " + replaced + " world folder(s) replaced.");
        } catch (IOException | RuntimeException x) {
            plugin.getLogger().log(Level.SEVERE,
                "[WorldBackup] Failed to apply staged world restore " + id + ": " + describe(x));
        }
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
    @Nonnull
    private String writeMetadata(@Nonnull WorldSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("id", snapshot.id());
        root.addProperty("sequence", snapshot.sequence());
        root.addProperty("createdAt", snapshot.createdAt().toString());
        root.addProperty("slimefunVersion", snapshot.slimefunVersion());
        root.addProperty("serverVersion", snapshot.serverVersion());

        JsonArray worlds = new JsonArray();

        for (String world : snapshot.worlds()) {
            worlds.add(world);
        }

        root.add("worlds", worlds);
        root.add("archive", writeComponent(snapshot.archive()));

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    @Nonnull
    private WorldSnapshot parseMetadata(@Nonnull File directory, @Nonnull String json) throws IOException {
        JsonElement parsed;

        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException x) {
            throw new IOException("World backup metadata is not valid JSON", x);
        }

        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("World backup metadata is not a JSON object");
        }

        JsonObject root = parsed.getAsJsonObject();
        String id = string(root, "id", "");

        if (id.isBlank()) {
            throw new IOException("World backup metadata names no id");
        }

        List<String> worlds = new ArrayList<>();

        if (root.has("worlds") && root.get("worlds").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("worlds")) {
                if (element != null && element.isJsonPrimitive()) {
                    worlds.add(element.getAsString());
                }
            }
        }

        return new WorldSnapshot(
            directory,
            id,
            number(root, "sequence", parseSequence(directory.getName())),
            instant(root, "createdAt"),
            string(root, "slimefunVersion", "?"),
            string(root, "serverVersion", "?"),
            worlds,
            readComponent(root, "archive"));
    }

    @Nonnull
    private static JsonObject writeComponent(@Nonnull CheckpointComponent component) {
        JsonObject object = new JsonObject();
        object.addProperty("status", component.status().name());
        object.addProperty("fileName", component.fileName());
        object.addProperty("format", component.format().name());
        object.addProperty("checksum", component.checksum());
        object.addProperty("bytes", component.bytes());
        object.addProperty("entries", component.totalEntries());
        object.addProperty("detail", component.detail());

        JsonObject counts = new JsonObject();

        for (Map.Entry<String, Integer> entry : component.counts().entrySet()) {
            counts.addProperty(entry.getKey(), entry.getValue());
        }

        object.add("counts", counts);
        return object;
    }

    @Nonnull
    private static CheckpointComponent readComponent(@Nonnull JsonObject root, @Nonnull String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return CheckpointComponent.failed(BackupFormat.WORLD_FILES, "Metadata holds no " + key + " component");
        }

        JsonObject object = root.getAsJsonObject(key);
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (object.has("counts") && object.get("counts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("counts").entrySet()) {
                try {
                    counts.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (RuntimeException ignored) {
                    counts.put(entry.getKey(), 0);
                }
            }
        }

        return new CheckpointComponent(
            string(object, "fileName", WorldBackup.ARCHIVE_FILE),
            BackupFormat.fromName(string(object, "format", "")),
            string(object, "checksum", ""),
            longNumber(object, "bytes"),
            counts,
            CheckpointStatus.fromName(string(object, "status", "")),
            string(object, "detail", ""));
    }

    @Nonnull
    private static String string(@Nonnull JsonObject object, @Nonnull String key, @Nonnull String fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }

        try {
            return object.get(key).getAsString();
        } catch (RuntimeException x) {
            return fallback;
        }
    }

    private static int number(@Nonnull JsonObject object, @Nonnull String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }

        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException x) {
            return fallback;
        }
    }

    private static long longNumber(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return 0L;
        }

        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException x) {
            return 0L;
        }
    }

    @Nonnull
    private static Instant instant(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return Instant.EPOCH;
        }

        try {
            return Instant.parse(object.get(key).getAsString());
        } catch (RuntimeException x) {
            return Instant.EPOCH;
        }
    }
}