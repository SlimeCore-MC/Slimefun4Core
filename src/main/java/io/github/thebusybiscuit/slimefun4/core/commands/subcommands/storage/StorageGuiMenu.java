package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.Checkpoint;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;

/**
 * The home screen of {@code /sf storage gui}.
 *
 * <p>Shows live storage health and links to the backup browser, the provider setup
 * wizard and the in-game guide. Every value on this screen is read once per open on an
 * async task, because pinging the database and hashing backup files must not run on the
 * server thread.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class StorageGuiMenu {

    private StorageGuiMenu() {}

    /**
     * Collects the health snapshot off the main thread and opens the home screen.
     *
     * @param plugin The plugin instance used for scheduling
     * @param player The admin to open the menu for
     */
    public static void open(@Nonnull Plugin plugin, @Nonnull Player player) {
        StorageMenuLayout.runAsync(plugin, () -> {
            Snapshot snapshot = Snapshot.capture();
            StorageMenuLayout.sync(() -> {
                if (snapshot == null) {
                    player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
                    return;
                }

                build(plugin, player, snapshot).open(player);
            });
        });
    }

    @Nonnull
    private static ChestMenu build(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull Snapshot snapshot) {

        ChestMenu menu = StorageMenuLayout.newMenu("&2Slimefun Storage Engine");

        StorageMenuLayout.fillBackground(menu);

        addStatusTiles(menu, snapshot);
        addActionTiles(plugin, menu, snapshot);

        return menu;
    }

    private static void addStatusTiles(@Nonnull ChestMenu menu, @Nonnull Snapshot snapshot) {
        StorageMenuLayout.addInfoItem(menu, 4, Material.ENDER_CHEST,
            "&aStorage Engine",
            StorageMenuLayout.lore(
                "&7Provider:    &f" + snapshot.providerName(),
                "&7Backend:     &f" + snapshot.backendName(),
                "&7database.yml: &f" + snapshot.configuredProvider(),
                "",
                "&7Everything on this screen was read",
                "&7at the moment you opened it."));

        StorageMenuLayout.addInfoItem(menu, 10, snapshot.connected() ? Material.LIME_DYE : Material.RED_DYE,
            (snapshot.connected() ? "&aStatus: " : "&cStatus: ") + snapshot.status(),
            StorageMenuLayout.lore(
                "&7Reported: &f" + snapshot.status(),
                "&7Type:     &f" + snapshot.providerType()));

        StorageMenuLayout.addInfoItem(menu, 11, Material.CLOCK,
            "&bPing: &f" + (snapshot.ping() < 0 ? "n/a" : snapshot.ping() + " ms"),
            StorageMenuLayout.lore(
                snapshot.sql()
                    ? "&7Round-trip time of a test query."
                    : "&7File storage does not use a network."));

        StorageMenuLayout.addInfoItem(menu, 12, Material.HOPPER,
            "&bConnection pool",
            StorageMenuLayout.lore(snapshot.sql()
                ? "&7Active: &f" + snapshot.poolActive() + " &7/ &f" + snapshot.poolMax()
                : "&7Not applicable to file storage."));

        StorageMenuLayout.addInfoItem(menu, 13, Material.BOOKSHELF,
            "&bCache",
            snapshot.cacheSummary());

        StorageMenuLayout.addInfoItem(menu, 14,
            snapshot.integrityHealthy() ? Material.LIME_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE,
            "&bSchema integrity",
            StorageMenuLayout.lore(
                "&7State:  &f" + snapshot.integrity(),
                "&7Tables: &f" + (snapshot.sql() ? snapshot.tables() + " verified" : "n/a (file storage)")));

        StorageMenuLayout.addInfoItem(menu, 15,
            snapshot.lastBackupFailed() ? Material.RED_STAINED_GLASS_PANE : Material.CHEST,
            "&bLast backup",
            snapshot.backupSummary());

        StorageMenuLayout.addInfoItem(menu, 16, Material.PAPER,
            "&bStored backups: &f" + snapshot.backupCount(),
            StorageMenuLayout.lore(
                "&7Total size: &f" + StorageMenuLayout.formatSize(snapshot.backupBytes()),
                "&7Folder: &f" + snapshot.backupDirectory(),
                snapshot.emptyBackups() > 0
                    ? "&c" + snapshot.emptyBackups() + " backup(s) captured no data"
                    : "&7All stored backups contain data."));
    }

    private static void addActionTiles(
            @Nonnull Plugin plugin,
            @Nonnull ChestMenu menu,
            @Nonnull Snapshot snapshot) {

        StorageMenuLayout.addButton(menu, 27,
            CustomItemStack.create(Material.MINECART, "&b> Migrate storage",
                StorageMenuLayout.lore(
                    "&7Now on: &f" + snapshot.providerName(),
                    "&7Copy every player record into",
                    "&7another provider. Nothing is",
                    "&7removed from where it is now.",
                    "",
                    "&7Needs an empty server.",
                    "",
                    "&eClick &7to open")),
            (pl, right, shift) -> MigrationMenu.open(plugin, pl));

        StorageMenuLayout.addManagedButton(menu, 28,
            CustomItemStack.create(Material.CHEST_MINECART, "&a> Create a backup now",
                StorageMenuLayout.lore(
                    "&7Format: &f" + snapshot.backupFormat(),
                    "&7Reads the data off the server thread.",
                    "&7Nothing is deleted if the copy fails.",
                    "",
                    "&eClick &7to start")),
            (pl, right, shift) -> createBackup(plugin, pl));

        StorageMenuLayout.addButton(menu, 29,
            CustomItemStack.create(Material.BOOK, "&b> Browse backups",
                StorageMenuLayout.lore(
                    "&7Stored: &f" + snapshot.backupCount(),
                    "&7Inspect, verify, restore or delete",
                    "&7any stored backup.",
                    "",
                    "&eClick &7to open")),
            (pl, right, shift) -> BackupBrowserMenu.open(plugin, pl, 1));

        StorageMenuLayout.addButton(menu, 30,
            CustomItemStack.create(Material.ANVIL, "&b> Database setup",
                StorageMenuLayout.lore(
                    "&7Pick a provider and edit the",
                    "&7connection settings.",
                    "&7Passwords stay in database.yml.",
                    "",
                    "&eClick &7to open")),
            (pl, right, shift) -> DatabaseSetupMenu.open(plugin, pl));

        StorageMenuLayout.addButton(menu, 31,
            CustomItemStack.create(Material.ENCHANTED_BOOK, "&b> Guide",
                StorageMenuLayout.lore(
                    "&7How every provider is configured,",
                    "&7what each key does and where the",
                    "&7config file lives.",
                    "",
                    "&eClick &7to open")),
            (pl, right, shift) -> StorageGuideMenu.open(plugin, pl, 0));

        StorageMenuLayout.addManagedButton(menu, 32,
            CustomItemStack.create(Material.SPYGLASS, "&b> Verify schema",
                StorageMenuLayout.lore(
                    "&7State: &f" + snapshot.integrity(),
                    snapshot.sql()
                        ? "&7Re-checks every managed table."
                        : "&7Not applicable to file storage.",
                    "",
                    snapshot.sql() ? "&eClick &7to run" : "&8Unavailable")),
            (pl, right, shift) -> verifySchema(plugin, pl, snapshot.sql()));

        StorageMenuLayout.addManagedButton(menu, 33,
            CustomItemStack.create(Material.BUCKET, "&b> Clear cache",
                StorageMenuLayout.lore(
                    "&7Drops every cached profile so the",
                    "&7next read comes from the database.",
                    "&7Unsaved changes are kept.",
                    "",
                    "&eClick &7to clear")),
            (pl, right, shift) -> clearCache(plugin, pl));

        StorageMenuLayout.addButton(menu, 34,
            CustomItemStack.create(Material.COMPASS, "&b> Refresh",
                StorageMenuLayout.lore(
                    "&7Re-reads every value on this screen.",
                    "",
                    "&eClick &7to refresh")),
            (pl, right, shift) -> open(plugin, pl));

        StorageMenuLayout.addButton(menu, 35,
            CustomItemStack.create(Material.SHULKER_BOX, "&b> Checkpoints",
                StorageMenuLayout.lore(
                    "&7Stored: &f" + snapshot.checkpointCount() + " &7/ &f" + snapshot.checkpointMax(),
                    "&7Newest: &f" + snapshot.lastCheckpoint(),
                    "",
                    "&7One checkpoint holds two halves:",
                    "&7the Slimefun data files and the",
                    "&7database player records.",
                    "",
                    snapshot.checkpointsEnabled() ? "&eClick &7to open" : "&8Creation is disabled")),
            (pl, right, shift) -> CheckpointBrowserMenu.open(plugin, pl, 1));

        StorageMenuLayout.addCloseButton(menu, StorageMenuLayout.SLOT_BACK);
    }

    private static void createBackup(@Nonnull Plugin plugin, @Nonnull Player player) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Creating a backup, this can take a moment.");

        StorageMenuLayout.runAsync(plugin, () -> {
            StorageEngineService.BackupOutcome outcome = svc.performBackup(false);

            StorageMenuLayout.sync(() -> {
                if (outcome.success()) {
                    player.sendMessage(ChatColor.GREEN + "Backup created: " + ChatColor.WHITE
                        + StorageMenuLayout.shorten(outcome.detail())
                        + ChatColor.GRAY + " (" + outcome.entries() + " entries)");
                } else {
                    player.sendMessage(ChatColor.RED + "Backup failed: " + ChatColor.WHITE + outcome.detail());
                    player.sendMessage(ChatColor.GRAY + "Nothing was written and no older backup was removed.");
                }

                open(plugin, player);
            });
        });
    }

    private static void verifySchema(@Nonnull Plugin plugin, @Nonnull Player player, boolean sql) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        if (!sql) {
            player.sendMessage(ChatColor.GRAY + "File storage has no database schema to verify.");
            return;
        }

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Verifying the database schema.");

        StorageMenuLayout.runAsync(plugin, () -> {
            List<String> issues = svc.verifySchemaNow();

            StorageMenuLayout.sync(() -> {
                if (issues.isEmpty()) {
                    player.sendMessage(ChatColor.GREEN + "Schema verified: " + ChatColor.WHITE
                        + svc.getVerifiedTableCount() + ChatColor.GREEN + " table(s), no issues.");
                } else {
                    player.sendMessage(ChatColor.RED + "Schema issues: " + ChatColor.WHITE + issues.size());

                    for (String issue : issues) {
                        player.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + issue);
                    }

                    player.sendMessage(ChatColor.GRAY + "Run " + ChatColor.WHITE + "/sf storage repair"
                        + ChatColor.GRAY + " to attempt a fix.");
                }

                open(plugin, player);
            });
        });
    }

    private static void clearCache(@Nonnull Plugin plugin, @Nonnull Player player) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        if (svc.getStorage() instanceof StorageCache cache) {
            int dropped = cache.invalidateAll();
            player.sendMessage(ChatColor.GREEN + "Cache cleared: " + ChatColor.WHITE + dropped
                + ChatColor.GREEN + " entries dropped.");
        } else {
            player.sendMessage(ChatColor.GRAY + "The active storage is not cache-wrapped.");
        }

        open(plugin, player);
    }

    @Nullable
    private static StorageEngineService service() {
        return Slimefun.instance() == null ? null : Slimefun.instance().getStorageEngineService();
    }

    private record Snapshot(
        @Nonnull String providerName,
        @Nonnull String backendName,
        @Nonnull String configuredProvider,
        @Nonnull String providerType,
        @Nonnull String status,
        boolean connected,
        boolean sql,
        long ping,
        int poolActive,
        int poolMax,
        @Nonnull List<String> cacheSummary,
        @Nonnull String integrity,
        boolean integrityHealthy,
        int tables,
        @Nonnull List<String> backupSummary,
        boolean lastBackupFailed,
        @Nonnull String backupFormat,
        int backupCount,
        long backupBytes,
        int emptyBackups,
        @Nonnull String backupDirectory,
        int checkpointCount,
        int checkpointMax,
        boolean checkpointsEnabled,
        @Nonnull String lastCheckpoint
    ) {

        @Nullable
        static Snapshot capture() {
            StorageEngineService svc = service();

            if (svc == null) {
                return null;
            }

            StorageProvider provider = svc.getProvider();
            boolean sql = provider.supportsSql();
            StoragePoolStats pool = svc.getPoolStats();
            String configured = svc.getDbConfig().getString("provider");
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());

            long bytes = 0L;
            int empty = 0;

            for (BackupEntry entry : backups) {
                long size = entry.sizeBytes();

                if (size > 0L) {
                    bytes += size;
                }

                if (entry.metadata().isEmpty()) {
                    empty++;
                }
            }

            StorageEngineService.IntegrityStatus integrity = svc.getIntegrityStatus();
            StorageEngineService.BackupOutcome last = svc.getLastBackupResult();

            CheckpointService checkpoints = svc.getCheckpointService();
            List<Checkpoint> stored = checkpoints == null ? List.of() : checkpoints.list();
            Checkpoint newest = stored.isEmpty() ? null : stored.get(0);

            return new Snapshot(
                provider.getProviderName(),
                provider.getBackendName(),
                configured == null || configured.isBlank() ? "not set" : configured,
                provider.getProviderType().name(),
                svc.getStatus(),
                !sql || provider.isAvailable(),
                sql,
                sql ? svc.getPing() : -1L,
                pool.active(),
                pool.max(),
                describeCache(svc.getCacheStats()),
                svc.getIntegrityDescription(),
                integrity != StorageEngineService.IntegrityStatus.ISSUES
                    && integrity != StorageEngineService.IntegrityStatus.FAILED,
                svc.getVerifiedTableCount(),
                describeBackup(last),
                last != null && !last.success(),
                (sql ? BackupFormat.SQL_JSON : BackupFormat.LEGACY_FILES).getDisplayName(),
                backups.size(),
                bytes,
                empty,
                DatabaseBackupService.resolveDirectory(svc.getDbConfig()).getPath(),
                stored.size(),
                checkpoints == null ? CheckpointService.DEFAULT_MAX_CHECKPOINTS : checkpoints.getMaxCheckpoints(),
                checkpoints != null && checkpoints.isEnabled(),
                newest == null
                    ? "none"
                    : newest.id() + " (" + StorageMenuLayout.formatTimestamp(newest.metadata().createdAt()) + ")");
        }

        @Nonnull
        private static List<String> describeCache(@Nullable StorageCache.CacheStats stats) {
            if (stats == null) {
                return StorageMenuLayout.lore("&7The active storage is not cache-wrapped.");
            }

            return StorageMenuLayout.lore(
                "&7Entries:   &f" + stats.size() + " &7/ &f" + stats.maxSize(),
                "&7Hit rate:  &f" + String.format("%.1f%%", stats.hitRate() * 100.0),
                "&7Hits:      &f" + stats.hits() + "  &7Misses: &f" + stats.misses(),
                "&7Evictions: &f" + stats.evictions());
        }

        @Nonnull
        private static List<String> describeBackup(@Nullable StorageEngineService.BackupOutcome outcome) {
            if (outcome == null) {
                return StorageMenuLayout.lore(
                    "&7No backup has run since this server",
                    "&7was started.",
                    "",
                    "&7The automatic task and the button",
                    "&7below both record their result here.");
            }

            if (!outcome.success()) {
                return StorageMenuLayout.lore(
                    "&cFailed &7at &f" + StorageMenuLayout.formatTimestamp(outcome.timestamp()),
                    "&7Reason: &f" + outcome.detail(),
                    "&7Trigger: &f" + (outcome.automatic() ? "automatic" : "manual"),
                    "",
                    "&7No file was written and no older",
                    "&7backup was removed.");
            }

            return StorageMenuLayout.lore(
                "&aSucceeded &7at &f" + StorageMenuLayout.formatTimestamp(outcome.timestamp()),
                "&7Id:      &f" + StorageMenuLayout.shorten(outcome.detail()),
                "&7Entries: &f" + outcome.entries(),
                "&7Trigger: &f" + (outcome.automatic() ? "automatic" : "manual"));
        }
    }
}
