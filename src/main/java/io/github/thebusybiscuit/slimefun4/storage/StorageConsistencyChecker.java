package io.github.thebusybiscuit.slimefun4.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backup.BlockStorageBackup;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;

import org.apache.commons.lang.Validate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nonnull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Performs asynchronous read-only consistency checks against player and block storage.
 *
 * <p>Constructing this class does nothing but wire it up. {@link #startScheduler()} has to be
 * called for the periodic check to begin, so a caller that only wants
 * {@link #scanBlockFiles(File)} or a single {@link #runCheck()} does not silently acquire a
 * repeating task it then has to remember to cancel.
 *
 * <p>The player half compares the cache against the backend for players who are online, so it
 * cannot say anything about a migration, which by rule runs on an empty server. The block half
 * needs no players and is exposed as the static {@link #scanBlockFiles(File)} for exactly that
 * reason: it is the integrity check to run after a block archive has been restored.
 */
@Beta
public class StorageConsistencyChecker {

    private final Slimefun plugin;
    private final Storage storage;
    private final Storage backendForChecks;
    private final AtomicReference<BukkitTask> scheduledTask = new AtomicReference<>();

    /**
     * Creates a new {@link StorageConsistencyChecker} without scheduling anything.
     *
     * @param plugin
     *            The current {@link Slimefun} instance
     * @param storage
     *            The storage backend to inspect
     */
    public StorageConsistencyChecker(@Nonnull Slimefun plugin, @Nonnull Storage storage) {
        Validate.notNull(plugin, "Plugin must not be null");
        Validate.notNull(storage, "Storage must not be null");
        this.plugin = plugin;
        this.storage = storage;
        this.backendForChecks = storage instanceof StorageCache cache ? cache.getBackend() : storage;
    }

    /**
     * Starts a new asynchronous consistency check.
     *
     * @return A future with the resulting {@link ConsistencyReport}
     */
    public @Nonnull CompletableFuture<ConsistencyReport> runCheck() {
        return Slimefun.getThreadService().supplyFuture(plugin, "StorageConsistencyChecker#runCheck", this::runCheckSynchronously);
    }

    /**
     * Stops the scheduled consistency task.
     */
    public void shutdown() {
        BukkitTask task = scheduledTask.getAndSet(null);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Reads every stored block file under a data root and reports the ones that no longer parse.
     *
     * <p>Nothing is written and nothing is repaired. This is the check to run after a block
     * archive has been restored, and after a migration, because a truncated or half-written
     * YAML file is exactly what a failed copy leaves behind.
     *
     * @param root The data root block files live under, usually {@code data-storage/Slimefun}
     * @return What was scanned and what could not be parsed
     */
    @Nonnull
    public static BlockScanResult scanBlockFiles(@Nonnull File root) {
        Validate.notNull(root, "Root must not be null");

        BlockScanCollector collector = new BlockScanCollector();

        for (String section : BlockStorageBackup.getSections()) {
            collector.walk(new File(root, section));
        }

        return new BlockScanResult(collector.scanned, List.copyOf(collector.unreadable));
    }

    /**
     * Starts the periodic consistency check.
     *
     * <p>Calling this more than once replaces nothing: the previous task keeps running, so
     * {@link #shutdown()} is the only way to stop one. Call it once, from startup.
     */
    public void startScheduler() {
        long intervalMinutes = Math.max(1L, Slimefun.getCfg().getLong("storage.consistency-check-interval-minutes"));
        long periodTicks = intervalMinutes * 60L * 20L;

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                runCheckSynchronously();
            } catch (Exception x) {
                plugin.getLogger().log(Level.WARNING, "Consistency check failed", x);
            }
        }, periodTicks, periodTicks);

        scheduledTask.set(task);
    }

    private @Nonnull ConsistencyReport runCheckSynchronously() {
        int playerIssues = 0;
        int blockIssues = 0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerProfile profile = PlayerProfile.find(player).orElse(null);
            if (profile == null) {
                continue;
            }

            PlayerData cachedData = profile.getPlayerData();
            PlayerData backendData = backendForChecks.loadPlayerData(player.getUniqueId());

            playerIssues += compareSection("researches", player.getUniqueId().toString(), cachedData.getResearches().size(), backendData.getResearches().size());
            playerIssues += compareSection("backpacks", player.getUniqueId().toString(), cachedData.getBackpacks().size(), backendData.getBackpacks().size());
            playerIssues += compareSection("waypoints", player.getUniqueId().toString(), cachedData.getWaypoints().size(), backendData.getWaypoints().size());
        }

        boolean blockScanEnabled = Slimefun.getCfg().getBoolean("storage.consistency-check-scan-blocks");
        if (blockScanEnabled) {
            BlockScanResult result = scanBlockFiles(BlockStorageBackup.defaultRoot());
            blockIssues = result.unreadable().size();

            for (String path : result.unreadable()) {
                plugin.getLogger().log(Level.WARNING, "Stored block file could not be parsed: {0}", path);
            }
        }

        return new ConsistencyReport(playerIssues, blockIssues);
    }

    private int compareSection(@Nonnull String section, @Nonnull String playerId, int cachedCount, int backendCount) {
        if (cachedCount != backendCount) {
            plugin.getLogger().log(Level.WARNING, "Storage consistency mismatch for player {0} in {1}: cached={2}, backend={3}",
                new Object[] { playerId, section, cachedCount, backendCount });
            return 1;
        }

        return 0;
    }

    private static final class BlockScanCollector {

        private final List<String> unreadable = new ArrayList<>();
        private int scanned;

        private void walk(@Nonnull File file) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();

                if (children != null) {
                    for (File child : children) {
                        walk(child);
                    }
                }

                return;
            }

            if (!file.isFile()) {
                return;
            }

            scanned++;

            try {
                new Config(file).getKeys();
            } catch (Exception x) {
                unreadable.add(file.getPath());
            }
        }
    }

    /**
     * The result of a block file scan.
     *
     * @param scanned
     *            How many files were read
     * @param unreadable
     *            The path of every file that could not be parsed, in scan order
     */
    @Beta
    public record BlockScanResult(int scanned, @Nonnull List<String> unreadable) {

        /**
         * @return {@code true} when every scanned file parsed
         */
        public boolean isHealthy() {
            return unreadable.isEmpty();
        }

        /**
         * @return A single line describing the scan
         */
        @Nonnull
        public String describe() {
            if (scanned == 0) {
                return "No block files were found";
            }

            return unreadable.isEmpty()
                ? scanned + " block file(s) scanned, all readable"
                : unreadable.size() + " of " + scanned + " block file(s) could not be parsed";
        }
    }

    /**
     * The result of a consistency check run.
     *
     * @param playerIssues
     *            The number of player-data mismatches found
     * @param blockIssues
     *            The number of block-storage issues found
     */
    @Beta
    public record ConsistencyReport(int playerIssues, int blockIssues) {
    }
}
