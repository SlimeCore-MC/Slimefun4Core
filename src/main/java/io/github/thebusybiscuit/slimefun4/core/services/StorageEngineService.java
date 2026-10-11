package io.github.thebusybiscuit.slimefun4.core.services;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.Storage;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageException;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backend.legacy.LegacyStorage;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointResult;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.DailyBackupSchedule;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.WorldBackupService;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationReport;
import io.github.thebusybiscuit.slimefun4.storage.migration.StorageMigrator;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderFactory;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaRepair;

import org.apache.commons.lang.Validate;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central service that owns the entire storage engine lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load {@code database.yml} and create the correct {@link StorageProvider}</li>
 *   <li>Wrap the provider in a {@link StorageCache}</li>
 *   <li>Run schema initialization and integrity verification on startup</li>
 *   <li>Schedule automatic database backups</li>
 *   <li>Print the startup banner</li>
 *   <li>Gracefully shut down the pool on plugin disable</li>
 * </ul>
 *
 * <p>Obtain the active {@link Storage} via {@link #getStorage()}.
 * Use {@link #getProvider()} for health metrics and admin commands.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public class StorageEngineService {

    private static final String SEPARATOR = "======================================================";

    /**
     * Poll period for fixed-time backup schedules: once a minute (in ticks). Each configured UTC
     * minute fires at most once, so a one-minute poll is enough to hit every scheduled time.
     */
    private static final long MINUTE_CHECK_TICKS = 60L * 20L;

    private final Slimefun plugin;

    private Config dbConfig;
    private StorageProvider provider;
    private Storage storage;
    private @Nullable volatile BukkitTask backupTask;
    private @Nullable volatile BukkitTask checkpointTask;
    private @Nullable volatile BukkitTask worldBackupTask;
    private @Nullable volatile CheckpointService checkpointService;
    private @Nullable volatile WorldBackupService worldBackupService;
    private int verifiedTableCount = 0;
    private IntegrityStatus integrityStatus = IntegrityStatus.UNKNOWN;
    private List<String> integrityIssueList = List.of();
    private volatile @Nullable BackupOutcome lastBackupResult;
    private volatile @Nullable MigrationReport lastMigrationResult;
    private volatile @Nullable MigrationReport lastSuccessfulMigration;

    /**
     * The verification state of the database schema.
     */
    public enum IntegrityStatus {

        /**
         * Verification has not run yet.
         */
        UNKNOWN("Unknown"),

        /**
         * The active provider is not SQL-backed, so there is no schema to verify.
         */
        NOT_APPLICABLE("Not applicable"),

        /**
         * Verification is disabled by {@code verify-schema} in {@code database.yml}.
         */
        SKIPPED("Skipped (verify-schema: false)"),

        /**
         * Every managed table and column was found intact.
         */
        PASSED("Passed"),

        /**
         * At least one table or column is missing or malformed.
         */
        ISSUES("Issues found"),

        /**
         * Verification itself could not run, for example because no connection was available.
         */
        FAILED("Could not verify");

        private final String label;

        IntegrityStatus(@Nonnull String label) {
            this.label = label;
        }

        /**
         * Returns the human readable label for this state.
         *
         * @return The display label
         */
        @Nonnull
        public String getLabel() {
            return label;
        }
    }

    /**
     * The outcome of the most recent backup attempt.
     *
     * @param success   Whether the backup succeeded
     * @param detail    A short description, either the backup id or the failure reason
     * @param entries   The number of rows or files captured, {@code 0} on failure
     * @param timestamp When the attempt finished
     * @param automatic Whether the attempt was made by the scheduler
     */
    public record BackupOutcome(
        boolean success,
        @Nonnull String detail,
        int entries,
        @Nonnull Instant timestamp,
        boolean automatic
    ) {}

    /**
     * Creates and fully initializes a new {@link StorageEngineService}.
     *
     * <p>This constructor will:
     * <ol>
     *   <li>Load {@code database.yml}</li>
     *   <li>Detect and apply the backward-compat shim from {@code config.yml}</li>
     *   <li>Build and initialize the configured provider</li>
     *   <li>Wrap the provider in a {@link StorageCache}</li>
     *   <li>Run schema verification (and repair if needed)</li>
     *   <li>Schedule automatic backups</li>
     *   <li>Print the startup banner</li>
     * </ol>
     *
     * @param plugin The current {@link Slimefun} instance
     */
    public StorageEngineService(@Nonnull Slimefun plugin) {
        Validate.notNull(plugin, "Plugin must not be null");
        this.plugin = plugin;
        initialize();
    }
    // Public API
    /**
     * Returns the active storage backend (a {@link StorageCache} wrapping the provider).
     *
     * @return The current {@link Storage}
     */
    @Nonnull
    public Storage getStorage() {
        return storage;
    }

    /**
     * Returns the underlying {@link StorageProvider} (not cache-wrapped).
     *
     * @return The current provider
     */
    @Nonnull
    public StorageProvider getProvider() {
        return provider;
    }

    /**
     * Returns the loaded {@code database.yml} config.
     *
     * @return The database config
     */
    @Nonnull
    public Config getDbConfig() {
        return dbConfig;
    }

    /**
     * Returns the current cache statistics from the {@link StorageCache}.
     *
     * @return Cache stats, or {@code null} if the storage is not a {@link StorageCache}
     */
    @Nullable
    public StorageCache.CacheStats getCacheStats() {
        if (storage instanceof StorageCache cache) {
            return cache.getCacheStats();
        }
        return null;
    }

    /**
     * Returns the current connection pool statistics.
     *
     * @return Pool stats
     */
    @Nonnull
    public StoragePoolStats getPoolStats() {
        return provider.getPoolStats();
    }

    /**
     * Measures the current database ping latency.
     *
     * @return Round-trip ms, or {@code -1} if unavailable
     */
    public long getPing() {
        return provider.pingMillis();
    }

    /**
     * Returns a one-line status string for the provider.
     *
     * @return e.g. {@literal "Connected"}, {@literal "Unavailable"}, or {@literal "Legacy YAML"}
     */
    @Nonnull
    public String getStatus() {
        if (provider.getProviderType() == StorageProviderType.LEGACY) {
            return "Legacy YAML";
        }
        return provider.isAvailable() ? "Connected" : "Unavailable";
    }

    /**
     * Returns the number of managed tables that were confirmed to exist.
     *
     * @return The verified table count, {@code 0} for file-backed providers
     */
    public int getVerifiedTableCount() {
        return verifiedTableCount;
    }

    /**
     * Returns the verification state of the database schema.
     *
     * @return The current {@link IntegrityStatus}
     */
    @Nonnull
    public IntegrityStatus getIntegrityStatus() {
        return integrityStatus;
    }

    /**
     * Returns the issues reported by the last schema verification.
     *
     * @return An immutable list of issue descriptions, empty when the schema is intact
     */
    @Nonnull
    public List<String> getIntegrityIssues() {
        return integrityIssueList;
    }

    /**
     * Returns a display string describing the schema integrity, including the provider
     * reason when verification does not apply.
     *
     * @return e.g. {@literal "Passed"}, {@literal "Not applicable (Legacy YAML)"},
     *         or {@literal "2 issue(s) found"}
     */
    @Nonnull
    public String getIntegrityDescription() {
        return switch (integrityStatus) {
            case NOT_APPLICABLE -> "Not applicable (" + provider.getProviderName() + ")";
            case ISSUES -> integrityIssueList.size() + " issue(s) found";
            case FAILED -> integrityIssueList.isEmpty()
                ? IntegrityStatus.FAILED.getLabel()
                : IntegrityStatus.FAILED.getLabel() + " (" + integrityIssueList.get(0) + ")";
            default -> integrityStatus.getLabel();
        };
    }

    /**
     * Returns the outcome of the most recent backup attempt made in this session.
     *
     * @return The last outcome, or {@code null} if no backup has been attempted yet
     */
    @Nullable
    public BackupOutcome getLastBackupResult() {
        return lastBackupResult;
    }

    /**
     * Returns the checkpoint service.
     *
     * <p>A checkpoint pairs an archive of the file-based Slimefun data with an export of the
     * database-owned player data, so it is owned by this service rather than by either half.
     *
     * @return The checkpoint service, or {@code null} before initialization or after shutdown
     */
    @Nullable
    public CheckpointService getCheckpointService() {
        return checkpointService;
    }

    /**
     * Returns the world backup service.
     *
     * <p>World backups are a separate pool from checkpoints: they archive the Minecraft world
     * folders, have their own retention and opt-in toggle, and are restored on the next startup.
     *
     * @return The world backup service, or {@code null} before initialization or after shutdown
     */
    @Nullable
    public WorldBackupService getWorldBackupService() {
        return worldBackupService;
    }

    /**
     * Returns the report of the most recent migration attempt made in this session.
     *
     * @return The last report, or {@code null} if no migration has been attempted yet
     */
    @Nullable
    public MigrationReport getLastMigrationResult() {
        return lastMigrationResult;
    }

    /**
     * Returns the report of the most recent migration that copied and verified everything.
     *
     * <p>Kept separately from {@link #getLastMigrationResult()} so that a dry run made after a
     * real migration cannot hide the verified result that {@code migrate apply} needs.
     *
     * @return The last verified report, or {@code null} if no migration has succeeded yet
     */
    @Nullable
    public MigrationReport getLastSuccessfulMigration() {
        return lastSuccessfulMigration;
    }

    /**
     * Publishes the report of a migration attempt.
     *
     * <p>Called by the migration command once {@link StorageMigrator} returns, so the command
     * output, the GUI and {@code migrate apply} all read the same immutable report.
     *
     * @param report The report to publish
     */
    public void setLastMigrationResult(@Nonnull MigrationReport report) {
        this.lastMigrationResult = report;

        if (report.isSuccess()) {
            this.lastSuccessfulMigration = report;
        }
    }

    /**
     * Shuts down the storage engine gracefully.
     *
     * <p>Cancels the automatic backup task, then calls {@link Storage#shutdown()}
     * on the wrapped storage (which in turn shuts down the provider and pool).
     */
    public void shutdown() {
        if (backupTask != null) {
            backupTask.cancel();
            backupTask = null;
        }
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        if (worldBackupTask != null) {
            worldBackupTask.cancel();
            worldBackupTask = null;
        }
        checkpointService = null;
        worldBackupService = null;
        storage.shutdown();
    }
    // Private — initialization
    private void initialize() {
        Logger logger = plugin.getLogger();

        // 1. Load database.yml (creates the file from the bundled default if absent)
        dbConfig = new Config(plugin, "database.yml");

        java.io.File dbFile = new java.io.File(plugin.getDataFolder(), "database.yml");
        if (!dbFile.exists()) {
            logger.log(Level.WARNING, "[Storage] Dough Config failed to extract database.yml! Attempting manual extraction...");
            java.io.InputStream in = plugin.getResource("database.yml");
            if (in == null) {
                logger.log(Level.SEVERE, "[Storage] CRITICAL: database.yml is missing from the compiled plugin jar!");
            } else {
                try {
                    java.nio.file.Files.copy(in, dbFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    logger.log(Level.INFO, "[Storage] Successfully extracted database.yml manually.");
                    // Reload the config now that the file actually exists
                    dbConfig = new Config(plugin, "database.yml");
                } catch (java.io.IOException e) {
                    logger.log(Level.SEVERE, "[Storage] Failed to manually extract database.yml!", e);
                }
            }
        }

        // 2. Backward-compat shim: if database.yml provider == "legacy" but config.yml
        //    storage.backend is something non-legacy, respect it (and log a deprecation).
        applyBackwardCompatShim();

        // 3. Build the provider
        provider = StorageProviderFactory.create(plugin, dbConfig);

        // 4. Attempt to initialize the provider; fall back to LegacyStorage on failure
        try {
            provider.initialize();
        } catch (StorageException x) {
            logger.log(Level.SEVERE,
                "[Storage] Failed to initialize provider \"" + provider.getProviderName()
                + "\" — falling back to Legacy YAML storage.", x);
            provider = buildFallbackProvider();
        }

        // 5. Wrap in StorageCache
        boolean cacheEnabled  = dbConfig.getBoolean("cache.enabled");
        int     cacheMaxSize  = dbConfig.getInt("cache.max-size");
        long    cacheTtlMin   = safeGetLong("cache.expire-after-minutes", 30L);

        if (cacheEnabled && cacheMaxSize > 0) {
            long ttlMs = cacheTtlMin > 0 ? cacheTtlMin * 60_000L : Long.MAX_VALUE;
            storage = new StorageCache(provider, cacheMaxSize, ttlMs);
        } else {
            storage = new StorageCache(provider); // unbounded, no TTL
        }

        // 6. Schema verification (SQL providers only)
        runSchemaVerification();

        checkpointService = new CheckpointService(plugin, dbConfig);
        worldBackupService = new WorldBackupService(plugin, dbConfig, checkpointService);

        // 7. Schedule automatic backups
        scheduleBackups();
        scheduleCheckpoints();
        scheduleWorldBackups();

        // 8. Print startup banner
        printBanner();
    }

    /**
     * Reads a long value from {@code database.yml}, tolerating missing or
     * malformed keys instead of letting {@code dough}'s {@link Config#getLong(String)}
     * throw a {@link NumberFormatException} (which happens when the key is
     * absent — e.g. an older {@code database.yml} predating a schema change).
     *
     * <p>On failure, this logs a one-time warning, writes the default back
     * into the config so the file self-heals, and returns the default.
     *
     * @param path         The dotted config path
     * @param defaultValue The value to fall back to (and persist) if missing/invalid
     * @return The parsed value, or {@code defaultValue} if the key was missing/invalid
     */
    private long safeGetLong(@Nonnull String path, long defaultValue) {
        try {
            return dbConfig.getLong(path);
        } catch (NumberFormatException x) {
            plugin.getLogger().log(Level.WARNING,
                "[Storage] Config key \"" + path + "\" in database.yml is missing or invalid — "
                + "using default (" + defaultValue + ") and repairing the file.");
            dbConfig.setValue(path, defaultValue);
            return defaultValue;
        }
    }

    private void applyBackwardCompatShim() {
        String dbYmlProvider  = dbConfig.getString("provider");
        String cfgYmlBackend  = Slimefun.getCfg().getString("storage.backend");

        // If database.yml has never been customized (provider == null or "legacy")
        // AND config.yml has a non-legacy backend, migrate it automatically.
        if ((dbYmlProvider == null || dbYmlProvider.equalsIgnoreCase("legacy"))
                && cfgYmlBackend != null && !cfgYmlBackend.equalsIgnoreCase("legacy")) {
            plugin.getLogger().log(Level.WARNING,
                "[Storage] DEPRECATION: Found 'storage.backend: {0}' in config.yml. "
                + "Please move all storage settings to database.yml. "
                + "Migrating automatically this time.",
                cfgYmlBackend);
            dbConfig.setValue("provider", cfgYmlBackend.toLowerCase(Locale.ROOT));
        }
    }

    private StorageProvider buildFallbackProvider() {
        LegacyStorage fallback = new LegacyStorage();
        try {
            fallback.initialize();
        } catch (StorageException x) {
            plugin.getLogger().log(Level.SEVERE,
                "[Storage] Legacy YAML fallback also failed to initialize.", x);
        }
        return StorageProviderFactory.LegacyStorageAdapter_create(fallback);
    }

    private void runSchemaVerification() {
        if (!provider.supportsSql()) {
            verifiedTableCount = 0;
            integrityStatus = IntegrityStatus.NOT_APPLICABLE;
            integrityIssueList = List.of();
            return;
        }

        if (!dbConfig.getBoolean("verify-schema")) {
            verifiedTableCount = SchemaManager.getTables().size();
            integrityStatus = IntegrityStatus.SKIPPED;
            integrityIssueList = List.of();
            return;
        }

        verifySchemaNow();
    }

    /**
     * Runs schema verification against the live database and updates the reported
     * integrity state.
     *
     * <p>Blocking: this opens a pooled connection and issues metadata queries. It is
     * safe to call from an async task and is what {@code /sf storage gui} uses to
     * re-check a running server.
     *
     * @return The issues found, empty when the schema is intact
     */
    @Nonnull
    public List<String> verifySchemaNow() {
        if (!provider.supportsSql()) {
            verifiedTableCount = 0;
            integrityStatus = IntegrityStatus.NOT_APPLICABLE;
            integrityIssueList = List.of();
            return integrityIssueList;
        }

        try (Connection connection = provider.borrowConnection().orElse(null)) {
            if (connection == null) {
                integrityStatus = IntegrityStatus.FAILED;
                integrityIssueList = List.of("No connection was available for verification");
                plugin.getLogger().log(Level.WARNING,
                    "[Storage] Schema verification skipped: the provider returned no connection.");
                return integrityIssueList;
            }

            List<String> issues = SchemaManager.verify(connection, provider.getProviderType());
            verifiedTableCount = SchemaManager.getTables().size();
            integrityIssueList = List.copyOf(issues);

            if (issues.isEmpty()) {
                integrityStatus = IntegrityStatus.PASSED;
                plugin.getLogger().log(Level.INFO,
                    "[Storage] Schema verification: {0} tables confirmed.", verifiedTableCount);
            } else {
                integrityStatus = IntegrityStatus.ISSUES;
                plugin.getLogger().log(Level.WARNING,
                    "[Storage] Schema verification found {0} issue(s):", issues.size());

                for (String issue : issues) {
                    plugin.getLogger().log(Level.WARNING, "[Storage]   - {0}", issue);
                }

                attemptSchemaRepair();
            }

            return integrityIssueList;
        } catch (Exception x) {
            integrityStatus = IntegrityStatus.FAILED;
            integrityIssueList = List.of(describe(x));
            plugin.getLogger().log(Level.WARNING, "[Storage] Schema verification could not run.", x);
            return integrityIssueList;
        }
    }

    private void attemptSchemaRepair() {
        if (!dbConfig.getBoolean("auto-migrate")) {
            plugin.getLogger().log(Level.WARNING,
                "[Storage] Set 'auto-migrate: true' in database.yml or run '/sf storage repair' to fix this.");
            return;
        }

        try (Connection connection = provider.borrowConnection().orElse(null)) {
            if (connection == null) {
                return;
            }

            int repaired = SchemaRepair.repair(connection, provider.getProviderType(), integrityIssueList);

            if (repaired <= 0) {
                return;
            }

            plugin.getLogger().log(Level.INFO,
                "[Storage] Schema repair applied {0} change(s).", repaired);

            List<String> remaining = SchemaManager.verify(connection, provider.getProviderType());
            integrityIssueList = List.copyOf(remaining);
            integrityStatus = remaining.isEmpty() ? IntegrityStatus.PASSED : IntegrityStatus.ISSUES;
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "[Storage] Schema repair failed.", x);
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

    private void scheduleBackups() {
        boolean autoBackup = dbConfig.getBoolean("backup.automatic");
        boolean backupEnabled = dbConfig.getBoolean("backup.enabled");
        if (!backupEnabled || !autoBackup) {
            return;
        }

        long intervalHours = safeGetLong("backup.interval-hours", 6L);
        if (intervalHours <= 0) {
            intervalHours = 6;
        }

        final long intervalTicks = intervalHours * 3600L * 20L;

        backupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, () -> performBackup(true), intervalTicks, intervalTicks);
    }

    private void scheduleCheckpoints() {
        CheckpointService service = checkpointService;

        if (service == null || !service.isEnabled() || !service.isAutomatic()) {
            return;
        }

        DailyBackupSchedule schedule = DailyBackupSchedule.parse(service.getScheduleTimes());

        if (!schedule.isEmpty()) {
            checkpointTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, () -> {
                    if (schedule.shouldFire(Instant.now())) {
                        performScheduledCheckpoint();
                    }
                }, MINUTE_CHECK_TICKS, MINUTE_CHECK_TICKS);
            return;
        }

        long intervalTicks = service.getIntervalHours() * 3600L * 20L;

        checkpointTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::performScheduledCheckpoint, intervalTicks, intervalTicks);
    }

    private void scheduleWorldBackups() {
        WorldBackupService service = worldBackupService;

        if (service == null || !service.isEnabled() || !service.isAutomatic()) {
            return;
        }

        DailyBackupSchedule schedule = DailyBackupSchedule.parse(service.getScheduleTimes());

        if (!schedule.isEmpty()) {
            worldBackupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, () -> {
                    if (schedule.shouldFire(Instant.now())) {
                        performScheduledWorldBackup();
                    }
                }, MINUTE_CHECK_TICKS, MINUTE_CHECK_TICKS);
            return;
        }

        long intervalTicks = service.getIntervalHours() * 3600L * 20L;

        worldBackupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin, this::performScheduledWorldBackup, intervalTicks, intervalTicks);
    }

    private void performScheduledWorldBackup() {
        WorldBackupService service = worldBackupService;

        if (service == null) {
            return;
        }

        WorldBackupService.WorldBackupResult result = service.create(true);

        if (!result.success()) {
            plugin.getLogger().log(Level.WARNING, "[WorldBackup] Automatic world backup failed: " + result.message());
        }
    }

    private void performScheduledCheckpoint() {
        CheckpointService service = checkpointService;

        if (service == null) {
            return;
        }

        if (service.isOnlyWhenChanged() && !service.status(provider).checkpointRequired()) {
            plugin.getLogger().log(Level.INFO,
                "[Checkpoint] Nothing changed since the newest checkpoint, skipping this run.");
            return;
        }

        CheckpointResult result = service.create(provider, true);

        if (!result.success()) {
            plugin.getLogger().log(Level.WARNING, "[Checkpoint] Automatic checkpoint failed: " + result.message());
        }
    }

    /**
     * Creates a checkpoint with the active provider.
     *
     * <p>Blocking: this reads the data folder and the database. Call it from an async task.
     *
     * @param automatic Whether the scheduler made this attempt rather than an admin
     *
     * @return The outcome of the attempt, never {@code null}
     */
    @Nonnull
    public CheckpointResult performCheckpoint(boolean automatic) {
        CheckpointService service = checkpointService;

        if (service == null) {
            return new CheckpointResult(false, "The checkpoint service is not available",
                null, List.of(), Instant.now(), automatic, 0L, List.of());
        }

        return service.create(provider, automatic);
    }

    /**
     * Creates a backup with the active provider and records the outcome so that it can
     * be reported by {@code /sf storage gui} and the startup banner.
     *
     * <p>Blocking: this reads the database or the data folder. Call it from an async task.
     *
     * @param automatic Whether this attempt was made by the scheduler rather than an admin
     * @return The outcome of the attempt, never {@code null}
     */
    @Nonnull
    public BackupOutcome performBackup(boolean automatic) {
        BackupOutcome outcome;

        try {
            BackupEntry entry = DatabaseBackupService.createBackup(provider, dbConfig, automatic);
            int entries = entry.metadata().totalRows();
            outcome = new BackupOutcome(true, entry.metadata().backupId(), entries, Instant.now(), automatic);

            if (automatic) {
                plugin.getLogger().log(Level.INFO,
                    "[Storage] Automatic backup completed: {0} entries captured.", entries);
            }
        } catch (Exception x) {
            outcome = new BackupOutcome(false, describe(x), 0, Instant.now(), automatic);
            plugin.getLogger().log(Level.WARNING,
                (automatic ? "[Storage] Automatic backup failed." : "[Storage] Backup failed."), x);
        }

        lastBackupResult = outcome;
        return outcome;
    }

    private void printBanner() {
        Logger logger = plugin.getLogger();
        StoragePoolStats pool = provider.getPoolStats();
        long ping             = provider.pingMillis();
        boolean cacheEnabled  = dbConfig.getBoolean("cache.enabled");
        int cacheMaxSize      = dbConfig.getInt("cache.max-size");
        long cacheTtlMin      = safeGetLong("cache.expire-after-minutes", 30L);
        boolean backupEnabled = dbConfig.getBoolean("backup.enabled");
        boolean autoBackup    = dbConfig.getBoolean("backup.automatic");
        long backupInterval   = safeGetLong("backup.interval-hours", 6L);
        boolean autoMigrate   = dbConfig.getBoolean("auto-migrate");
        boolean sql           = provider.supportsSql();

        String pingStr     = ping < 0 ? "N/A" : ping + " ms";
        String poolStr     = sql ? pool.active() + " / " + pool.max() : "n/a (file storage)";
        String cacheStr    = cacheEnabled
            ? "Enabled (" + cacheMaxSize + " max, " + cacheTtlMin + " min TTL)"
            : "Disabled";
        String tablesStr   = sql ? verifiedTableCount + " verified" : "n/a (file storage)";
        String integrityStr = getIntegrityDescription();
        String backupStr   = describeBackupLine(backupEnabled, autoBackup, backupInterval);
        String migrateStr  = !sql ? "n/a (file storage)" : autoMigrate ? "Enabled" : "Disabled";

        logger.info(SEPARATOR);
        logger.info("  Slimefun4Core Storage Engine");
        logger.info(SEPARATOR);
        logger.info(String.format("  %-12s: %s", "Provider",  provider.getProviderName()));
        logger.info(String.format("  %-12s: %s", "Status",    getStatus()));
        logger.info(String.format("  %-12s: %s", "Ping",      pingStr));
        logger.info(String.format("  %-12s: %s", "Pool",      poolStr));
        logger.info(String.format("  %-12s: %s", "Cache",     cacheStr));
        logger.info(String.format("  %-12s: %s", "Tables",    tablesStr));
        logger.info(String.format("  %-12s: %s", "Integrity", integrityStr));
        logger.info(String.format("  %-12s: %s", "Migrate",   migrateStr));
        logger.info(String.format("  %-12s: %s", "Backup",    backupStr));
        logger.info(String.format("  %-12s: %s", "Checkpoint", describeCheckpointLine()));
        logger.info(String.format("  %-12s: %s", "Worlds",     describeWorldBackupLine()));
        logger.info(SEPARATOR);
        logger.info("  Storage Engine initialized. Manage it in-game with /sf storage gui");
        logger.info(SEPARATOR);
    }

    @Nonnull
    private String describeCheckpointLine() {
        CheckpointService service = checkpointService;

        if (service == null || !service.isEnabled()) {
            return "Disabled";
        }

        int existing = service.list().size();
        String schedule = service.isAutomatic()
            ? "auto, " + describeSchedule(service.getScheduleTimes(), service.getIntervalHours())
            : "manual only";

        return "Ready (" + existing + "/" + service.getMaxCheckpoints() + " kept, " + schedule + ")";
    }

    @Nonnull
    private String describeWorldBackupLine() {
        WorldBackupService service = worldBackupService;

        if (service == null || !service.isEnabled()) {
            return "Disabled";
        }

        int existing = service.list().size();
        String schedule = service.isAutomatic()
            ? "auto, " + describeSchedule(service.getScheduleTimes(), service.getIntervalHours())
            : "manual only";

        return "Ready (" + existing + "/" + service.getKeep() + " kept, " + schedule + ")";
    }

    @Nonnull
    private String describeSchedule(@Nonnull List<String> configuredTimes, int intervalHours) {
        DailyBackupSchedule schedule = DailyBackupSchedule.parse(configuredTimes);

        if (schedule.isEmpty()) {
            return "every " + intervalHours + "h";
        }

        return "at " + schedule.times().stream()
            .map(time -> String.format("%02d:%02d", time.getHour(), time.getMinute()))
            .collect(java.util.stream.Collectors.joining(", ")) + " UTC";
    }

    @Nonnull
    private String describeBackupLine(boolean backupEnabled, boolean autoBackup, long backupInterval) {
        if (!backupEnabled) {
            return "Disabled";
        }

        String format = provider.supportsSql() ? "SQL row dump" : "file archive";
        String schedule = autoBackup
            ? "auto, every " + backupInterval + "h"
            : "manual only";

        return "Ready (" + schedule + ", " + format + ")";
    }
}