package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.Checkpoint;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointChangeStatus;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointComponent;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointMetadata;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointResult;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointStatus;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The paged checkpoint browser of {@code /sf storage gui}.
 *
 * <p>A checkpoint is one complete Slimefun state made of two independent halves: a storage
 * archive of the file based Slimefun data tree, and a database backup holding database owned
 * player data only. Minecraft world data is never part of either half. This screen shows both
 * halves of every checkpoint together with their statuses, entry counts and checksums.
 *
 * <p>Restoring is deliberately absent here. A restore replaces the live data of both halves at
 * once, so it may only be started from the server console. Everything on this screen is read
 * once per open on an async task, because listing, hashing and counting must not run on the
 * server thread.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class CheckpointBrowserMenu {

    private static final String PERM_CHECKPOINT_MANAGE = "slimefun.admin.checkpoint";

    private static final int SLOT_SCOPE = 45;
    private static final int SLOT_REFRESH = 47;
    private static final int SLOT_LAST = 48;
    private static final int SLOT_CREATE = 49;
    private static final int SLOT_CHANGES = 50;
    private static final int SLOT_GUIDE = 51;

    private static final int SLOT_DETAIL_ICON = 4;
    private static final int SLOT_DETAIL_STORAGE = 20;
    private static final int SLOT_DETAIL_DATABASE = 24;
    private static final int SLOT_DETAIL_SCOPE = 31;
    private static final int SLOT_DETAIL_CONSOLE = 47;
    private static final int SLOT_DETAIL_VERIFY = 49;
    private static final int SLOT_DETAIL_CHANGES = 51;
    private static final int SLOT_EMPTY_NOTICE = 22;

    private static final int COUNT_LINE_CAP = 6;
    private static final int CHECKSUM_PREVIEW = 16;

    private CheckpointBrowserMenu() {}

    static void open(@Nonnull Plugin plugin, @Nonnull Player player, int page) {
        StorageMenuLayout.runAsync(plugin, () -> {
            Snapshot snapshot = Snapshot.capture();

            StorageMenuLayout.sync(() -> {
                if (snapshot == null) {
                    player.sendMessage(ChatColor.RED + "The checkpoint service is not available yet.");
                    return;
                }

                build(plugin, player, snapshot, page).open(player);
            });
        });
    }

    @Nonnull
    private static ChestMenu build(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull Snapshot snapshot,
            int page) {

        List<Checkpoint> checkpoints = snapshot.checkpoints();
        int size = StorageMenuLayout.PAGE_SIZE;
        int pages = Math.max(1, (checkpoints.size() + size - 1) / size);
        int current = Math.max(1, Math.min(page, pages));

        ChestMenu menu = StorageMenuLayout.newMenu("&2Checkpoints &7(" + current + "/" + pages + ")");
        StorageMenuLayout.paintNavigationRow(menu);

        int first = (current - 1) * size;

        for (int index = 0; index < size; index++) {
            int position = first + index;

            if (position >= checkpoints.size()) {
                break;
            }

            Checkpoint checkpoint = checkpoints.get(position);
            StorageMenuLayout.addButton(menu, index, describe(checkpoint),
                (pl, right, shift) -> inspect(plugin, pl, snapshot, checkpoint, current));
        }

        if (checkpoints.isEmpty()) {
            StorageMenuLayout.addInfoItem(menu, SLOT_EMPTY_NOTICE, Material.BARRIER, "&7No checkpoints stored",
                StorageMenuLayout.lore(
                    "&7Nothing has been checkpointed yet.",
                    "&7Folder: &f" + snapshot.directory(),
                    "",
                    "&7Use the button below, or run",
                    "&f/sf checkpoint create&7."));
        }

        addNavigation(plugin, player, menu, snapshot, current, pages);

        return menu;
    }

    private static void addNavigation(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Snapshot snapshot,
            int current,
            int pages) {

        StorageMenuLayout.addInfoItem(menu, SLOT_SCOPE, Material.KNOWLEDGE_BOOK, "&bWhat a checkpoint holds",
            StorageMenuLayout.lore(
                "&7Storage half: &fSlimefun data files",
                "&8  players, waypoints, blocks, chunks",
                "&7Database half: &fplayer records only",
                "",
                "&cMinecraft world data is never included.",
                "&7Regions, level.dat and playerdata stay",
                "&7outside every checkpoint.",
                "",
                "&7Kept: &f" + snapshot.checkpoints().size() + " &7/ &f" + snapshot.max(),
                "&7Total size: &f" + StorageMenuLayout.formatSize(snapshot.totalBytes()),
                "&7Folder: &f" + snapshot.directory()));

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

        StorageMenuLayout.addButton(menu, SLOT_REFRESH,
            CustomItemStack.create(Material.COMPASS, "&b> Refresh",
                StorageMenuLayout.lore(
                    "&7Re-reads the folder and re-checks",
                    "&7whether the data changed since the",
                    "&7newest checkpoint.")),
            (pl, right, shift) -> open(plugin, pl, current));

        StorageMenuLayout.addInfoItem(menu, SLOT_LAST, Material.PAPER, "&bLast checkpoint attempt",
            snapshot.lastAttempt());

        StorageMenuLayout.addButton(menu, SLOT_CREATE, createIcon(snapshot),
            (pl, right, shift) -> createCheckpoint(plugin, pl, current));

        StorageMenuLayout.addInfoItem(menu, SLOT_CHANGES, Material.COMPARATOR, "&bChange detection",
            changeLore(snapshot));

        StorageMenuLayout.addButton(menu, SLOT_GUIDE,
            CustomItemStack.create(Material.ENCHANTED_BOOK, "&b> Guide",
                StorageMenuLayout.lore(
                    "&7What each half holds, which keys",
                    "&7control checkpoints, the order used",
                    "&7at the limit, and how a restore runs.",
                    "",
                    "&eClick &7to open")),
            (pl, right, shift) -> StorageGuideMenu.openCheckpointPage(plugin, pl));

        StorageMenuLayout.addBackButton(menu, player, () -> StorageGuiMenu.open(plugin, player));
    }

    @Nonnull
    private static ItemStack createIcon(@Nonnull Snapshot snapshot) {
        if (!snapshot.enabled()) {
            return CustomItemStack.create(Material.GRAY_DYE, "&8> Checkpoints are disabled",
                StorageMenuLayout.lore(
                    "&7Set &fcheckpoint.enabled: true",
                    "&7in &fdatabase.yml &7to create one.",
                    "",
                    "&7Stored checkpoints stay readable and",
                    "&7restorable while this is off."));
        }

        if (snapshot.busy()) {
            return CustomItemStack.create(Material.CLOCK, "&e> A checkpoint is running",
                StorageMenuLayout.lore(
                    "&7Only one checkpoint operation runs",
                    "&7at a time. Refresh in a moment."));
        }

        return CustomItemStack.create(Material.ENDER_CHEST, "&a> Create a checkpoint now",
            StorageMenuLayout.lore(
                "&7Backs up the storage half and the",
                "&7database half, verifies both, and only",
                "&7then removes the oldest checkpoint if",
                "&7the limit of &f" + snapshot.max() + " &7is reached.",
                "",
                "&7Needs &f" + PERM_CHECKPOINT_MANAGE,
                "",
                "&eClick &7to start"));
    }

    @Nonnull
    private static List<String> changeLore(@Nonnull Snapshot snapshot) {
        CheckpointChangeStatus change = snapshot.change();

        return StorageMenuLayout.lore(
            "&7Last checkpoint: &f"
                + (change.lastCheckpointId() == null ? "none" : change.lastCheckpointId()),
            "&7Created: &f" + StorageMenuLayout.formatTimestamp(change.lastCreatedAt()),
            "",
            "&7Storage changed:  " + flag(change.storageChanged()),
            "&7Database changed: " + flag(change.databaseChanged()),
            "&7Checkpoint required: " + flag(change.checkpointRequired()),
            "&7Reason: &f" + change.detail(),
            "",
            "&7Schedule: &f" + describeSchedule(snapshot),
            "&8Change detection never creates a",
            "&8checkpoint on its own.");
    }

    @Nonnull
    private static ItemStack describe(@Nonnull Checkpoint checkpoint) {
        CheckpointMetadata meta = checkpoint.metadata();

        return CustomItemStack.create(icon(checkpoint),
            "&f" + meta.checkpointId() + " &7" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
            StorageMenuLayout.lore(
                "&7Storage half:  " + half(meta.storage()),
                "&7Database half: " + half(meta.database()),
                "&7Size:       &f" + StorageMenuLayout.formatSize(checkpoint.totalBytes()),
                "&7Entries:    &f" + meta.totalEntries(),
                "&7Provider:   &f" + meta.provider(),
                "&7Slimefun:   &f" + meta.slimefunVersion(),
                "&7Trigger:    &f" + (meta.automatic() ? "automatic" : "manual"),
                "&7Restorable: " + (checkpoint.isRestorable() ? "&aYES" : "&cNO"),
                "",
                "&eClick &7to inspect both halves"));
    }

    private static void inspect(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull Snapshot snapshot,
            @Nonnull Checkpoint checkpoint,
            int page) {

        CheckpointMetadata meta = checkpoint.metadata();

        ChestMenu menu = StorageMenuLayout.newMenu("&2" + meta.checkpointId());
        StorageMenuLayout.fillBackground(menu);

        StorageMenuLayout.addInfoItem(menu, SLOT_DETAIL_ICON, icon(checkpoint), "&aCheckpoint details",
            StorageMenuLayout.lore(
                "&7Id:         &f" + meta.checkpointId(),
                "&7Created:    &f" + StorageMenuLayout.formatTimestamp(meta.createdAt()),
                "&7Slimefun:   &f" + meta.slimefunVersion(),
                "&7Server:     &f" + meta.serverVersion(),
                "&7Provider:   &f" + meta.provider() + " &7(" + meta.providerType() + ")",
                "&7Metadata:   &fversion " + meta.formatVersion(),
                "&7Trigger:    &f" + (meta.automatic() ? "automatic" : "manual"),
                "&7Entries:    &f" + meta.totalEntries(),
                "&7Size:       &f" + StorageMenuLayout.formatSize(checkpoint.totalBytes()),
                "&7Valid:      " + (meta.isValid() ? "&aYES" : "&cNO"),
                "&7Restorable: " + (checkpoint.isRestorable() ? "&aYES" : "&cNO"),
                "&7Folder: &f" + checkpoint.directory().getPath()));

        addHalfTile(menu, SLOT_DETAIL_STORAGE, Material.CHEST, "&bStorage half",
            meta.storage(), checkpoint.storageFile(),
            "&7A ZIP of the file based Slimefun data",
            "&7tree: players, waypoints, blocks,",
            "&7inventories and chunk data.");

        addHalfTile(menu, SLOT_DETAIL_DATABASE, Material.PAPER, "&bDatabase half",
            meta.database(), checkpoint.databaseFile(),
            "&7Database owned player data only:",
            "&7researches, backpacks and waypoints.",
            "&7Never blocks, never world data.");

        StorageMenuLayout.addInfoItem(menu, SLOT_DETAIL_SCOPE, Material.KNOWLEDGE_BOOK, "&bWhere the blocks are",
            StorageMenuLayout.lore(
                "&7Machines, chests and chunk data are",
                "&7always stored as files, on every",
                "&7provider, so they live in the storage",
                "&7half and not in the database half.",
                "",
                "&cNo Minecraft world data is included.",
                "&7Restoring never touches regions,",
                "&7level.dat, entities or playerdata."));

        addDetailNavigation(plugin, player, menu, snapshot, checkpoint, page);

        menu.open(player);
    }

    private static void addDetailNavigation(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Snapshot snapshot,
            @Nonnull Checkpoint checkpoint,
            int page) {

        StorageMenuLayout.addInfoItem(menu, SLOT_DETAIL_CONSOLE, Material.COMMAND_BLOCK,
            "&eRestoring is console only",
            StorageMenuLayout.lore(
                "&7A restore replaces the live data of",
                "&7the selected half, so it can only be",
                "&7started from the server console.",
                "",
                "&7With no players online, run:",
                "&f/sf checkpoint restore storage " + checkpoint.id(),
                "&f/sf checkpoint restore database " + checkpoint.id(),
                "&f/sf checkpoint restore " + checkpoint.id() + " confirm",
                "",
                "&7A safety copy of the current state is",
                "&7taken first, and newer checkpoints are",
                "&7never deleted by a restore."));

        StorageMenuLayout.addButton(menu, SLOT_DETAIL_VERIFY,
            CustomItemStack.create(Material.SPYGLASS, "&b> Verify both halves",
                StorageMenuLayout.lore(
                    "&7Re-hashes every captured half and",
                    "&7compares it with the checksum in",
                    "&7metadata.json.",
                    "",
                    "&eClick &7to verify")),
            (pl, right, shift) -> verify(plugin, pl, checkpoint, page));

        StorageMenuLayout.addInfoItem(menu, SLOT_DETAIL_CHANGES, Material.COMPARATOR, "&bChange detection",
            changeLore(snapshot));

        StorageMenuLayout.addBackButton(menu, player, () -> open(plugin, player, page));
    }

    private static void addHalfTile(
            @Nonnull ChestMenu menu,
            int slot,
            @Nonnull Material material,
            @Nonnull String title,
            @Nonnull CheckpointComponent component,
            @Nullable File file,
            @Nonnull String... notes) {

        List<String> lines = StorageMenuLayout.lore(
            "&7Status:   " + colour(component.status()) + component.status().getDisplayName(),
            "&7Format:   &f" + component.format().getDisplayName(),
            "&7Entries:  &f" + component.totalEntries(),
            "&7Size:     &f" + StorageMenuLayout.formatSize(component.bytes()),
            "&7Checksum: &f" + preview(component.checksum()),
            "&7File:     &f" + (file == null ? "none" : file.getName()),
            "&7On disk:  " + onDisk(component, file));

        if (!component.detail().isBlank()) {
            lines.add("&7Detail: &f" + component.detail());
        }

        lines.add("");
        lines.addAll(describeCounts(component.counts()));
        lines.add("");
        lines.addAll(List.of(notes));

        StorageMenuLayout.addInfoItem(menu, slot, material, title, lines);
    }

    private static void verify(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull Checkpoint checkpoint,
            int page) {

        CheckpointMetadata meta = checkpoint.metadata();

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Verifying " + ChatColor.WHITE + meta.checkpointId()
            + ChatColor.GRAY + ", both halves.");

        StorageMenuLayout.runAsync(plugin, () -> {
            String storage = verifyHalf(meta.storage(), checkpoint.storageFile());
            String database = verifyHalf(meta.database(), checkpoint.databaseFile());

            StorageMenuLayout.sync(() -> {
                player.sendMessage(ChatColor.GRAY + "Storage half:  " + storage);
                player.sendMessage(ChatColor.GRAY + "Database half: " + database);
                open(plugin, player, page);
            });
        });
    }

    @Nonnull
    private static String verifyHalf(@Nonnull CheckpointComponent component, @Nullable File file) {
        if (!component.isPresent()) {
            return ChatColor.GRAY + "nothing was captured (" + component.status().getDisplayName() + ")";
        }

        if (file == null || !file.isFile()) {
            return ChatColor.RED + "the captured file is gone";
        }

        try {
            return DatabaseBackupService.sha256Hex(file).equalsIgnoreCase(component.checksum())
                ? ChatColor.GREEN + "checksum matches, intact"
                : ChatColor.RED + "checksum mismatch, corrupted";
        } catch (IOException x) {
            String reason = x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage();
            return ChatColor.RED + "could not be read: " + reason;
        }
    }

    private static void createCheckpoint(@Nonnull Plugin plugin, @Nonnull Player player, int page) {
        if (!StorageMenuLayout.requireManage(player) || !requireCheckpointManage(player)) {
            return;
        }

        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        CheckpointService service = svc.getCheckpointService();

        if (service == null) {
            player.sendMessage(ChatColor.RED + "The checkpoint service is not available yet.");
            return;
        }

        if (!service.isEnabled()) {
            player.sendMessage(ChatColor.RED + "Checkpoints are disabled in database.yml.");
            player.sendMessage(ChatColor.GRAY + "Set " + ChatColor.WHITE + "checkpoint.enabled: true"
                + ChatColor.GRAY + " to create one.");
            return;
        }

        if (service.isBusy()) {
            player.sendMessage(ChatColor.YELLOW + "A checkpoint operation is already running.");
            return;
        }

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Creating a checkpoint: storage half plus database half.");

        StorageMenuLayout.runAsync(plugin, () -> {
            CheckpointResult result = svc.performCheckpoint(false);

            StorageMenuLayout.sync(() -> {
                report(player, result);
                open(plugin, player, page);
            });
        });
    }

    private static void report(@Nonnull Player player, @Nonnull CheckpointResult result) {
        if (!result.success()) {
            player.sendMessage(ChatColor.RED + "Checkpoint failed: " + ChatColor.WHITE + result.message());
            player.sendMessage(ChatColor.GRAY
                + "The incomplete directory was removed and no old checkpoint was deleted.");
            return;
        }

        Checkpoint checkpoint = result.checkpoint();
        CheckpointMetadata meta = checkpoint == null ? null : checkpoint.metadata();

        player.sendMessage(ChatColor.GREEN + "Checkpoint created: " + ChatColor.WHITE + result.checkpointId());

        if (meta != null) {
            player.sendMessage(ChatColor.GRAY + "Storage half:  " + ChatColor.WHITE + plain(meta.storage()));
            player.sendMessage(ChatColor.GRAY + "Database half: " + ChatColor.WHITE + plain(meta.database()));
        }

        player.sendMessage(ChatColor.GRAY + "Took " + ChatColor.WHITE + result.durationMillis()
            + " ms" + ChatColor.GRAY + ", both halves verified.");

        if (!result.purged().isEmpty()) {
            player.sendMessage(ChatColor.GRAY + "Retention removed: " + ChatColor.WHITE
                + String.join(", ", result.purged()));
        }
    }

    private static boolean requireCheckpointManage(@Nonnull Player player) {
        if (player.hasPermission(PERM_CHECKPOINT_MANAGE)) {
            return true;
        }

        player.sendMessage(ChatColor.RED + "You need " + PERM_CHECKPOINT_MANAGE + " to do that.");
        return false;
    }

    @Nonnull
    private static Material icon(@Nonnull Checkpoint checkpoint) {
        if (!checkpoint.metadata().isValid()) {
            return Material.BARRIER;
        }

        return checkpoint.isRestorable() ? Material.SHULKER_BOX : Material.ORANGE_STAINED_GLASS_PANE;
    }

    @Nonnull
    private static String half(@Nonnull CheckpointComponent component) {
        return colour(component.status()) + component.status().getDisplayName()
            + " &7(" + component.totalEntries() + " entries, "
            + StorageMenuLayout.formatSize(component.bytes()) + ")";
    }

    @Nonnull
    private static String plain(@Nonnull CheckpointComponent component) {
        return component.status().getDisplayName() + ", " + component.totalEntries() + " entries, "
            + StorageMenuLayout.formatSize(component.bytes());
    }

    @Nonnull
    private static String colour(@Nonnull CheckpointStatus status) {
        return switch (status) {
            case OK -> "&a";
            case SKIPPED -> "&e";
            case FAILED -> "&c";
        };
    }

    @Nonnull
    private static String onDisk(@Nonnull CheckpointComponent component, @Nullable File file) {
        if (!component.isPresent()) {
            return "&7n/a";
        }

        return file != null && file.isFile() ? "&aYES" : "&cMISSING";
    }

    @Nonnull
    private static String flag(boolean value) {
        return value ? "&eYES" : "&aNO";
    }

    @Nonnull
    private static String preview(@Nonnull String checksum) {
        if (checksum.isEmpty()) {
            return "none";
        }

        return checksum.length() <= CHECKSUM_PREVIEW
            ? checksum
            : checksum.substring(0, CHECKSUM_PREVIEW) + "...";
    }

    @Nonnull
    private static String describeSchedule(@Nonnull Snapshot snapshot) {
        if (!snapshot.enabled()) {
            return "disabled";
        }

        if (!snapshot.automatic()) {
            return "manual only";
        }

        return "every " + snapshot.intervalHours() + "h"
            + (snapshot.onlyWhenChanged() ? ", skipped when nothing changed" : "");
    }

    @Nonnull
    private static List<String> describeCounts(@Nonnull Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return StorageMenuLayout.lore("&8No sections recorded.");
        }

        List<String> lines = new ArrayList<>();
        int shown = 0;

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (shown == COUNT_LINE_CAP) {
                lines.add("&8" + (counts.size() - shown) + " more section(s) not shown");
                break;
            }

            lines.add("&8  " + entry.getKey() + ": &7" + entry.getValue());
            shown++;
        }

        return lines;
    }

    @Nonnull
    private static List<String> describeResult(@Nullable CheckpointResult result) {
        if (result == null) {
            return StorageMenuLayout.lore(
                "&7No checkpoint has been attempted",
                "&7since this server was started.",
                "",
                "&7The scheduler and the button next to",
                "&7this one both report their result here.");
        }

        if (!result.success()) {
            return StorageMenuLayout.lore(
                "&cFailed &7at &f" + StorageMenuLayout.formatTimestamp(result.timestamp()),
                "&7Reason: &f" + result.message(),
                "&7Trigger: &f" + (result.automatic() ? "automatic" : "manual"),
                "",
                "&7The incomplete directory was removed",
                "&7and no old checkpoint was deleted.");
        }

        return StorageMenuLayout.lore(
            "&aSucceeded &7at &f" + StorageMenuLayout.formatTimestamp(result.timestamp()),
            "&7Id:      &f" + result.checkpointId(),
            "&7Took:    &f" + result.durationMillis() + " ms",
            "&7Trigger: &f" + (result.automatic() ? "automatic" : "manual"),
            "&7Removed: &f" + (result.purged().isEmpty() ? "nothing" : String.join(", ", result.purged())),
            "",
            "&7Both halves were verified before the",
            "&7oldest checkpoint was considered.");
    }

    @Nullable
    private static StorageEngineService service() {
        return Slimefun.instance() == null ? null : Slimefun.instance().getStorageEngineService();
    }

    private record Snapshot(
        @Nonnull List<Checkpoint> checkpoints,
        @Nonnull CheckpointChangeStatus change,
        @Nonnull List<String> lastAttempt,
        @Nonnull String directory,
        int max,
        boolean enabled,
        boolean automatic,
        boolean onlyWhenChanged,
        int intervalHours,
        boolean busy,
        long totalBytes
    ) {

        @Nullable
        static Snapshot capture() {
            StorageEngineService svc = service();

            if (svc == null) {
                return null;
            }

            CheckpointService service = svc.getCheckpointService();

            if (service == null) {
                return null;
            }

            List<Checkpoint> checkpoints = service.list();
            long bytes = 0L;

            for (Checkpoint checkpoint : checkpoints) {
                bytes += checkpoint.totalBytes();
            }

            return new Snapshot(
                checkpoints,
                service.status(svc.getProvider()),
                describeResult(service.getLastResult()),
                service.getDirectory().getPath(),
                service.getMaxCheckpoints(),
                service.isEnabled(),
                service.isAutomatic(),
                service.isOnlyWhenChanged(),
                service.getIntervalHours(),
                service.isBusy(),
                bytes);
        }
    }
}
