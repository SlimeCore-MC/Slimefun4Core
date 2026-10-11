package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupMetadata;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseRestoreService;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The paged backup browser of {@code /sf storage gui}.
 *
 * <p>Lists every stored backup newest first, 45 per page, and exposes inspect, verify,
 * restore and delete. Listing, hashing, restoring and deleting all happen on an async
 * task; only the menu itself is built on the server thread.
 *
 * <p>Checksums are hashed once per file revision and remembered in {@link #HEALTH}, keyed
 * by path, size and modification time, so paging back and forth does not re-hash
 * megabytes of archives.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class BackupBrowserMenu {

    private static final Map<String, Boolean> HEALTH = new ConcurrentHashMap<>();

    private BackupBrowserMenu() {}

    static void open(@Nonnull Plugin plugin, @Nonnull Player player, int page) {
        StorageMenuLayout.runAsync(plugin, () -> {
            StorageEngineService svc = service();

            if (svc == null) {
                StorageMenuLayout.sync(() ->
                    player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet."));
                return;
            }

            List<BackupEntry> backups = new ArrayList<>(DatabaseBackupService.listBackups(svc.getDbConfig()));
            backups.sort(Comparator.comparing((BackupEntry e) -> e.metadata().createdAt()).reversed());

            for (BackupEntry entry : backups) {
                healthOf(entry);
            }

            File directory = DatabaseBackupService.resolveDirectory(svc.getDbConfig());

            StorageMenuLayout.sync(() ->
                build(plugin, player, backups, directory, page).open(player));
        });
    }

    @Nonnull
    private static ChestMenu build(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull List<BackupEntry> backups,
            @Nonnull File directory,
            int page) {

        int pages = Math.max(1, (backups.size() + StorageMenuLayout.PAGE_SIZE - 1) / StorageMenuLayout.PAGE_SIZE);
        int current = Math.max(1, Math.min(page, pages));

        ChestMenu menu = StorageMenuLayout.newMenu("&2Backups &7(" + current + "/" + pages + ")");
        StorageMenuLayout.paintNavigationRow(menu);

        int first = (current - 1) * StorageMenuLayout.PAGE_SIZE;

        for (int index = 0; index < StorageMenuLayout.PAGE_SIZE; index++) {
            int position = first + index;

            if (position >= backups.size()) {
                break;
            }

            BackupEntry entry = backups.get(position);
            StorageMenuLayout.addButton(menu, index, describe(entry),
                (pl, right, shift) -> onClick(plugin, pl, entry, current, right, shift));
        }

        if (backups.isEmpty()) {
            StorageMenuLayout.addInfoItem(menu, 22, Material.BARRIER, "&7No backups stored",
                StorageMenuLayout.lore(
                    "&7Nothing has been backed up yet.",
                    "&7Folder: &f" + directory.getPath(),
                    "",
                    "&7Use the button below to create one."));
        }

        addNavigation(plugin, player, menu, current, pages);

        return menu;
    }

    private static void addNavigation(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            int current,
            int pages) {

        menu.addItem(StorageMenuLayout.SLOT_PREVIOUS, ChestMenuUtils.getPreviousButton(player, current, pages));
        menu.addMenuClickHandler(StorageMenuLayout.SLOT_PREVIOUS, (pl, slot, item, action) -> {
            if (current > 1) {
                open(plugin, pl, current - 1);
            }
            return false;
        });

        menu.addItem(StorageMenuLayout.SLOT_NEXT, ChestMenuUtils.getNextButton(player, current, pages));
        menu.addMenuClickHandler(StorageMenuLayout.SLOT_NEXT, (pl, slot, item, action) -> {
            if (current < pages) {
                open(plugin, pl, current + 1);
            }
            return false;
        });

        StorageMenuLayout.addManagedButton(menu, StorageMenuLayout.SLOT_CENTER,
            CustomItemStack.create(Material.CHEST_MINECART, "&a> Create a backup now",
                StorageMenuLayout.lore(
                    "&7Reads the data off the server thread.",
                    "&7Nothing is deleted if the copy fails.",
                    "",
                    "&eClick &7to start")),
            (pl, right, shift) -> createBackup(plugin, pl, current));

        StorageMenuLayout.addButton(menu, 47,
            CustomItemStack.create(Material.COMPASS, "&b> Refresh",
                StorageMenuLayout.lore("&7Re-reads the folder and re-checks", "&7every checksum.")),
            (pl, right, shift) -> {
                HEALTH.clear();
                open(plugin, pl, current);
            });

        StorageMenuLayout.addBackButton(menu, player, () -> StorageGuiMenu.open(plugin, player));
    }

    @Nonnull
    private static ItemStack describe(@Nonnull BackupEntry entry) {
        BackupMetadata meta = entry.metadata();
        Health health = healthOf(entry);
        long size = entry.sizeBytes();

        return CustomItemStack.create(health.icon(meta.format()),
            "&f" + StorageMenuLayout.shorten(meta.backupId())
                + " &7" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
            StorageMenuLayout.lore(
                "&7Provider: &f" + meta.provider(),
                "&7Format:   &f" + meta.format().getDisplayName(),
                "&7Entries:  &f" + meta.totalRows(),
                "&7Size:     &f" + (size < 0L ? "file missing" : StorageMenuLayout.formatSize(size)),
                "&7Version:  &f" + meta.slimefunVersion(),
                "&7Trigger:  &f" + (meta.automatic() ? "automatic" : "manual"),
                "&7Health:   " + health.getLabel(),
                "",
                "&eLeft-click &7to inspect",
                "&eRight-click &7to verify the checksum",
                "&eShift + left-click &7to restore",
                "&eShift + right-click &7to delete"));
    }

    @Nonnull
    private static Health healthOf(@Nonnull BackupEntry entry) {
        if (!entry.exists()) {
            return Health.MISSING;
        }

        if (entry.metadata().isEmpty()) {
            return Health.EMPTY;
        }

        File file = entry.dataFile();
        String key = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();

        return HEALTH.computeIfAbsent(key, k -> DatabaseBackupService.verifyBackup(entry))
            ? Health.OK
            : Health.MISMATCH;
    }

    private static void onClick(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page,
            boolean right,
            boolean shift) {

        if (shift && right) {
            if (StorageMenuLayout.requireManage(player)) {
                confirmDelete(plugin, player, entry, page);
            }
        } else if (shift) {
            if (StorageMenuLayout.requireManage(player)) {
                confirmRestore(plugin, player, entry, page);
            }
        } else if (right) {
            verify(plugin, player, entry, page);
        } else {
            inspect(plugin, player, entry, page);
        }
    }

    private static void inspect(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        BackupMetadata meta = entry.metadata();
        Health health = healthOf(entry);
        long size = entry.sizeBytes();

        ChestMenu menu = StorageMenuLayout.newMenu("&2Backup &7" + StorageMenuLayout.shorten(meta.backupId()));
        StorageMenuLayout.fillBackground(menu);

        StorageMenuLayout.addInfoItem(menu, 4, health.icon(meta.format()), "&aBackup details",
            StorageMenuLayout.lore(
                "&7Id:       &f" + meta.backupId(),
                "&7Created:  &f" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
                "&7Provider: &f" + meta.provider(),
                "&7Format:   &f" + meta.format().getDisplayName(),
                "&7Version:  &f" + meta.slimefunVersion(),
                "&7Trigger:  &f" + (meta.automatic() ? "automatic" : "manual"),
                "&7Size:     &f" + (size < 0L ? "file missing" : StorageMenuLayout.formatSize(size)),
                "&7Checksum: &f" + StorageMenuLayout.shorten(meta.checksum()),
                "&7Health:   " + health.getLabel(),
                "&7Entries:  &f" + meta.totalRows(),
                "&7File: &f" + entry.dataFile().getName()));

        addSectionTiles(menu, meta);
        addDetailNavigation(plugin, player, menu, entry, page);

        menu.open(player);
    }

    private static void addSectionTiles(@Nonnull ChestMenu menu, @Nonnull BackupMetadata meta) {
        List<String> sections = new ArrayList<>(meta.rowCounts().keySet());
        sections.sort(Comparator.naturalOrder());

        int slot = 18;

        for (String section : sections) {
            if (slot > 44) {
                break;
            }

            int count = meta.rowCounts().getOrDefault(section, 0);

            StorageMenuLayout.addInfoItem(menu, slot,
                count > 0 ? Material.PAPER : Material.RED_STAINED_GLASS_PANE,
                "&b" + section,
                StorageMenuLayout.lore(
                    "&7Entries: &f" + count,
                    count > 0 ? "&7Captured." : "&cThis section captured nothing."));

            slot++;
        }

        if (sections.isEmpty()) {
            StorageMenuLayout.addInfoItem(menu, 31, Material.BARRIER, "&cNo sections recorded",
                StorageMenuLayout.lore(
                    "&7This backup captured no data at all",
                    "&7and cannot be restored.",
                    "",
                    "&7Backups written before this build",
                    "&7could be empty without saying so."));
        }
    }

    private static void addDetailNavigation(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull BackupEntry entry,
            int page) {

        StorageMenuLayout.addButton(menu, 47,
            CustomItemStack.create(Material.SPYGLASS, "&b> Verify checksum",
                StorageMenuLayout.lore(
                    "&7Re-hashes the file and compares it",
                    "&7with the checksum in the metadata.",
                    "",
                    "&eClick &7to verify")),
            (pl, right, shift) -> verify(plugin, pl, entry, page));

        StorageMenuLayout.addManagedButton(menu, 49,
            CustomItemStack.create(Material.LIME_WOOL, "&a> Restore this backup",
                StorageMenuLayout.lore(
                    "&7Overwrites the live data with this",
                    "&7copy. A snapshot is taken first.",
                    "",
                    "&cRun this with no players online.",
                    "",
                    "&eClick &7to continue")),
            (pl, right, shift) -> confirmRestore(plugin, pl, entry, page));

        StorageMenuLayout.addManagedButton(menu, 51,
            CustomItemStack.create(Material.LAVA_BUCKET, "&c> Delete this backup",
                StorageMenuLayout.lore(
                    "&7Removes the data file and its",
                    "&7metadata from disk.",
                    "",
                    "&eClick &7to continue")),
            (pl, right, shift) -> confirmDelete(plugin, pl, entry, page));

        StorageMenuLayout.addBackButton(menu, player, () -> open(plugin, player, page));
    }

    private static void verify(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Verifying " + ChatColor.WHITE
            + StorageMenuLayout.shorten(entry.metadata().backupId()) + ChatColor.GRAY + ".");

        StorageMenuLayout.runAsync(plugin, () -> {
            boolean missing = !entry.exists();
            boolean empty = entry.metadata().isEmpty();
            boolean valid = !missing && DatabaseBackupService.verifyBackup(entry);

            if (!missing) {
                File file = entry.dataFile();
                HEALTH.put(file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified(), valid);
            }

            StorageMenuLayout.sync(() -> {
                if (missing) {
                    player.sendMessage(ChatColor.RED + "The data file for this backup is gone.");
                } else if (empty) {
                    player.sendMessage(ChatColor.YELLOW + "This backup recorded no data and cannot be restored.");
                } else if (!valid) {
                    player.sendMessage(ChatColor.RED + "Checksum mismatch, this backup is corrupted.");
                } else {
                    player.sendMessage(ChatColor.GREEN + "Checksum matches, this backup is intact.");
                }

                open(plugin, player, page);
            });
        });
    }

    private static void confirmDelete(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        BackupMetadata meta = entry.metadata();

        StorageConfirmMenu.open(player, "&4Delete this backup?", Material.LAVA_BUCKET,
            "&cDelete a stored backup",
            StorageMenuLayout.lore(
                "&7Id:      &f" + StorageMenuLayout.shorten(meta.backupId()),
                "&7Created: &f" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
                "&7Entries: &f" + meta.totalRows(),
                "&7Size:    &f" + StorageMenuLayout.formatSize(Math.max(0L, entry.sizeBytes())),
                "",
                "&7Only the archive is removed.",
                "&7Live data is not touched."),
            CustomItemStack.create(Material.LAVA_BUCKET, "&c> Delete permanently",
                StorageMenuLayout.lore("&7This cannot be undone.", "", "&eClick &7to delete")),
            () -> doDelete(plugin, player, entry, page),
            () -> open(plugin, player, page));
    }

    private static void doDelete(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        String id = StorageMenuLayout.shorten(entry.metadata().backupId());

        StorageMenuLayout.runAsync(plugin, () -> {
            DatabaseBackupService.deleteBackup(entry);
            boolean gone = !entry.dataFile().exists();

            StorageMenuLayout.sync(() -> {
                if (gone) {
                    player.sendMessage(ChatColor.GREEN + "Backup deleted: " + ChatColor.WHITE + id);
                } else {
                    player.sendMessage(ChatColor.RED + "Could not delete " + ChatColor.WHITE + id
                        + ChatColor.RED + ", the file is still on disk.");
                    player.sendMessage(ChatColor.GRAY + "Check the file permissions of the backup folder.");
                }

                open(plugin, player, page);
            });
        });
    }

    private static void confirmRestore(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        BackupMetadata meta = entry.metadata();

        StorageConfirmMenu.open(player, "&6Restore this backup?", Material.LIME_WOOL,
            "&eStep 1 of 2: check the copy",
            StorageMenuLayout.lore(
                "&7Id:      &f" + StorageMenuLayout.shorten(meta.backupId()),
                "&7Created: &f" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
                "&7Format:  &f" + meta.format().getDisplayName(),
                "&7Entries: &f" + meta.totalRows(),
                "&7Health:  " + healthOf(entry).getLabel(),
                "",
                "&7Will overwrite: &f" + sections(meta),
                "",
                "&7The checksum is re-checked before",
                "&7anything is written."),
            CustomItemStack.create(Material.LIME_WOOL, "&a> Continue",
                StorageMenuLayout.lore("&7One more confirmation follows.", "", "&eClick &7to continue")),
            () -> confirmRestoreFinal(plugin, player, entry, page),
            () -> open(plugin, player, page));
    }

    private static void confirmRestoreFinal(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        BackupMetadata meta = entry.metadata();
        int online = plugin.getServer().getOnlinePlayers().size();

        StorageConfirmMenu.open(player, "&4Overwrite live data?", Material.REDSTONE_BLOCK,
            "&cStep 2 of 2: this replaces live data",
            StorageMenuLayout.lore(
                "&7Replacing: &f" + sections(meta),
                "&7From: &f" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
                "",
                "&7A snapshot of the current data is",
                "&7taken first, so this is reversible.",
                "",
                online > 1
                    ? "&c" + online + " players are online. Their data"
                    : "&7No other players are online.",
                online > 1 ? "&cmay be overwritten mid-session." : "&7This is the safest time to restore.",
                "",
                "&cThis is the last confirmation."),
            CustomItemStack.create(Material.REDSTONE_BLOCK, "&4> Overwrite live data",
                StorageMenuLayout.lore("&7Runs off the server thread.", "", "&eClick &7to restore")),
            () -> doRestore(plugin, player, entry, page),
            () -> open(plugin, player, page));
    }

    private static void doRestore(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull BackupEntry entry,
            int page) {

        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        player.sendMessage(ChatColor.GRAY + "Restoring, this can take a while.");

        StorageMenuLayout.runAsync(plugin, () -> {
            DatabaseRestoreService.RestoreResult result =
                DatabaseRestoreService.restore(entry, svc.getProvider());

            StorageMenuLayout.sync(() -> {
                if (result.success()) {
                    player.sendMessage(ChatColor.GREEN + "Restore complete: " + ChatColor.WHITE + result.message());
                    player.sendMessage(ChatColor.GRAY + "Sections: " + ChatColor.WHITE + result.tablesRestored()
                        + ChatColor.GRAY + ", entries: " + ChatColor.WHITE + result.rowsRestored());
                    player.sendMessage(ChatColor.YELLOW + "Restart the server so every plugin re-reads the data.");
                } else {
                    player.sendMessage(ChatColor.RED + "Restore failed: " + ChatColor.WHITE + result.message());
                    player.sendMessage(ChatColor.GRAY + "The live data was left as it was.");
                }

                open(plugin, player, page);
            });
        });
    }

    private static void createBackup(@Nonnull Plugin plugin, @Nonnull Player player, int page) {
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

                open(plugin, player, page);
            });
        });
    }

    @Nonnull
    private static String sections(@Nonnull BackupMetadata meta) {
        List<String> names = new ArrayList<>(meta.rowCounts().keySet());

        if (names.isEmpty()) {
            return "nothing";
        }

        names.sort(Comparator.naturalOrder());

        if (names.size() <= 3) {
            return String.join(", ", names);
        }

        return String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
    }

    @Nullable
    private static StorageEngineService service() {
        return Slimefun.instance() == null ? null : Slimefun.instance().getStorageEngineService();
    }

    private enum Health {

        OK("&aOK"),
        EMPTY("&cEMPTY"),
        MISMATCH("&cCHECKSUM MISMATCH"),
        MISSING("&cFILE MISSING");

        private final String label;

        Health(@Nonnull String label) {
            this.label = label;
        }

        @Nonnull
        String getLabel() {
            return label;
        }

        @Nonnull
        Material icon(@Nonnull BackupFormat format) {
            return switch (this) {
                case OK -> format == BackupFormat.LEGACY_FILES ? Material.CHEST : Material.PAPER;
                case EMPTY -> Material.RED_STAINED_GLASS_PANE;
                case MISMATCH -> Material.ORANGE_STAINED_GLASS_PANE;
                case MISSING -> Material.BARRIER;
            };
        }
    }
}
