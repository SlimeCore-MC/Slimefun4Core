package io.github.thebusybiscuit.slimefun4.storage.migration;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageConsistencyChecker;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.BlockStorageBackup;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.util.AtomicFiles;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.logging.Level;

/**
 * Copies every player record from the active backend into another one.
 *
 * <p>Nothing here goes through {@code loadPlayerData} or {@code PlayerData}. Both backends
 * drop an unknown research id, an unexpected backpack size, an item that will not
 * deserialize and a waypoint whose world is not loaded, silently, while loading. A migration
 * built on top of that would quietly shrink a player's data and still verify, because both
 * sides dropped the same rows. Records are therefore read and written directly, as raw
 * integers, raw bytes and raw world names, by {@link SqlRecordStore},
 * {@link LegacyRecordStore} and {@link SqlTableCopier}.
 *
 * <p>The source is only ever read. Every direction is a copy, so the worst outcome of a
 * failed run is a discarded destination and a backend that still holds everything it held
 * before. On top of that, two verified backups are taken before the first write: the source's
 * player data, and a {@link BackupFormat#BLOCK_FILES} archive of the block tree.
 *
 * <p>Block data itself is not migrated, because there is nothing to migrate: block storage
 * was never moved to this layer and lives in YAML under {@code data-storage/Slimefun} on
 * every provider, MySQL included. It is flushed and archived, never converted.
 *
 * <p>A real run is refused while any player is online, and the count is read a second time
 * after the source has been read and archived, so a player who joins mid-run stops the copy
 * before the destination is ever opened. A dry run is exempt, because it opens no destination
 * and writes nothing.
 *
 * @author SlimefunCore Contributors
 */
public final class StorageMigrator {

    private static final int FLUSH_TIMEOUT_SECONDS = 120;
    private static final int LISTED_BLOCK_PROBLEMS = 3;
    private static final String STAGING_DIRECTORY = "migration-staging";

    private static final String SECTION_RESEARCHES = "researches";
    private static final String SECTION_BACKPACKS = "backpacks";
    private static final String SECTION_ITEMS = "backpack-items";
    private static final String SECTION_WAYPOINTS = "waypoints";

    private StorageMigrator() {}

    /**
     * Everything an operator decides about one migration.
     *
     * @param destination   The provider to copy into
     * @param dryRun        When {@code true} nothing is written at all, not even a backup
     * @param overwrite     Whether writing into a non-empty destination is permitted
     * @param onlinePlayers How many players are online, supplied lazily so the rule is testable
     * @param storageReady  Whether the storage engine finished starting up
     */
    public record Request(
        @Nonnull StorageProviderType destination,
        boolean dryRun,
        boolean overwrite,
        @Nonnull IntSupplier onlinePlayers,
        boolean storageReady
    ) {}

    /**
     * The compact form of a {@link MigrationReport}, kept for API consumers that only need
     * counts and timing.
     *
     * @param succeeded   How many players were copied and verified
     * @param failed      How many players did not verify
     * @param failedUUIDs Exactly those players
     * @param duration    How long the run took
     */
    @Beta
    public record MigrationResult(int succeeded, int failed, @Nonnull List<UUID> failedUUIDs, @Nonnull Duration duration) {
    }

    /**
     * Reduces a report to its counts.
     *
     * @param report The report to summarize
     * @return The compact form of that report
     */
    @Nonnull
    public static MigrationResult summarize(@Nonnull MigrationReport report) {
        List<UUID> failed = List.copyOf(report.getMismatches().keySet());

        return new MigrationResult(
            Math.max(0, report.getPlayers() - failed.size()),
            failed.size(),
            failed,
            report.getDuration()
        );
    }

    /**
     * Runs one migration, or reports why it may not run.
     *
     * <p>This never throws. Every failure becomes a {@link MigrationReport} naming what went
     * wrong, so a command or a menu always has something to show. Only one migration may be
     * in flight at a time; a second call while the first is copying is refused.
     *
     * <p>Every step blocks, so this belongs on an async task. The one exception is the block
     * flush, which is handed back to the main thread.
     *
     * @param plugin   The plugin instance used for logging and for the main thread hop
     * @param source   The live provider, only ever read from
     * @param dbConfig The loaded {@code database.yml}
     * @param request  What the operator asked for
     * @return What happened
     */
    @Nonnull
    public static MigrationReport run(
        @Nonnull Slimefun plugin,
        @Nonnull StorageProvider source,
        @Nonnull Config dbConfig,
        @Nonnull Request request
    ) {
        MigrationReport.Builder report =
            new MigrationReport.Builder(source.getProviderType(), request.destination())
                .overwrite(request.overwrite());

        if (!MigrationPreconditions.tryBegin()) {
            return report
                .problem("Another migration is already running")
                .build(MigrationReport.Outcome.REFUSED, "A migration is already in progress");
        }

        try {
            return execute(plugin, source, dbConfig, request, report);
        } catch (MigrationException x) {
            plugin.getLogger().log(Level.SEVERE, "Migration aborted: {0}", x.getMessage());

            return report
                .problem(x.getMessage())
                .build(MigrationReport.Outcome.FAILED, x.getMessage());
        } catch (SQLException | IOException x) {
            String detail = describe(x);
            plugin.getLogger().log(Level.SEVERE, "Migration aborted: {0}", detail);

            return report
                .problem(detail)
                .build(MigrationReport.Outcome.FAILED, "Backend error: " + detail);
        } catch (RuntimeException x) {
            String detail = describe(x);
            plugin.getLogger().log(Level.SEVERE, "Migration aborted unexpectedly", x);

            return report
                .problem(detail)
                .build(MigrationReport.Outcome.FAILED, "Unexpected error: " + detail);
        } finally {
            MigrationPreconditions.finish();
        }
    }

    @Nonnull
    private static MigrationReport execute(
        @Nonnull Slimefun plugin,
        @Nonnull StorageProvider source,
        @Nonnull Config dbConfig,
        @Nonnull Request request,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException, SQLException, IOException {
        StorageProviderType sourceType = source.getProviderType();
        StorageProviderType destination = request.destination();
        int online = Math.max(0, request.onlinePlayers().getAsInt());

        MigrationPreconditions.Decision decision = MigrationPreconditions.evaluate(
            new MigrationPreconditions.Conditions(
                () -> online,
                request.storageReady(),
                source.isAvailable(),
                sourceType,
                destination,
                MigrationTargets.missingKeys(dbConfig, destination),
                request.dryRun()));

        if (!decision.allowed()) {
            return report
                .problems(decision.problems())
                .build(MigrationReport.Outcome.REFUSED, decision.describe());
        }

        if (online > 0) {
            report.warning(online + " player(s) online (live snapshot). Server must be empty to run a real migration");
        }

        File dataRoot = BlockStorageBackup.defaultRoot();
        PlayerIndex.Index index = enumerate(source, sourceType, dataRoot);

        report.players(index.size());

        for (String unreadable : index.unreadable()) {
            report.warning("Skipped invalid player UUID: " + unreadable);
        }

        if (index.uuids().isEmpty()) {
            return report
                .problem("Source backend has no player data to migrate")
                .build(MigrationReport.Outcome.REFUSED, "Nothing to migrate");
        }

        log(plugin, "Reading " + index.size() + " player(s) from " + name(sourceType));

        Map<UUID, String> sourceDigests = new LinkedHashMap<>();

        readSource(source, sourceType, dataRoot, index.uuids(), sourceDigests, report);

        if (request.dryRun()) {
            return report.build(MigrationReport.Outcome.DRY_RUN, report.hasProblems()
                ? report.problemCount() + " issue(s) found; nothing was written"
                : "All records converted successfully; nothing was written.");
        }

        if (report.hasProblems()) {
            return report.build(MigrationReport.Outcome.FAILED, "Aborted before writing: "
                + report.problemCount() + " record(s) failed conversion");
        }

        log(plugin, "Flushing and archiving block data");
        flushBlockData(plugin);
        archiveBlocks(plugin, source, dbConfig, report);

        log(plugin, "Archiving player data from " + name(sourceType));
        archiveSource(source, dbConfig, report);

        int joined = Math.max(0, request.onlinePlayers().getAsInt());

        if (joined > 0) {
            return report
                .problem(joined + " player(s) joined while reading source; aborted without writing to "
                    + name(destination))
                .build(MigrationReport.Outcome.REFUSED, joined
                    + " player(s) joined mid-run; destination was never opened");
        }

        log(plugin, "Connecting to destination " + name(destination));
        StorageProvider target = MigrationTargets.open(plugin, dbConfig, destination);

        try {
            if (target.supportsSql()) {
                return copyIntoSql(plugin, source, sourceType, target, dbConfig,
                    request, index.uuids(), dataRoot, sourceDigests, report);
            }

            return copyIntoLegacy(plugin, source, sourceType, target, dbConfig,
                request, index.uuids(), dataRoot, sourceDigests, report);
        } finally {
            target.shutdown();
        }
    }

    @Nonnull
    private static MigrationReport copyIntoSql(
        @Nonnull Slimefun plugin,
        @Nonnull StorageProvider source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull StorageProvider target,
        @Nonnull Config dbConfig,
        @Nonnull Request request,
        @Nonnull List<UUID> uuids,
        @Nonnull File dataRoot,
        @Nonnull Map<UUID, String> sourceDigests,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException, SQLException, IOException {
        StorageProviderType destination = target.getProviderType();

        try (Connection connection = connect(target)) {
            int existing = SqlRecordStore.countPlayerRows(connection, destination);
            MigrationPreconditions.Decision decision =
                MigrationPreconditions.checkDestination(existing, request.overwrite());

            if (!decision.allowed()) {
                return report
                    .problems(decision.problems())
                    .build(MigrationReport.Outcome.REFUSED, decision.describe());
            }

            if (existing > 0) {
                archiveDestination(target, dbConfig, report);
            }

            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try {
                if (existing > 0) {
                    report.warning("Cleared " + SqlTableCopier.clear(connection, destination)
                        + " existing row(s) from destination");
                }

                log(plugin, "Copying " + uuids.size() + " player(s) to " + name(destination));

                report.copiedEntries(sourceType == StorageProviderType.LEGACY
                    ? writeRecords(connection, destination, dataRoot, uuids)
                    : copyTables(source, sourceType, connection, destination));

                connection.commit();
            } catch (MigrationException x) {
                rollback(plugin, connection);

                throw x;
            } catch (SQLException | RuntimeException x) {
                rollback(plugin, connection);

                throw new MigrationException(
                    "Destination transaction rolled back; nothing was committed", x);
            } finally {
                connection.setAutoCommit(autoCommit);
            }

            log(plugin, "Verifying " + name(destination));

            Map<UUID, String> destinationDigests = new LinkedHashMap<>();
            Map<String, Integer> counts = newSectionCounts();

            for (UUID uuid : uuids) {
                accept(SqlRecordStore.read(connection, destination, uuid), destinationDigests, counts);
            }

            if (sourceType != StorageProviderType.LEGACY) {
                try (Connection origin = connect(source)) {
                    verifyTables(origin, sourceType, connection, destination, report);
                }
            }

            return complete(plugin, report, counts, sourceDigests, destinationDigests);
        }
    }

    @Nonnull
    private static MigrationReport copyIntoLegacy(
        @Nonnull Slimefun plugin,
        @Nonnull StorageProvider source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull StorageProvider target,
        @Nonnull Config dbConfig,
        @Nonnull Request request,
        @Nonnull List<UUID> uuids,
        @Nonnull File dataRoot,
        @Nonnull Map<UUID, String> sourceDigests,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException, SQLException, IOException {
        PlayerIndex.Index existing = PlayerIndex.fromLegacy(dataRoot);
        MigrationPreconditions.Decision decision =
            MigrationPreconditions.checkDestination(existing.size(), request.overwrite());

        if (!decision.allowed()) {
            return report
                .problems(decision.problems())
                .build(MigrationReport.Outcome.REFUSED, decision.describe());
        }

        if (!existing.uuids().isEmpty()) {
            archiveDestination(target, dbConfig, report);
            warnUntouched(existing.uuids(), uuids, dataRoot, report);
        }

        File staging = new File(dataRoot, STAGING_DIRECTORY);

        deleteTree(staging);
        log(plugin, "Converting " + uuids.size() + " player(s) in " + staging.getPath());

        int written = 0;

        try (Connection connection = connect(source)) {
            for (UUID uuid : uuids) {
                written += LegacyRecordStore.write(staging, SqlRecordStore.read(connection, sourceType, uuid));
            }
        }

        report.copiedEntries(written);

        log(plugin, "Moving converted files to " + dataRoot.getPath());
        promote(staging, dataRoot, uuids);
        deleteTree(staging);

        log(plugin, "Verifying " + name(StorageProviderType.LEGACY));

        Map<UUID, String> destinationDigests = new LinkedHashMap<>();
        Map<String, Integer> counts = newSectionCounts();

        for (UUID uuid : uuids) {
            accept(LegacyRecordStore.read(dataRoot, uuid), destinationDigests, counts);
        }

        return complete(plugin, report, counts, sourceDigests, destinationDigests);
    }

    private static void warnUntouched(
        @Nonnull List<UUID> present,
        @Nonnull List<UUID> incoming,
        @Nonnull File dataRoot,
        @Nonnull MigrationReport.Builder report
    ) {
        Set<UUID> migrated = new HashSet<>(incoming);
        int untouched = 0;

        for (UUID uuid : present) {
            if (!migrated.contains(uuid)) {
                untouched++;
            }
        }

        if (untouched > 0) {
            report.warning(untouched + " player(s) in " + dataRoot.getPath()
                + " not found in source; left unchanged");
        }
    }

    private static void promote(
        @Nonnull File staging,
        @Nonnull File dataRoot,
        @Nonnull List<UUID> uuids
    ) throws IOException {
        for (UUID uuid : uuids) {
            replace(LegacyRecordStore.playerFile(staging, uuid), LegacyRecordStore.playerFile(dataRoot, uuid));
            replace(LegacyRecordStore.waypointsFile(staging, uuid), LegacyRecordStore.waypointsFile(dataRoot, uuid));
        }
    }

    private static void replace(@Nonnull File staged, @Nonnull File live) throws IOException {
        if (!staged.isFile()) {
            Files.deleteIfExists(live.toPath());

            return;
        }

        File parent = live.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        AtomicFiles.moveReplace(staged, live);
    }

    private static void deleteTree(@Nonnull File file) throws MigrationException {
        File[] children = file.listFiles();

        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException x) {
            throw new MigrationException("Failed to delete " + file.getPath(), x);
        }
    }

    @Nonnull
    private static PlayerIndex.Index enumerate(
        @Nonnull StorageProvider source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull File dataRoot
    ) throws MigrationException, SQLException {
        if (sourceType == StorageProviderType.LEGACY) {
            return PlayerIndex.fromLegacy(dataRoot);
        }

        try (Connection connection = connect(source)) {
            return PlayerIndex.fromSql(connection, sourceType);
        }
    }

    private static void readSource(
        @Nonnull StorageProvider source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull File dataRoot,
        @Nonnull List<UUID> uuids,
        @Nonnull Map<UUID, String> digests,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException, SQLException {
        Map<String, Integer> counts = newSectionCounts();

        if (sourceType == StorageProviderType.LEGACY) {
            for (UUID uuid : uuids) {
                try {
                    accept(LegacyRecordStore.read(dataRoot, uuid), digests, counts);
                } catch (MigrationException x) {
                    report.problem(x.getMessage());
                }
            }
        } else {
            try (Connection connection = connect(source)) {
                for (UUID uuid : uuids) {
                    try {
                        accept(SqlRecordStore.read(connection, sourceType, uuid), digests, counts);
                    } catch (SQLException x) {
                        report.problem("Player " + uuid + ": " + describe(x));
                    }
                }
            }
        }

        counts.forEach(report::sourceCount);
    }

    private static void accept(
        @Nonnull PlayerRecords records,
        @Nonnull Map<UUID, String> digests,
        @Nonnull Map<String, Integer> counts
    ) {
        digests.put(records.getUuid(), records.digest());

        int items = 0;

        for (PlayerRecords.BackpackRecord backpack : records.getBackpacks()) {
            items += backpack.items().size();
        }

        counts.merge(SECTION_RESEARCHES, records.getResearches().size(), Integer::sum);
        counts.merge(SECTION_BACKPACKS, records.getBackpacks().size(), Integer::sum);
        counts.merge(SECTION_ITEMS, items, Integer::sum);
        counts.merge(SECTION_WAYPOINTS, records.getWaypoints().size(), Integer::sum);
    }

    @Nonnull
    private static Map<String, Integer> newSectionCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();

        counts.put(SECTION_RESEARCHES, 0);
        counts.put(SECTION_BACKPACKS, 0);
        counts.put(SECTION_ITEMS, 0);
        counts.put(SECTION_WAYPOINTS, 0);

        return counts;
    }

    private static int writeRecords(
        @Nonnull Connection connection,
        @Nonnull StorageProviderType destination,
        @Nonnull File dataRoot,
        @Nonnull List<UUID> uuids
    ) throws MigrationException, SQLException {
        int rows = 0;

        for (UUID uuid : uuids) {
            rows += SqlRecordStore.write(connection, destination, LegacyRecordStore.read(dataRoot, uuid));
        }

        return rows;
    }

    private static int copyTables(
        @Nonnull StorageProvider source,
        @Nonnull StorageProviderType sourceType,
        @Nonnull Connection destination,
        @Nonnull StorageProviderType destinationType
    ) throws MigrationException, SQLException {
        int rows = 0;

        try (Connection origin = connect(source)) {
            for (int copied : SqlTableCopier.copyAll(origin, sourceType, destination, destinationType).values()) {
                rows += copied;
            }
        }

        return rows;
    }

    private static void verifyTables(
        @Nonnull Connection origin,
        @Nonnull StorageProviderType sourceType,
        @Nonnull Connection destination,
        @Nonnull StorageProviderType destinationType,
        @Nonnull MigrationReport.Builder report
    ) throws SQLException {
        Map<String, Integer> before = SqlTableCopier.countAll(origin, sourceType);
        Map<String, Integer> after = SqlTableCopier.countAll(destination, destinationType);

        report.destinationCounts(after);

        for (Map.Entry<String, Integer> entry : before.entrySet()) {
            int actual = after.getOrDefault(entry.getKey(), 0);

            if (actual != entry.getValue()) {
                report.problem("Row count mismatch in " + entry.getKey() + ": destination has " + actual
                    + ", source has " + entry.getValue());
            }
        }
    }

    @Nonnull
    private static Connection connect(@Nonnull StorageProvider provider) throws MigrationException {
        try {
            return provider.borrowConnection().orElseThrow(() -> new MigrationException(
                provider.getProviderName() + " failed to provide a database connection"));
        } catch (SQLException x) {
            throw new MigrationException("Failed to connect to " + provider.getProviderName(), x);
        }
    }

    private static void rollback(@Nonnull Slimefun plugin, @Nonnull Connection connection) {
        try {
            connection.rollback();
            plugin.getLogger().log(Level.WARNING,
                "Migration: destination transaction rolled back; no changes committed");
        } catch (SQLException x) {
            plugin.getLogger().log(Level.SEVERE, "Migration: failed to rollback destination transaction", x);
        }
    }

    /**
     * Builds the online player count used by {@link MigrationPreconditions}.
     *
     * <p>The count is read on the main thread, because {@code getOnlinePlayers} is not safe to
     * touch from anywhere else. A hop that is interrupted or times out reports
     * {@link Integer#MAX_VALUE}, so a count that could not be established refuses the migration
     * instead of permitting one.
     *
     * @param plugin The plugin instance used for the main thread hop
     * @return A supplier safe to call from the migration thread
     */
    @Nonnull
    public static IntSupplier onlinePlayers(@Nonnull Slimefun plugin) {
        return () -> {
            if (plugin.getServer().isPrimaryThread()) {
                return plugin.getServer().getOnlinePlayers().size();
            }

            try {
                return plugin.getServer().getScheduler()
                    .callSyncMethod(plugin, () -> plugin.getServer().getOnlinePlayers().size())
                    .get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();

                return Integer.MAX_VALUE;
            } catch (ExecutionException | TimeoutException x) {
                plugin.getLogger().log(Level.WARNING,
                    "Migration: failed to check online player count; aborting", x);

                return Integer.MAX_VALUE;
            }
        };
    }

    /**
     * Writes every pending block change to disk.
     *
     * <p>This is the exact sequence {@code AutoSavingService} uses: {@code computeChanges()} on
     * every world, {@code save()} on the worlds that reported changes, then
     * {@code BlockStorage.saveChunks()}. {@code BlockStorage} itself is untouched.
     *
     * <p>The flush always happens on the main thread, so the resulting archive cannot catch a
     * half-written map. A caller already on the main thread runs it directly; anyone else waits
     * up to {@value #FLUSH_TIMEOUT_SECONDS} seconds for the hop.
     *
     * @param plugin The plugin instance used for the main thread hop
     * @throws MigrationException If the flush could not be completed
     */
    public static void flushBlockData(@Nonnull Slimefun plugin) throws MigrationException {
        if (plugin.getServer().isPrimaryThread()) {
            flushBlocksNow();

            return;
        }

        try {
            plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                flushBlocksNow();

                return Boolean.TRUE;
            }).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();

            throw new MigrationException("Interrupted while flushing block data", x);
        } catch (ExecutionException | TimeoutException x) {
            throw new MigrationException("Failed to flush block data; nothing was written", x);
        }
    }

    private static void flushBlocksNow() {
        Set<BlockStorage> worlds = new HashSet<>();

        for (BlockStorage storage : Slimefun.getRegistry().getWorlds().values()) {
            storage.computeChanges();

            if (storage.getChanges() > 0) {
                worlds.add(storage);
            }
        }

        for (BlockStorage storage : worlds) {
            storage.save();
        }

        BlockStorage.saveChunks();
    }

    private static void archiveBlocks(
        @Nonnull Slimefun plugin,
        @Nonnull StorageProvider source,
        @Nonnull Config dbConfig,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException {
        File root = BlockStorageBackup.defaultRoot();

        if (!BlockStorageBackup.hasData(root)) {
            report.warning("No block data found in " + root.getPath()
                + "; skipped block backup");

            return;
        }

        report.blockBackupId(backup(source, dbConfig, BackupFormat.BLOCK_FILES).metadata().backupId());

        StorageConsistencyChecker.BlockScanResult scan = StorageConsistencyChecker.scanBlockFiles(root);

        if (scan.isHealthy()) {
            report.warning("Block integrity: " + scan.describe()
                + " (blocks archived in place, not converted)");

            return;
        }

        report.warning("Block integrity warning: " + scan.describe()
            + ". Files archived as-is; repair recommended");

        List<String> unreadable = scan.unreadable();

        for (String path : unreadable) {
            plugin.getLogger().log(Level.WARNING, "Migration: unreadable block file: {0}", path);
        }

        for (int index = 0; index < Math.min(LISTED_BLOCK_PROBLEMS, unreadable.size()); index++) {
            report.warning("Unreadable block file: " + unreadable.get(index));
        }

        if (unreadable.size() > LISTED_BLOCK_PROBLEMS) {
            report.warning((unreadable.size() - LISTED_BLOCK_PROBLEMS)
                + " more unreadable block file(s) logged to console");
        }
    }

    private static void archiveSource(
        @Nonnull StorageProvider source,
        @Nonnull Config dbConfig,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException {
        report.playerBackupId(backup(source, dbConfig, formatOf(source)).metadata().backupId());
    }

    private static void archiveDestination(
        @Nonnull StorageProvider target,
        @Nonnull Config dbConfig,
        @Nonnull MigrationReport.Builder report
    ) throws MigrationException {
        report.warning("Destination not empty; created backup "
            + backup(target, dbConfig, formatOf(target)).metadata().backupId()
            + " before overwriting");
    }

    @Nonnull
    private static BackupFormat formatOf(@Nonnull StorageProvider provider) {
        return provider.supportsSql() ? BackupFormat.SQL_JSON : BackupFormat.LEGACY_FILES;
    }

    @Nonnull
    private static BackupEntry backup(
        @Nonnull StorageProvider provider,
        @Nonnull Config dbConfig,
        @Nonnull BackupFormat format
    ) throws MigrationException {
        try {
            BackupEntry entry = DatabaseBackupService.createBackup(
                provider,
                DatabaseBackupService.resolveDirectory(dbConfig),
                dbConfig.getBoolean("backup.compress"),
                0,
                false,
                format);

            if (!DatabaseBackupService.verifyBackup(entry)) {
                throw new MigrationException("Backup verification failed for " + entry.metadata().backupId()
                    + "; nothing was written");
            }

            return entry;
        } catch (IOException x) {
            throw new MigrationException("Pre-migration backup of " + provider.getProviderName()
                + " failed; nothing was written", x);
        }
    }

    @Nonnull
    private static MigrationReport complete(
        @Nonnull Slimefun plugin,
        @Nonnull MigrationReport.Builder report,
        @Nonnull Map<String, Integer> destinationCounts,
        @Nonnull Map<UUID, String> sourceDigests,
        @Nonnull Map<UUID, String> destinationDigests
    ) {
        destinationCounts.forEach(report::destinationCount);

        for (Map.Entry<UUID, String> entry : sourceDigests.entrySet()) {
            String actual = destinationDigests.get(entry.getKey());

            if (actual == null) {
                report.mismatch(entry.getKey(), "missing in destination");
            } else if (!actual.equals(entry.getValue())) {
                report.mismatch(entry.getKey(), "data mismatch between source and destination");
            }
        }

        int cached = invalidateCache();

        if (cached > 0) {
            report.warning("Invalidated " + cached + " cached player record(s)");
        }

        int mismatches = report.mismatchCount();

        if (mismatches > 0 || report.hasProblems()) {
            String detail = (mismatches > 0
                ? mismatches + " player(s) failed verification"
                : report.problemCount() + " issue(s) failed verification")
                + "; destination is incomplete and must not be activated";

            log(plugin, detail);

            return report.build(MigrationReport.Outcome.FAILED, detail);
        }

        log(plugin, "Verified " + sourceDigests.size() + " player(s)");

        return report.build(MigrationReport.Outcome.SUCCESS,
            "All records copied and verified; run '/sf storage migrate apply' to activate destination");
    }

    private static int invalidateCache() {
        return Slimefun.getPlayerStorage() instanceof StorageCache cache ? cache.invalidateAll() : 0;
    }

    private static void log(@Nonnull Slimefun plugin, @Nonnull String message) {
        plugin.getLogger().log(Level.INFO, "Migration: {0}", message);
    }

    @Nonnull
    private static String name(@Nonnull StorageProviderType type) {
        return type.name().toLowerCase(Locale.ROOT);
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
