package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageConsistencyChecker;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.BlockStorageBackup;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseRestoreService;
import io.github.thebusybiscuit.slimefun4.storage.backup.LegacyFileBackup;
import io.github.thebusybiscuit.slimefun4.storage.backup.PlayerFileBackup;
import io.github.thebusybiscuit.slimefun4.storage.migration.StorageMigrator;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.TableDefinition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Creates, lists and restores checkpoints.
 *
 * <p>A checkpoint is one coherent Slimefun backup state holding two independent halves: an archive
 * of the file-based storage tree and an export of the database-owned player data. Both halves are
 * captured under a single {@code checkpoint-NNN} directory together with a {@code metadata.json}
 * describing them.
 *
 * <p>Neither half ever contains Minecraft world data. The storage half is rooted at
 * {@code data-storage/Slimefun} and limited to the sections the storage implementation actually
 * owns; the database half only ever holds Slimefun player tables.
 *
 * <p>Creation is all-or-nothing. A checkpoint whose halves cannot both be verified is deleted
 * before this class returns, so {@link #list()} can never show a half-valid state. Retention only
 * removes an old checkpoint after the new one has been written, checksummed and re-parsed.
 *
 * <p>Restores are equally strict: the requested half or halves are verified and a matching safety
 * copy of the current state is taken first. The database half is written in place and rolled back on
 * failure; the storage half is staged and applied on the next startup by
 * {@link #applyPendingStorageRestore(Slimefun)}, before Slimefun loads block data into memory, so a
 * live server can never overwrite the restored files on shutdown.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public class CheckpointService {

    /**
     * Default directory name, relative to the plugin data folder.
     */
    public static final String DEFAULT_DIRECTORY = "slimefun-checkpoints";

    /**
     * Default retention limit.
     */
    public static final int DEFAULT_MAX_CHECKPOINTS = 10;

    /**
     * Fixed prefix of every checkpoint directory name.
     */
    public static final String ID_PREFIX = "checkpoint-";

    private static final int MINIMUM_RETAINED = 1;
    private static final int SEQUENCE_DIGITS = 3;
    private static final String SAFETY_DIRECTORY = "safety";
    private static final String SAFETY_PREFIX = "pre-restore-";
    private static final int DEFAULT_SAFETY_KEEP = 3;

    /**
     * Staging directory (under the plugin data folder) for a pending storage restore. The block
     * archive is copied here and applied on the next startup by {@link #applyPendingStorageRestore},
     * before Slimefun loads block data into memory. Restoring live is unsafe: Slimefun holds block
     * data in memory and re-writes it over the restored files on the very shutdown a live restore
     * asks the operator to perform.
     */
    private static final String STORAGE_PENDING_DIRECTORY = "storage-restore-pending";
    private static final String STORAGE_PENDING_MARKER = "restore.marker";
    private static final String STORAGE_PENDING_ARCHIVE = "storage.zip";
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Slimefun plugin;
    private final Config dbConfig;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean storageDirty = new AtomicBoolean(true);
    private final AtomicBoolean databaseDirty = new AtomicBoolean(true);

    private volatile @Nullable CheckpointResult lastResult;
    private volatile @Nullable CheckpointRestoreResult lastRestoreResult;

    /**
     * Creates the service.
     *
     * @param plugin   The plugin instance, used for the data folder and the logger
     * @param dbConfig The {@code database.yml} configuration holding the {@code checkpoint} block
     */
    public CheckpointService(@Nonnull Slimefun plugin, @Nonnull Config dbConfig) {
        this.plugin = plugin;
        this.dbConfig = dbConfig;
    }

    /**
     * Returns whether the checkpoint system is enabled.
     *
     * @return {@code true} unless {@code checkpoint.enabled} is explicitly {@code false}
     */
    public boolean isEnabled() {
        return !dbConfig.contains("checkpoint.enabled") || dbConfig.getBoolean("checkpoint.enabled");
    }

    /**
     * Returns whether automatic checkpoints are scheduled.
     *
     * @return {@code true} when {@code checkpoint.automatic} is set
     */
    public boolean isAutomatic() {
        return dbConfig.getBoolean("checkpoint.automatic");
    }

    /**
     * Returns whether the scheduler should skip unchanged states.
     *
     * @return {@code true} unless {@code checkpoint.only-when-changed} is explicitly {@code false}
     */
    public boolean isOnlyWhenChanged() {
        return !dbConfig.contains("checkpoint.only-when-changed")
            || dbConfig.getBoolean("checkpoint.only-when-changed");
    }

    /**
     * Returns the interval between automatic checkpoints.
     *
     * @return The configured number of hours, or {@code 12} when unset or not positive
     */
    public int getIntervalHours() {
        int hours = dbConfig.contains("checkpoint.interval-hours")
            ? dbConfig.getInt("checkpoint.interval-hours") : 12;
        return hours > 0 ? hours : 12;
    }

    /**
     * Returns the fixed UTC clock times at which automatic checkpoints should run.
     *
     * <p>When this list holds valid {@code HH:mm} times, the scheduler fires at each of them instead
     * of on the {@link #getIntervalHours() hour interval}. An empty or absent list falls back to the
     * interval.
     *
     * @return The configured time strings, never {@code null}
     */
    @Nonnull
    public List<String> getScheduleTimes() {
        List<String> times = dbConfig.getStringList("checkpoint.times");
        return times == null ? List.of() : times;
    }

    /**
     * Returns the retention limit.
     *
     * @return The configured maximum, never below {@value #MINIMUM_RETAINED}
     */
    public int getMaxCheckpoints() {
        int max = dbConfig.contains("checkpoint.max-checkpoints")
            ? dbConfig.getInt("checkpoint.max-checkpoints") : DEFAULT_MAX_CHECKPOINTS;
        return Math.max(MINIMUM_RETAINED, max);
    }

    /**
     * Returns the outcome of the newest creation attempt.
     *
     * @return The last result, or {@code null} when none was attempted
     */
    @Nullable
    public CheckpointResult getLastResult() {
        return lastResult;
    }

    /**
     * Returns the outcome of the newest restore attempt.
     *
     * @return The last restore result, or {@code null} when none was attempted
     */
    @Nullable
    public CheckpointRestoreResult getLastRestoreResult() {
        return lastRestoreResult;
    }

    /**
     * Returns whether a checkpoint operation is currently running.
     *
     * @return {@code true} while a creation or restore holds the lock
     */
    public boolean isBusy() {
        return busy.get();
    }

    /**
     * Records that file-based storage data changed since the newest checkpoint.
     */
    public void markStorageDirty() {
        storageDirty.set(true);
    }

    /**
     * Records that database-owned player data changed since the newest checkpoint.
     */
    public void markDatabaseDirty() {
        databaseDirty.set(true);
    }

    /**
     * Returns the checkpoint root directory.
     *
     * @return The configured directory, resolved against the plugin data folder when relative
     */
    @Nonnull
    public File getDirectory() {
        String configured = dbConfig.contains("checkpoint.directory")
            ? dbConfig.getString("checkpoint.directory") : DEFAULT_DIRECTORY;

        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_DIRECTORY;
        }

        File candidate = new File(configured);
        return candidate.isAbsolute() ? candidate : new File(plugin.getDataFolder(), configured);
    }

    /**
     * Returns the directory holding pre-restore safety copies.
     *
     * <p>Safety copies are named {@code pre-restore-<stamp>} so they never look like a checkpoint id.
     * They are therefore invisible to {@link #list()}, to id allocation and to retention.
     *
     * @return The safety directory inside {@link #getDirectory()}
     */
    @Nonnull
    public File getSafetyDirectory() {
        return new File(getDirectory(), SAFETY_DIRECTORY);
    }

    /**
     * Lists every valid checkpoint, newest first.
     *
     * <p>A directory whose metadata cannot be parsed is logged as a warning and left on disk. It is
     * never counted towards retention and never offered as a restore source.
     *
     * @return The checkpoints in descending sequence order, possibly empty
     */
    @Nonnull
    public List<Checkpoint> list() {
        List<Checkpoint> checkpoints = new ArrayList<>();
        File root = getDirectory();

        if (!root.isDirectory()) {
            return checkpoints;
        }

        File[] children = root.listFiles();

        if (children == null) {
            return checkpoints;
        }

        for (File child : children) {
            if (!child.isDirectory() || parseSequence(child.getName()) < 0) {
                continue;
            }

            Checkpoint checkpoint = read(child);

            if (checkpoint != null) {
                checkpoints.add(checkpoint);
            }
        }

        checkpoints.sort(Comparator.comparingInt(Checkpoint::sequence).reversed());
        return checkpoints;
    }

    /**
     * Returns the newest valid checkpoint.
     *
     * @return The newest checkpoint, or {@code null} when none exist
     */
    @Nullable
    public Checkpoint newest() {
        List<Checkpoint> checkpoints = list();
        return checkpoints.isEmpty() ? null : checkpoints.get(0);
    }

    /**
     * Resolves an operator supplied reference to a checkpoint.
     *
     * <p>Accepts {@literal "checkpoint-003"}, {@literal "003"} and {@literal "3"}, and falls back to
     * a unique prefix match.
     *
     * @param reference The reference to resolve
     *
     * @return The matching checkpoint, or {@code null} when nothing matches
     */
    @Nullable
    public Checkpoint find(@Nonnull String reference) {
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

        List<Checkpoint> checkpoints = list();

        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.id().equalsIgnoreCase(wanted)) {
                return checkpoint;
            }
        }

        for (Checkpoint checkpoint : checkpoints) {
            if (checkpoint.id().toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return checkpoint;
            }
        }

        return null;
    }

    /**
     * Formats a sequence number as a checkpoint id.
     *
     * @param sequence The sequence number
     *
     * @return The id, for example {@literal "checkpoint-003"}
     */
    @Nonnull
    public static String formatId(int sequence) {
        return ID_PREFIX + String.format("%0" + SEQUENCE_DIGITS + "d", sequence);
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

    @Nullable
    private Checkpoint read(@Nonnull File directory) {
        File metadataFile = new File(directory, Checkpoint.METADATA_FILE);

        if (!metadataFile.isFile()) {
            plugin.getLogger().log(Level.WARNING,
                "Ignoring checkpoint without metadata: " + directory.getName());
            return null;
        }

        try {
            String json = Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8);
            CheckpointMetadata metadata = CheckpointMetadata.fromJson(json);

            if (!metadata.isValid()) {
                plugin.getLogger().log(Level.WARNING,
                    "Ignoring incomplete checkpoint: " + directory.getName());
                return null;
            }

            return new Checkpoint(directory, metadata);
        } catch (IOException | CheckpointException x) {
            plugin.getLogger().log(Level.WARNING,
                "Could not read checkpoint " + directory.getName() + ": " + describe(x));
            return null;
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

    /**
     * Creates a new checkpoint.
     *
     * <p>Both halves are written and verified before retention removes anything, so a failure never
     * costs an existing checkpoint. On any error the partially written directory is deleted.
     *
     * <p>This performs blocking file and database work and must not be called on the main thread.
     *
     * @param provider  The active storage provider, used for the database half
     * @param automatic Whether the scheduler requested this checkpoint
     *
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public CheckpointResult create(@Nonnull StorageProvider provider, boolean automatic) {
        long started = System.currentTimeMillis();
        List<String> steps = new ArrayList<>();

        if (!isEnabled()) {
            return finish(false, "The checkpoint system is disabled in database.yml",
                null, steps, automatic, started, List.of());
        }

        if (!busy.compareAndSet(false, true)) {
            return finish(false, "Another checkpoint operation is already running",
                null, steps, automatic, started, List.of());
        }

        try {
            File root = getDirectory();
            Files.createDirectories(root.toPath());

            int sequence = nextSequence(root);
            String id = formatId(sequence);
            steps.add("Preparing " + id + " in " + root.getPath());

            Checkpoint checkpoint = capture(provider, new File(root, id), id, sequence, automatic, steps);

            List<String> purged = new ArrayList<>();
            purge(getMaxCheckpoints(), purged, steps);

            storageDirty.set(false);
            databaseDirty.set(false);

            plugin.getLogger().log(Level.INFO, "Created " + id + " (" + summarize(checkpoint.metadata()) + ")");
            return finish(true, id + " created: " + summarize(checkpoint.metadata()),
                checkpoint, steps, automatic, started, purged);
        } catch (CheckpointException | IOException | RuntimeException x) {
            String message = "Checkpoint failed: " + describe(x);
            steps.add(message);
            plugin.getLogger().log(Level.SEVERE, message);
            return finish(false, message, null, steps, automatic, started, List.of());
        } finally {
            busy.set(false);
        }
    }

    @Nonnull
    private Checkpoint capture(@Nonnull StorageProvider provider, @Nonnull File directory,
            @Nonnull String id, int sequence, boolean automatic, @Nonnull List<String> steps)
            throws CheckpointException {
        boolean discard = true;

        try {
            if (directory.exists()) {
                throw new CheckpointException("Directory already exists: " + directory.getName());
            }

            Files.createDirectories(directory.toPath());
            flush(steps);

            CheckpointComponent storage = captureStorage(directory, steps);
            CheckpointComponent database = captureDatabase(provider, directory, steps);

            if (!storage.status().isAcceptable()) {
                throw new CheckpointException("Storage backup failed: " + storage.detail());
            }

            if (!database.status().isAcceptable()) {
                throw new CheckpointException("Database backup failed: " + database.detail());
            }

            if (!storage.isPresent() && !database.isPresent()) {
                throw new CheckpointException("Nothing to capture, both halves are empty");
            }

            CheckpointMetadata metadata = new CheckpointMetadata(
                CheckpointMetadata.CURRENT_FORMAT_VERSION, id, sequence, Instant.now(),
                Slimefun.getVersion(), serverVersion(plugin),
                provider.getProviderName(), provider.getProviderType().name(),
                automatic, storage, database
            );

            File metadataFile = new File(directory, Checkpoint.METADATA_FILE);
            Files.writeString(metadataFile.toPath(), metadata.toJson(), StandardCharsets.UTF_8);

            CheckpointMetadata reread =
                CheckpointMetadata.fromJson(Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8));

            if (!reread.isValid() || !reread.checkpointId().equals(id)) {
                throw new CheckpointException("Metadata did not survive a read back");
            }

            steps.add("Wrote and re-parsed " + Checkpoint.METADATA_FILE);
            Checkpoint checkpoint = new Checkpoint(directory, reread);
            verifyChecksums(checkpoint, steps);

            discard = false;
            return checkpoint;
        } catch (IOException x) {
            throw new CheckpointException("Could not write " + directory.getName() + ": " + describe(x), x);
        } finally {
            if (discard) {
                deleteRecursively(directory);
                steps.add("Removed the incomplete directory " + directory.getName());
            }
        }
    }

    private void flush(@Nonnull List<String> steps) {
        int players = 0;
        Iterator<PlayerProfile> iterator = PlayerProfile.iterator();

        while (iterator.hasNext()) {
            PlayerProfile profile = iterator.next();

            if (profile.isDirty()) {
                profile.save();
                players++;
            }
        }

        steps.add("Flushed " + players + " dirty player profile(s)");

        try {
            StorageMigrator.flushBlockData(plugin);
            steps.add("Flushed block data to disk");
        } catch (Exception x) {
            String message = "Block data was not flushed, the storage half may be slightly older than now: "
                + describe(x);
            steps.add(message);
            plugin.getLogger().log(Level.WARNING, message);
        }
    }

    @Nonnull
    private CheckpointComponent captureStorage(@Nonnull File directory, @Nonnull List<String> steps)
            throws IOException {
        File root = BlockStorageBackup.defaultRoot();

        if (!BlockStorageBackup.hasData(root)) {
            steps.add("Storage half skipped, no block data at " + root.getPath());
            return CheckpointComponent.skipped(BackupFormat.BLOCK_FILES,
                "No block data at " + root.getPath());
        }

        File target = new File(new File(directory, Checkpoint.STORAGE_DIRECTORY), Checkpoint.STORAGE_FILE);
        Files.createDirectories(target.getParentFile().toPath());

        Map<String, Integer> counts = BlockStorageBackup.writeArchive(target, root);
        int entries = totalOf(counts);

        if (entries == 0) {
            Files.deleteIfExists(target.toPath());
            steps.add("Storage half skipped, the block storage tree holds no files");
            return CheckpointComponent.skipped(BackupFormat.BLOCK_FILES,
                "The block storage tree holds no files");
        }

        long bytes = target.length();
        steps.add("Storage half captured: " + entries + " file(s), " + bytes + " bytes");

        return new CheckpointComponent(target.getName(), BackupFormat.BLOCK_FILES,
            DatabaseBackupService.sha256Hex(target), bytes, counts, CheckpointStatus.OK,
            describeCounts(counts));
    }

    @Nonnull
    private CheckpointComponent captureStorageSafety(@Nonnull File directory, @Nonnull List<String> steps)
            throws IOException {
        File root = BlockStorageBackup.defaultRoot();
        File target = new File(new File(directory, Checkpoint.STORAGE_DIRECTORY), Checkpoint.STORAGE_FILE);
        Files.createDirectories(target.getParentFile().toPath());
        Map<String, Integer> counts = BlockStorageBackup.writeArchive(target, root);
        long bytes = target.length();
        steps.add("Storage safety half captured: " + totalOf(counts) + " file(s), " + bytes + " bytes");
        return new CheckpointComponent(target.getName(), BackupFormat.BLOCK_FILES,
            DatabaseBackupService.sha256Hex(target), bytes, counts, CheckpointStatus.OK,
            describeCounts(counts));
    }

    @Nonnull
    private CheckpointComponent captureDatabase(@Nonnull StorageProvider provider,
            @Nonnull File directory, @Nonnull List<String> steps) throws IOException {
        boolean compress = !dbConfig.contains("checkpoint.compress")
            || dbConfig.getBoolean("checkpoint.compress");

        File parent = new File(directory, Checkpoint.DATABASE_DIRECTORY);
        Files.createDirectories(parent.toPath());

        Map<String, Integer> counts = new LinkedHashMap<>();
        BackupFormat format;
        File target;

        if (provider.supportsSql()) {
            format = BackupFormat.SQL_JSON;
            target = new File(parent, Checkpoint.DATABASE_FILE_BASE + (compress ? ".zip" : ".json"));
            counts.putAll(DatabaseBackupService.writeSqlDump(provider, target, compress));
        } else {
            format = BackupFormat.PLAYER_FILES;
            target = new File(parent, Checkpoint.DATABASE_FILE_BASE + ".zip");
            File root = PlayerFileBackup.defaultRoot();

            if (!PlayerFileBackup.hasData(root)) {
                steps.add("Database half skipped, " + provider.getProviderName() + " holds no player data");
                return CheckpointComponent.skipped(format,
                    provider.getProviderName() + " holds no player data");
            }

            counts.putAll(PlayerFileBackup.writeArchive(target, root));

            if (totalOf(counts) == 0) {
                Files.deleteIfExists(target.toPath());
                steps.add("Database half skipped, no player files were captured");
                return CheckpointComponent.skipped(format, "No player files were captured");
            }
        }

        if (!target.isFile()) {
            return CheckpointComponent.failed(format, "The backup file was not written");
        }

        long bytes = target.length();
        steps.add("Database half captured from " + provider.getProviderName() + ": "
            + totalOf(counts) + " record(s), " + bytes + " bytes");

        return new CheckpointComponent(target.getName(), format,
            DatabaseBackupService.sha256Hex(target), bytes, counts, CheckpointStatus.OK,
            describeCounts(counts));
    }

    @Nonnull
    private CheckpointComponent captureDatabaseSafety(@Nonnull StorageProvider provider,
            @Nonnull File directory, @Nonnull List<String> steps) throws IOException {
        if (provider.supportsSql()
                || PlayerFileBackup.hasData(PlayerFileBackup.defaultRoot())) {
            return captureDatabase(provider, directory, steps);
        }

        File parent = new File(directory, Checkpoint.DATABASE_DIRECTORY);
        Files.createDirectories(parent.toPath());
        File target = new File(parent, Checkpoint.DATABASE_FILE_BASE + ".zip");
        Map<String, Integer> counts = PlayerFileBackup.writeArchive(target, PlayerFileBackup.defaultRoot());
        long bytes = target.length();
        steps.add("Database safety half captured from " + provider.getProviderName() + ": 0 record(s), "
            + bytes + " bytes");
        return new CheckpointComponent(target.getName(), BackupFormat.PLAYER_FILES,
            DatabaseBackupService.sha256Hex(target), bytes, counts, CheckpointStatus.OK,
            describeCounts(counts));
    }

    private void verifyChecksums(@Nonnull Checkpoint checkpoint, @Nonnull List<String> steps)
            throws CheckpointException, IOException {
        verifyHalf(checkpoint.storageFile(), checkpoint.metadata().storage(), "storage");
        verifyHalf(checkpoint.databaseFile(), checkpoint.metadata().database(), "database");
        steps.add("Verified the checksum of every captured half");
    }

    private void verifyHalf(@Nullable File file, @Nonnull CheckpointComponent component,
            @Nonnull String label) throws CheckpointException, IOException {
        if (!component.isPresent()) {
            return;
        }

        if (file == null || !file.isFile()) {
            throw new CheckpointException("The " + label + " half is missing on disk");
        }

        if (!DatabaseBackupService.sha256Hex(file).equalsIgnoreCase(component.checksum())) {
            throw new CheckpointException("The " + label + " half failed checksum verification");
        }
    }

    private void purge(int max, @Nonnull List<String> purged, @Nonnull List<String> steps) {
        List<Checkpoint> checkpoints = list();

        if (checkpoints.size() <= max) {
            steps.add("Retention: " + checkpoints.size() + "/" + max + " kept, nothing removed");
            return;
        }

        for (int i = max; i < checkpoints.size(); i++) {
            Checkpoint old = checkpoints.get(i);

            if (deleteRecursively(old.directory())) {
                purged.add(old.id());
            } else {
                plugin.getLogger().log(Level.WARNING,
                    "Retention could not delete " + old.id() + ", remove it by hand");
            }
        }

        steps.add("Retention removed " + purged.size() + " old checkpoint(s): "
            + (purged.isEmpty() ? "none" : String.join(", ", purged)));
    }

    /**
     * Restores both halves of a checkpoint.
     *
     * <p>The ten stages run in a fixed order: locate the checkpoint, validate its metadata, verify
     * the storage checksum, verify the database half, confirm the server is in a safe state, take a
     * safety copy of the current state, restore storage, restore the database, validate the result
     * and report. A failure after the storage half was written rolls both halves back from the
     * safety copy.
     *
     * <p>Restoring an old checkpoint never touches newer ones. Only the active Slimefun data changes.
     *
     * <p>This performs blocking file and database work and must not be called on the main thread.
     *
     * @param provider   The active storage provider
     * @param checkpoint The checkpoint to restore
     *
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public CheckpointRestoreResult restore(@Nonnull StorageProvider provider, @Nonnull Checkpoint checkpoint) {
        return restore(provider, checkpoint, RestoreScope.BOTH);
    }

    /**
     * Restores only the file-based storage half of the given checkpoint.
     *
     * <p>The database half is left untouched. A safety copy of the current storage files is taken
     * before any writes occur.
     *
     * <p>This performs blocking file work and must not be called on the main thread.
     *
     * @param provider   The active storage provider
     * @param checkpoint The checkpoint to restore
     *
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public CheckpointRestoreResult restoreStorage(@Nonnull StorageProvider provider, @Nonnull Checkpoint checkpoint) {
        return restore(provider, checkpoint, RestoreScope.STORAGE_ONLY);
    }

    /**
     * Restores only the database half of the given checkpoint.
     *
     * <p>The file-based storage half is left untouched. A safety copy of the current database
     * state is taken before any writes occur.
     *
     * <p>This performs blocking database work and must not be called on the main thread.
     *
     * @param provider   The active storage provider
     * @param checkpoint The checkpoint to restore
     *
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public CheckpointRestoreResult restoreDatabase(@Nonnull StorageProvider provider, @Nonnull Checkpoint checkpoint) {
        return restore(provider, checkpoint, RestoreScope.DATABASE_ONLY);
    }

    /**
     * Restores the requested scope of the given checkpoint.
     *
     * <p>This is the core restore implementation. It validates metadata and checksums for only
     * the targeted half, takes a scoped safety copy, performs the restore, and validates the result.
     *
     * @param provider   The active storage provider
     * @param checkpoint The checkpoint to restore
     * @param scope      Which half to restore
     *
     * @return The outcome, never {@code null}
     */
    @Nonnull
    private CheckpointRestoreResult restore(@Nonnull StorageProvider provider,
            @Nonnull Checkpoint checkpoint, @Nonnull RestoreScope scope) {
        List<String> steps = new ArrayList<>();

        if (!busy.compareAndSet(false, true)) {
            return recordRestore(false, "Another checkpoint operation is already running",
                steps, null, 0, 0, false, scope);
        }

        String safetyId = null;

        try {
            String id = checkpoint.id();
            CheckpointMetadata metadata = checkpoint.metadata();

            if (!checkpoint.directory().isDirectory() || !checkpoint.metadataFile().isFile()) {
                return recordRestore(false, "Checkpoint " + id + " is no longer on disk",
                    steps, null, 0, 0, false, scope);
            }

            steps.add("1  Found " + id + " in " + checkpoint.directory().getPath()
                + " (scope: " + scope.getDisplayName() + ")");

            if (metadata.formatVersion() > CheckpointMetadata.CURRENT_FORMAT_VERSION) {
                return recordRestore(false, id + " was written by a newer build, format version "
                    + metadata.formatVersion(), steps, null, 0, 0, false, scope);
            }

            if (!checkpoint.isRestorable(scope)) {
                return recordRestore(false, id + " does not contain a restorable "
                    + scope.getDisplayName() + " component", steps, null, 0, 0, false, scope);
            }

            steps.add("2  Metadata valid: " + summarize(metadata));

            File archive = checkpoint.storageFile();
            File dump = checkpoint.databaseFile();

            try {
                if (scope.includesStorage()) {
                    verifyHalf(archive, metadata.storage(), "storage");
                    steps.add("3  Storage half: " + (metadata.storage().isPresent()
                        ? "checksum verified" : "not captured, nothing to restore"));
                } else {
                    steps.add("3  Storage half: skipped (not in scope)");
                }

                if (scope.includesDatabase()) {
                    verifyHalf(dump, metadata.database(), "database");
                    steps.add("4  Database half: " + (metadata.database().isPresent()
                        ? "checksum verified" : "not captured, nothing to restore"));
                } else {
                    steps.add("4  Database half: skipped (not in scope)");
                }
            } catch (CheckpointException | IOException x) {
                return recordRestore(false, "Verification failed, nothing was restored: " + describe(x),
                    steps, null, 0, 0, false, scope);
            }

            int online = StorageMigrator.onlinePlayers(plugin).getAsInt();

            if (online > 0) {
                return recordRestore(false, "Refusing to restore while " + online
                    + " player(s) are online", steps, null, 0, 0, false, scope);
            }

            if (scope.includesDatabase()) {
                if (!provider.isAvailable()) {
                    return recordRestore(false, provider.getProviderName()
                        + " is not available, nothing was restored", steps, null, 0, 0, false, scope);
                }

                if (provider.supportsSql() && provider.pingMillis() < 0) {
                    return recordRestore(false, provider.getProviderName()
                        + " is not reachable, nothing was restored", steps, null, 0, 0, false, scope);
                }
            }

            steps.add("5  Server state safe: no players online"
                + (scope.includesDatabase() ? ", " + provider.getProviderName() + " reachable" : ""));

            Checkpoint safety;

            try {
                safety = createSafetyCopy(provider, scope, steps);
            } catch (CheckpointException x) {
                return recordRestore(false, "Safety copy failed, nothing was restored: " + describe(x),
                    steps, null, 0, 0, false, scope);
            }

            safetyId = safety == null ? null : safety.id();
            steps.add("6  Safety copy: " + (safetyId == null
                ? "not needed, there is no current data to save" : safetyId));

            int files = 0;

            if (scope.includesStorage() && metadata.storage().isPresent() && archive != null) {
                try {
                    files = stageStorageRestore(archive, id);
                } catch (IOException x) {
                    // Nothing live was touched, so there is nothing to roll back.
                    return recordRestore(false, "Storage restore staging failed: " + describe(x),
                        steps, safetyId, 0, 0, false, scope);
                }
            }

            steps.add("7  Storage half staged: " + (scope.includesStorage()
                ? files + " file(s), applied on the next startup" : "skipped"));

            int rows = 0;

            if (scope.includesDatabase() && metadata.database().isPresent() && dump != null) {
                DatabaseRestoreService.RestoreResult result = provider.supportsSql()
                    ? DatabaseRestoreService.restoreSqlDump(dump, id, provider)
                    : DatabaseRestoreService.restorePlayerArchive(dump, id, provider);

                if (!result.success()) {
                    // The storage half was only staged, never written live, so discard the staged
                    // archive and roll back the database half alone.
                    boolean unstaged = unstageStorageRestore();
                    boolean rolled = rollback(provider, safety, steps, RestoreScope.DATABASE_ONLY);

                    if (scope.includesStorage()) {
                        steps.add(unstaged
                            ? "Discarded the staged storage restore; it will not be applied"
                            : "No staged storage restore was present to discard");
                    }

                    return recordRestore(false, "Database restore failed: " + result.message(),
                        steps, safetyId, 0, 0, rolled, scope);
                }

                rows = result.rowsRestored();
            }

            steps.add("8  Database half restored: " + (scope.includesDatabase() ? rows + " record(s)" : "skipped"));

            if (scope.includesStorage()) {
                steps.add("9  Storage archive checksum verified; the staged files are applied and"
                    + " validated on the next startup");
            } else {
                steps.add("9  Storage validation: skipped (not in scope)");
            }

            if (scope.includesDatabase()) {
                int dropped = DatabaseRestoreService.invalidateCaches();
                steps.add("10 Cache invalidated: " + dropped + " cached profile(s) dropped");
            } else {
                steps.add("10 Cache invalidation: skipped (not in scope)");
            }

            if (scope.includesStorage()) {
                storageDirty.set(true);
            }
            if (scope.includesDatabase()) {
                databaseDirty.set(true);
            }

            String summary = id + " " + scope.getDisplayName() + " restore: "
                + (scope.includesStorage() ? files + " storage file(s) staged" : "")
                + (scope == RestoreScope.BOTH ? ", " : "")
                + (scope.includesDatabase() ? rows + " database record(s) restored" : "");

            steps.add(scope.includesStorage()
                ? "Restore staged. Restart the server to apply it: the restored block data is written"
                    + " before Slimefun loads, so a live server cannot overwrite it."
                : "Restore complete, restart the server so the restored data is fully in use");

            return recordRestore(true, summary, steps, safetyId, files, rows, false, scope);
        } catch (RuntimeException x) {
            String message = "Restore failed: " + describe(x);
            steps.add(message);
            plugin.getLogger().log(Level.SEVERE, message);
            return recordRestore(false, message, steps, safetyId, 0, 0, false, scope);
        } finally {
            busy.set(false);
        }
    }

    @Nullable
    private Checkpoint createSafetyCopy(@Nonnull StorageProvider provider, @Nonnull RestoreScope scope,
            @Nonnull List<String> steps) throws CheckpointException {
        File root = getSafetyDirectory();

        try {
            Files.createDirectories(root.toPath());
        } catch (IOException x) {
            throw new CheckpointException("Could not create " + root.getPath() + ": " + describe(x), x);
        }

        String id = SAFETY_PREFIX + STAMP.format(Instant.now());
        File directory = new File(root, id);
        Checkpoint safety = captureSafety(provider, directory, id, scope, steps);
        purgeSafety(root);

        return safety;
    }

    @Nonnull
    private Checkpoint captureSafety(@Nonnull StorageProvider provider, @Nonnull File directory,
            @Nonnull String id, @Nonnull RestoreScope scope, @Nonnull List<String> steps)
            throws CheckpointException {
        boolean discard = true;

        try {
            Files.createDirectories(directory.toPath());
            flush(steps);

            CheckpointComponent storage = scope.includesStorage()
                ? captureStorageSafety(directory, steps)
                : CheckpointComponent.skipped(BackupFormat.BLOCK_FILES, "Not included in restore scope");
            BackupFormat databaseFormat = provider.supportsSql()
                ? BackupFormat.SQL_JSON : BackupFormat.PLAYER_FILES;
            CheckpointComponent database = scope.includesDatabase()
                ? captureDatabaseSafety(provider, directory, steps)
                : CheckpointComponent.skipped(databaseFormat, "Not included in restore scope");

            if ((scope.includesStorage() && !storage.isPresent())
                    || (scope.includesDatabase() && !database.isPresent())) {
                throw new CheckpointException("Could not capture current data for every requested safety component");
            }

            CheckpointMetadata metadata = new CheckpointMetadata(
                CheckpointMetadata.CURRENT_FORMAT_VERSION, id, 0, Instant.now(),
                Slimefun.getVersion(), serverVersion(plugin), provider.getProviderName(),
                provider.getProviderType().name(), false, storage, database);
            File metadataFile = new File(directory, Checkpoint.METADATA_FILE);
            Files.writeString(metadataFile.toPath(), metadata.toJson(), StandardCharsets.UTF_8);
            CheckpointMetadata reread = CheckpointMetadata.fromJson(
                Files.readString(metadataFile.toPath(), StandardCharsets.UTF_8));
            Checkpoint safety = new Checkpoint(directory, reread);

            if (!safety.isRestorable(scope)) {
                throw new CheckpointException("Safety metadata did not survive a read back");
            }

            if (scope.includesStorage()) {
                verifyHalf(safety.storageFile(), reread.storage(), "storage");
            }
            if (scope.includesDatabase()) {
                verifyHalf(safety.databaseFile(), reread.database(), "database");
            }

            discard = false;
            return safety;
        } catch (IOException x) {
            throw new CheckpointException("Could not write " + directory.getName() + ": " + describe(x), x);
        } finally {
            if (discard) {
                deleteRecursively(directory);
            }
        }
    }

    private void purgeSafety(@Nonnull File root) {
        int keep = dbConfig.contains("checkpoint.keep-safety-backups")
            ? dbConfig.getInt("checkpoint.keep-safety-backups") : DEFAULT_SAFETY_KEEP;

        if (keep <= 0) {
            return;
        }

        File[] children = root.listFiles();

        if (children == null) {
            return;
        }

        List<File> copies = new ArrayList<>();

        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(SAFETY_PREFIX)) {
                copies.add(child);
            }
        }

        copies.sort(Comparator.comparing(File::getName).reversed());

        for (int i = keep; i < copies.size(); i++) {
            deleteRecursively(copies.get(i));
        }
    }

    private static int restoreStorageArchive(@Nonnull File archive) throws IOException {
        return BlockStorageBackup.extractArchive(archive, BlockStorageBackup.defaultRoot());
    }

    /**
     * Applies a staged block archive over the live data root as an all-or-nothing swap.
     *
     * <p>Every existing live block section is first renamed aside into a scratch directory on the
     * same filesystem as the data root, so the move is a cheap rename rather than a copy and needs
     * no extra disk. Only once the sections are safely aside is the staged archive unpacked into the
     * now-empty root. If any part of the unpack fails — a disk-full error partway through the
     * per-section replace, for example — every section written so far is deleted and the originals
     * are renamed back, returning the tree to its exact pre-apply state.
     *
     * <p>Because the rollback is rename-only it cannot itself fail for want of disk, so a mid-apply
     * failure can never leave a half-applied block tree. In the rare event a move-back still fails,
     * the untouched original is preserved in the scratch directory and its location is logged.
     *
     * @param plugin The plugin, for logging
     * @param id The checkpoint id, for log messages
     * @param stagedArchive The verified staged block archive
     * @param root The live block-data root
     * @return The number of files restored
     * @throws IOException When the apply failed; the tree has been rolled back to its prior state
     */
    private static int applyStorageArchiveAtomically(@Nonnull Slimefun plugin, @Nonnull String id,
            @Nonnull File stagedArchive, @Nonnull File root) throws IOException {
        Files.createDirectories(root.toPath());

        File parent = root.getParentFile();
        Path asidePath = parent != null
            ? Files.createTempDirectory(parent.toPath(), ".slimefun-rollback-")
            : Files.createTempDirectory(".slimefun-rollback-");
        File aside = asidePath.toFile();

        List<String> sections = BlockStorageBackup.getSections();
        List<String> movedAside = new ArrayList<>();

        try {
            // Rename every existing live section aside first. Nothing under the root is destroyed
            // until the whole archive has been laid back down.
            for (String section : sections) {
                File live = new File(root, section);

                if (live.exists()) {
                    movePath(live, new File(aside, section));
                    movedAside.add(section);
                }
            }

            // The root now holds none of the sections; unpack the staged archive into it.
            int files = restoreStorageArchive(stagedArchive);

            // Committed: the aside copies are no longer needed.
            deleteRecursively(aside);
            return files;
        } catch (IOException | RuntimeException failure) {
            // Undo any half-written sections, then rename the originals back into place. These moves
            // are all renames on one filesystem, so the rollback cannot fail for want of disk.
            for (String section : sections) {
                deleteRecursively(new File(root, section));
            }

            boolean fullyRestored = true;

            for (String section : movedAside) {
                File live = new File(root, section);
                File saved = new File(aside, section);

                try {
                    movePath(saved, live);
                } catch (IOException moveBack) {
                    fullyRestored = false;
                    plugin.getLogger().log(Level.SEVERE,
                        "[Checkpoint] Storage restore " + id + " failed and section '" + section
                            + "' could not be moved back automatically. The original is preserved at "
                            + saved.getPath() + "; move it to " + live.getPath() + " by hand.");
                }
            }

            if (fullyRestored) {
                deleteRecursively(aside);
            }

            if (failure instanceof IOException io) {
                throw io;
            }

            throw (RuntimeException) failure;
        }
    }

    /**
     * Moves a file or directory to a new location, preferring an atomic rename.
     *
     * @param from The source path
     * @param to The destination path
     * @throws IOException When the move fails
     */
    private static void movePath(@Nonnull File from, @Nonnull File to) throws IOException {
        File parent = to.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Stages a storage-half restore for application on the next startup.
     *
     * <p>The verified block archive is copied into {@link #STORAGE_PENDING_DIRECTORY} under the
     * plugin data folder together with a marker naming the checkpoint. Nothing under the live data
     * root is touched. {@link #applyPendingStorageRestore(Slimefun)} unpacks the staged archive on
     * the next startup, before Slimefun loads block data into memory.
     *
     * @param archive      The verified storage archive to stage
     * @param checkpointId The id of the checkpoint being restored, recorded in the marker
     * @return The number of file entries the staged archive will restore
     * @throws IOException If the archive could not be staged
     */
    private int stageStorageRestore(@Nonnull File archive, @Nonnull String checkpointId) throws IOException {
        File pending = new File(plugin.getDataFolder(), STORAGE_PENDING_DIRECTORY);
        deleteRecursively(pending);
        Files.createDirectories(pending.toPath());

        File stagedArchive = new File(pending, STORAGE_PENDING_ARCHIVE);
        Files.copy(archive.toPath(), stagedArchive.toPath());

        File marker = new File(pending, STORAGE_PENDING_MARKER);
        Files.writeString(marker.toPath(), checkpointId + System.lineSeparator() + Instant.now(),
            StandardCharsets.UTF_8);

        int entries = BlockStorageBackup.countEntries(stagedArchive);
        return entries < 0 ? 0 : entries;
    }

    /**
     * Discards a pending staged storage restore, if one exists.
     *
     * <p>Used when a combined restore fails after the storage half was staged but before it is
     * applied, so the failed restore never partially applies on the next startup.
     *
     * @return {@code true} if a pending staging directory was present and removed
     */
    private boolean unstageStorageRestore() {
        File pending = new File(plugin.getDataFolder(), STORAGE_PENDING_DIRECTORY);

        if (!pending.exists()) {
            return false;
        }

        return deleteRecursively(pending);
    }

    /**
     * Applies a staged storage-half restore, if one is pending.
     *
     * <p>Called from {@link Slimefun#onLoad()} before Slimefun loads block data into memory. When a
     * marker is present the staged archive is unpacked over the live block data root, then the
     * pending directory is removed. This is the only point at which overwriting the block data tree
     * is safe: a live restore is re-written by Slimefun's shutdown save on the restart it asks for,
     * so the restore command only stages the archive and defers the write to here.
     *
     * @param plugin The plugin instance, used for the data folder and the logger
     */
    public static void applyPendingStorageRestore(@Nonnull Slimefun plugin) {
        File pending = new File(plugin.getDataFolder(), STORAGE_PENDING_DIRECTORY);
        File marker = new File(pending, STORAGE_PENDING_MARKER);

        if (!marker.isFile()) {
            return;
        }

        String id = "?";

        try {
            id = Files.readString(marker.toPath(), StandardCharsets.UTF_8).split("\\R", 2)[0].trim();
        } catch (IOException ignored) {
            // Fall back to the unknown-id placeholder.
        }

        File stagedArchive = new File(pending, STORAGE_PENDING_ARCHIVE);

        try {
            // Remove the marker first so a crash mid-apply cannot loop the restore forever.
            Files.deleteIfExists(marker.toPath());

            if (!stagedArchive.isFile()) {
                plugin.getLogger().log(Level.SEVERE,
                    "[Checkpoint] Pending storage restore " + id + " has no staged archive; nothing applied.");
                deleteRecursively(pending);
                return;
            }

            File root = BlockStorageBackup.defaultRoot();

            int files = applyStorageArchiveAtomically(plugin, id, stagedArchive, root);

            deleteRecursively(pending);

            StorageConsistencyChecker.BlockScanResult scan =
                StorageConsistencyChecker.scanBlockFiles(root);

            plugin.getLogger().log(Level.INFO,
                "[Checkpoint] Applied staged storage restore " + id + ": " + files + " file(s) restored ("
                    + scan.describe() + ").");

            if (!scan.isHealthy()) {
                plugin.getLogger().log(Level.WARNING,
                    "[Checkpoint] The restored block data looks unhealthy: " + scan.describe()
                        + ". A pre-restore safety copy remains under the checkpoint safety directory.");
            }
        } catch (IOException | RuntimeException x) {
            deleteRecursively(pending);
            plugin.getLogger().log(Level.SEVERE,
                "[Checkpoint] Failed to apply staged storage restore " + id + "; the previous block"
                    + " data was left in place: " + describe(x));
        }
    }

    private boolean rollback(@Nonnull StorageProvider provider, @Nullable Checkpoint safety,
            @Nonnull List<String> steps, @Nonnull RestoreScope scope) {
        if (safety == null) {
            steps.add("No safety copy exists, the previous state was empty and nothing was rolled back");
            return false;
        }

        boolean rolled = true;
        File archive = safety.storageFile();

        if (scope.includesStorage() && (!safety.metadata().storage().isPresent() || archive == null)) {
            rolled = false;
            steps.add("Storage rollback unavailable in " + safety.id());
        } else if (scope.includesStorage()) {
            try {
                int files = restoreStorageArchive(archive);
                steps.add("Rolled the storage half back from " + safety.id() + ": " + files + " file(s)");
            } catch (IOException x) {
                rolled = false;
                steps.add("Storage rollback failed, restore " + safety.id() + " by hand: " + describe(x));
            }
        }

        File dump = safety.databaseFile();

        if (scope.includesDatabase() && (!safety.metadata().database().isPresent() || dump == null)) {
            rolled = false;
            steps.add("Database rollback unavailable in " + safety.id());
        } else if (scope.includesDatabase()) {
            DatabaseRestoreService.RestoreResult result = provider.supportsSql()
                ? DatabaseRestoreService.restoreSqlDump(dump, safety.id(), provider)
                : DatabaseRestoreService.restorePlayerArchive(dump, safety.id(), provider);

            if (result.success()) {
                steps.add("Rolled the database half back from " + safety.id());
            } else {
                rolled = false;
                steps.add("Database rollback failed, restore " + safety.id() + " by hand: "
                    + result.message());
            }
        }

        if (scope.includesDatabase()) {
            DatabaseRestoreService.invalidateCaches();
        }

        if (!rolled) {
            plugin.getLogger().log(Level.SEVERE,
                "A checkpoint restore failed and could not be rolled back. The pre-restore copy "
                    + safety.id() + " is intact in " + safety.directory().getPath());
        }

        return rolled;
    }

    /**
     * Reports whether Slimefun data changed since the newest checkpoint.
     *
     * <p>This never creates a checkpoint. It compares the newest modification time under the storage
     * sections and the current database record counts against what the newest checkpoint recorded,
     * combined with the two dirty markers set by the save path.
     *
     * @param provider The active storage provider
     *
     * @return The change status, never {@code null}
     */
    @Nonnull
    public CheckpointChangeStatus status(@Nonnull StorageProvider provider) {
        int max = getMaxCheckpoints();
        List<Checkpoint> checkpoints = list();

        if (checkpoints.isEmpty()) {
            return new CheckpointChangeStatus(null, null, true, true,
                "No checkpoint exists yet", 0, max);
        }

        CheckpointMetadata metadata = checkpoints.get(0).metadata();
        List<String> reasons = new ArrayList<>();
        boolean storageChanged = storageDirty.get();

        if (storageChanged) {
            reasons.add("storage was marked dirty by the save path");
        }

        long modified = newestStorageModification();

        if (modified > metadata.createdAt().toEpochMilli()) {
            storageChanged = true;
            reasons.add("storage files were written after the checkpoint was taken");
        }

        boolean databaseChanged = databaseDirty.get();

        if (databaseChanged) {
            reasons.add("database was marked dirty by the save path");
        }

        Map<String, Integer> current = currentDatabaseCounts(provider);

        if (current == null) {
            databaseChanged = true;
            reasons.add("the current database record count could not be read");
        } else if (!normalize(current).equals(normalize(metadata.database().counts()))) {
            databaseChanged = true;
            reasons.add("database record counts differ from the checkpoint");
        }

        String detail = reasons.isEmpty()
            ? "Nothing changed since " + metadata.checkpointId()
            : String.join("; ", reasons);

        return new CheckpointChangeStatus(metadata.checkpointId(), metadata.createdAt(),
            storageChanged, databaseChanged, detail, checkpoints.size(), max);
    }

    private static long newestStorageModification() {
        File root = LegacyFileBackup.defaultRoot();

        if (!root.isDirectory()) {
            return 0L;
        }

        long newest = 0L;

        for (String section : LegacyFileBackup.getSections()) {
            newest = Math.max(newest, newestModification(new File(root, section), 0));
        }

        return newest;
    }

    private static long newestModification(@Nonnull File file, int depth) {
        if (depth > 16 || !file.exists()) {
            return 0L;
        }

        if (file.isFile()) {
            return file.lastModified();
        }

        File[] children = file.listFiles();

        if (children == null) {
            return 0L;
        }

        long newest = 0L;

        for (File child : children) {
            newest = Math.max(newest, newestModification(child, depth + 1));
        }

        return newest;
    }

    @Nullable
    private Map<String, Integer> currentDatabaseCounts(@Nonnull StorageProvider provider) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (!provider.supportsSql()) {
            File root = PlayerFileBackup.defaultRoot();

            for (String section : PlayerFileBackup.getSections()) {
                counts.put(section, countFiles(new File(root, section), 0));
            }

            return counts;
        }

        Optional<Connection> borrowed;

        try {
            borrowed = provider.borrowConnection();
        } catch (Exception x) {
            return null;
        }

        if (borrowed.isEmpty()) {
            return null;
        }

        try (Connection conn = borrowed.get(); Statement statement = conn.createStatement()) {
            for (TableDefinition table : SchemaManager.getTables()) {
                try (ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table.tableName())) {
                    counts.put(table.tableName(), result.next() ? result.getInt(1) : 0);
                }
            }

            return counts;
        } catch (Exception x) {
            return null;
        }
    }

    private static int countFiles(@Nonnull File file, int depth) {
        if (depth > 16 || !file.exists()) {
            return 0;
        }

        if (file.isFile()) {
            return 1;
        }

        File[] children = file.listFiles();

        if (children == null) {
            return 0;
        }

        int total = 0;

        for (File child : children) {
            total += countFiles(child, depth + 1);
        }

        return total;
    }

    @Nonnull
    private static Map<String, Integer> normalize(@Nonnull Map<String, Integer> counts) {
        Map<String, Integer> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() != null && entry.getValue() != 0) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }

        return normalized;
    }

    @Nonnull
    private CheckpointResult finish(boolean success, @Nonnull String message,
            @Nullable Checkpoint checkpoint, @Nonnull List<String> steps, boolean automatic,
            long startedAt, @Nonnull List<String> purged) {

        CheckpointResult result = new CheckpointResult(success, message, checkpoint, steps,
            Instant.now(), automatic, System.currentTimeMillis() - startedAt, purged);
        lastResult = result;
        return result;
    }

    @Nonnull
    private CheckpointRestoreResult recordRestore(boolean success, @Nonnull String message,
            @Nonnull List<String> steps, @Nullable String safetyBackupId, int storageFiles,
            int databaseRows, boolean rolledBack, @Nonnull RestoreScope scope) {

        CheckpointRestoreResult result = new CheckpointRestoreResult(success, message, steps,
            safetyBackupId, storageFiles, databaseRows, rolledBack, scope);
        lastRestoreResult = result;

        if (!success) {
            plugin.getLogger().log(Level.WARNING, "Checkpoint restore aborted: " + message);
        }

        return result;
    }

    @Nonnull
    private static String summarize(@Nonnull CheckpointMetadata metadata) {
        return "storage " + metadata.storage().status().getDisplayName() + " with "
            + metadata.storage().totalEntries() + " file(s), database "
            + metadata.database().status().getDisplayName() + " with "
            + metadata.database().totalEntries() + " record(s)";
    }

    @Nonnull
    private static String describeCounts(@Nonnull Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "nothing captured";
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }

            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }

        return sb.toString();
    }

    private static int totalOf(@Nonnull Map<String, Integer> counts) {
        int total = 0;

        for (Integer value : counts.values()) {
            if (value != null) {
                total += value;
            }
        }

        return total;
    }

    private static boolean deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }

        return file.delete();
    }

    @Nonnull
    private static String serverVersion(@Nonnull Slimefun plugin) {
        return plugin.getServer().getName() + " " + plugin.getServer().getBukkitVersion();
    }

    @Nonnull
    private static String describe(@Nonnull Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
