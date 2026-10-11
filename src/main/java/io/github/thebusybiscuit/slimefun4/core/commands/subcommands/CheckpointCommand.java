package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.Checkpoint;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointChangeStatus;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointComponent;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointMetadata;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointRestoreResult;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointResult;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointStatus;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.RestoreScope;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.WorldBackupService;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.WorldSnapshot;
import io.github.thebusybiscuit.slimefun4.storage.migration.StorageMigrator;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implements the {@code /sf checkpoint} sub-command tree.
 *
 * <p>A checkpoint is one complete Slimefun backup state holding two independent halves under a
 * single {@code checkpoint-NNN} directory: a storage backup of the file-based Slimefun data tree
 * and a database backup of database-owned player data. Neither half ever holds Minecraft world
 * data, and the two are never mixed.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code /sf checkpoint}                — alias for {@code list}</li>
 *   <li>{@code /sf checkpoint create}         — create and verify a checkpoint now</li>
 *   <li>{@code /sf checkpoint list}           — every valid checkpoint, newest first</li>
 *   <li>{@code /sf checkpoint info &lt;id&gt;}     — one checkpoint in full detail</li>
 *   <li>{@code /sf checkpoint status}         — whether data changed since the newest one</li>
 *   <li>{@code /sf checkpoint restore storage &lt;id&gt;}  — restore only the storage half, console only</li>
 *   <li>{@code /sf checkpoint restore database &lt;id&gt;} — restore only the database half, console only</li>
 *   <li>{@code /sf checkpoint restore &lt;id&gt; confirm}   — restore both halves, console only</li>
 * </ul>
 *
 * <p>Restoring is deliberately console-only because it replaces live Slimefun files or
 * database-owned records and must not be reachable from in-game chat.
 *
 * <p>Migration is a different operation and lives under {@code /sf storage migrate}. It moves
 * database-owned player data between providers and never touches checkpoint history.
 *
 * <p>All file and database work runs asynchronously via Bukkit's scheduler.
 *
 * @author SlimefunCore Contributors
 */
@Beta
class CheckpointCommand extends SubCommand {

    private static final ChatColor GOLD   = ChatColor.GOLD;
    private static final ChatColor GREEN  = ChatColor.GREEN;
    private static final ChatColor RED    = ChatColor.RED;
    private static final ChatColor GRAY   = ChatColor.GRAY;
    private static final ChatColor YELLOW = ChatColor.YELLOW;
    private static final ChatColor WHITE  = ChatColor.WHITE;

    private static final String PERM_VIEW   = "slimefun.command.checkpoint";
    private static final String PERM_MANAGE = "slimefun.admin.checkpoint";

    private static final int STEP_LINE_CAP = 12;
    private static final int CHECKSUM_PREVIEW = 16;

    private static final DateTimeFormatter FMT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    @ParametersAreNonnullByDefault
    CheckpointCommand(Slimefun plugin, SlimefunCommand cmd) {
        super(plugin, cmd, "checkpoint", false);
    }

    @Override
    public void onExecute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        switch (sub) {
            case "create"  -> dispatchCreate(sender);
            case "list"    -> dispatchList(sender);
            case "info"    -> dispatchInfo(sender, args);
            case "status"  -> dispatchStatus(sender);
            case "restore" -> dispatchRestore(sender, args);
            case "worlds"  -> dispatchWorlds(sender, args);
            default        -> sendHelp(sender);
        }
    }

    private void dispatchCreate(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }
        CheckpointService service = svc.getCheckpointService();
        if (service == null) { sendNotReady(sender); return; }

        if (!service.isEnabled()) {
            sender.sendMessage(RED + "The checkpoint system is disabled in database.yml.");
            sender.sendMessage(GRAY + "Set checkpoint.enabled to true and restart the server.");
            return;
        }

        if (service.isBusy()) {
            sender.sendMessage(YELLOW + "Another checkpoint operation is already running.");
            return;
        }

        sender.sendMessage(GOLD + "Creating a checkpoint: storage half + database half...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            CheckpointResult result = svc.performCheckpoint(false);
            List<String> lines = new ArrayList<>();

            if (result.success()) {
                lines.add(GREEN + "Checkpoint created: " + WHITE + result.checkpointId());
                Checkpoint checkpoint = result.checkpoint();

                if (checkpoint != null) {
                    lines.add(GRAY + "  Storage half  : " + describeHalf(checkpoint.metadata().storage()));
                    lines.add(GRAY + "  Database half : " + describeHalf(checkpoint.metadata().database()));
                    lines.add(GRAY + "  Total size    : " + WHITE + kilobytes(checkpoint.totalBytes()));
                }

                lines.add(GRAY + "  Took          : " + WHITE + result.durationMillis() + " ms");

                if (!result.purged().isEmpty()) {
                    lines.add(GRAY + "  Retention removed: " + WHITE + String.join(", ", result.purged()));
                }
            } else {
                lines.add(RED + "Checkpoint failed: " + result.message());
                lines.add(GRAY + "The incomplete directory was removed, no old checkpoint was deleted.");
            }

            appendSteps(lines, result.steps());
            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchList(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }
        CheckpointService service = svc.getCheckpointService();
        if (service == null) { sendNotReady(sender); return; }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Checkpoint> checkpoints = service.list();

            if (checkpoints.isEmpty()) {
                syncMessage(sender,
                    GRAY + "No checkpoints found in " + service.getDirectory().getPath(),
                    GRAY + "Run " + WHITE + "/sf checkpoint create" + GRAY + " to make the first one.");
                return;
            }

            List<String> lines = new ArrayList<>();
            lines.add(GOLD + "===== Checkpoints (" + checkpoints.size()
                + " kept, limit " + service.getMaxCheckpoints() + ") =====");

            for (Checkpoint checkpoint : checkpoints) {
                CheckpointMetadata metadata = checkpoint.metadata();

                lines.add(WHITE + metadata.checkpointId() + GRAY + "  " + FMT.format(metadata.createdAt())
                    + "  " + (metadata.automatic() ? "[auto]" : "[manual]")
                    + "  " + metadata.provider());
                lines.add(GRAY + "    storage " + describeHalf(metadata.storage())
                    + GRAY + ", database " + describeHalf(metadata.database()));
            }

            lines.add(GRAY + "Detail: " + WHITE + "/sf checkpoint info <id>");
            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchStatus(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }
        CheckpointService service = svc.getCheckpointService();
        if (service == null) { sendNotReady(sender); return; }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            CheckpointChangeStatus status = service.status(svc.getProvider());
            List<String> lines = new ArrayList<>();

            lines.add(GOLD + "===== Checkpoint status =====");
            lines.add(GRAY + "Last checkpoint    : " + WHITE + (status.lastCheckpointId() == null
                ? "none" : status.lastCheckpointId()));
            lines.add(GRAY + "Created            : " + WHITE + (status.lastCreatedAt() == null
                ? "never" : FMT.format(status.lastCreatedAt())));
            lines.add(GRAY + "Storage changed    : " + yesNo(status.storageChanged()));
            lines.add(GRAY + "Database changed   : " + yesNo(status.databaseChanged()));
            lines.add(GRAY + "Checkpoint required: " + yesNo(status.checkpointRequired()));
            lines.add(GRAY + "Reason             : " + WHITE + status.detail());
            lines.add(GRAY + "Kept               : " + WHITE + status.total() + " / " + status.max());
            lines.add(GRAY + "Schedule           : " + WHITE + describeSchedule(service));
            lines.add(GRAY + "Running now        : " + WHITE + (service.isBusy() ? "yes" : "no"));
            lines.add(GRAY + "Directory          : " + WHITE + service.getDirectory().getPath());
            lines.add(GRAY + "Change detection never creates a checkpoint on its own.");

            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchInfo(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_VIEW)) return;

        if (args.length < 3) {
            sender.sendMessage(RED + "Usage: /sf checkpoint info <id>");
            sender.sendMessage(GRAY + "Accepts 3, 003 or checkpoint-003.");
            return;
        }

        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }
        CheckpointService service = svc.getCheckpointService();
        if (service == null) { sendNotReady(sender); return; }

        String reference = args[2];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Checkpoint checkpoint = service.find(reference);

            if (checkpoint == null) {
                syncMessage(sender, RED + "No checkpoint matches: " + reference,
                    GRAY + "Run " + WHITE + "/sf checkpoint list" + GRAY + " to see the ids.");
                return;
            }

            CheckpointMetadata metadata = checkpoint.metadata();
            List<String> lines = new ArrayList<>();

            lines.add(GOLD + "===== " + metadata.checkpointId() + " =====");
            lines.add(GRAY + "Created    : " + WHITE + FMT.format(metadata.createdAt())
                + GRAY + " " + (metadata.automatic() ? "[auto]" : "[manual]"));
            lines.add(GRAY + "Slimefun   : " + WHITE + metadata.slimefunVersion());
            lines.add(GRAY + "Server     : " + WHITE + metadata.serverVersion());
            lines.add(GRAY + "Provider   : " + WHITE + metadata.provider()
                + GRAY + " (" + metadata.providerType() + ")");
            lines.add(GRAY + "Metadata   : " + WHITE + "format v" + metadata.formatVersion());
            lines.add(GRAY + "Valid      : " + yesNo(metadata.isValid()));
            lines.add(GRAY + "Restorable : " + yesNo(checkpoint.isRestorable()));
            lines.add(GRAY + "Directory  : " + WHITE + checkpoint.directory().getPath());

            appendHalf(lines, "Storage half — file-based Slimefun data",
                metadata.storage(), Checkpoint.STORAGE_DIRECTORY);
            appendHalf(lines, "Database half — database-owned player data only",
                metadata.database(), Checkpoint.DATABASE_DIRECTORY);

            lines.add(GRAY + "Blocks, machines and chunk data are part of the storage half on every"
                + " provider, so they are not database data.");

            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchRestore(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;

        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(RED + "/sf checkpoint restore is console-only.");
            sender.sendMessage(GRAY + "It replaces Slimefun files and/or database-owned records,");
            sender.sendMessage(GRAY + "so it must be run from the server console.");
            sender.sendMessage(GRAY + "In game you can still use " + WHITE + "/sf checkpoint list"
                + GRAY + ", " + WHITE + "info" + GRAY + " and " + WHITE + "status" + GRAY + ".");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(RED + "Usage:");
            sender.sendMessage(GRAY + "  /sf checkpoint restore storage <id>   " + WHITE + "— restore only file/storage data");
            sender.sendMessage(GRAY + "  /sf checkpoint restore database <id>  " + WHITE + "— restore only database data");
            sender.sendMessage(GRAY + "  /sf checkpoint restore <id> confirm   " + WHITE + "— restore both halves");
            return;
        }

        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }
        CheckpointService service = svc.getCheckpointService();
        if (service == null) { sendNotReady(sender); return; }

        RestoreScope scope;
        String reference;

        if (args[2].equalsIgnoreCase("storage")) {
            scope = RestoreScope.STORAGE_ONLY;
            if (args.length != 4) {
                sender.sendMessage(RED + "Usage: /sf checkpoint restore storage <id>");
                return;
            }
            reference = args[3];
        } else if (args[2].equalsIgnoreCase("database")) {
            scope = RestoreScope.DATABASE_ONLY;
            if (args.length != 4) {
                sender.sendMessage(RED + "Usage: /sf checkpoint restore database <id>");
                return;
            }
            reference = args[3];
        } else {
            scope = RestoreScope.BOTH;
            if (args.length != 4 || !args[3].equalsIgnoreCase("confirm")) {
                sender.sendMessage(YELLOW + "This restores both checkpoint halves.");
                sender.sendMessage(RED + "Confirm with: /sf checkpoint restore " + args[2] + " confirm");
                return;
            }
            reference = args[2];
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Checkpoint checkpoint = service.find(reference);

            if (checkpoint == null) {
                syncMessage(sender, RED + "No checkpoint matches: " + reference,
                    GRAY + "Run " + WHITE + "/sf checkpoint list" + GRAY + " to see the ids.");
                return;
            }

            int online = StorageMigrator.onlinePlayers(plugin).getAsInt();

            if (online > 0) {
                syncMessage(sender,
                    RED + "Refusing to restore while " + online + " player(s) are online.",
                    GRAY + "Empty the server first. Nothing was read and nothing was changed.");
                return;
            }

            syncMessage(sender, YELLOW + "Restoring " + checkpoint.id()
                + " (" + scope.getDisplayName() + "), this may take a while...");

            CheckpointRestoreResult result;

            switch (scope) {
                case STORAGE_ONLY  -> result = service.restoreStorage(svc.getProvider(), checkpoint);
                case DATABASE_ONLY -> result = service.restoreDatabase(svc.getProvider(), checkpoint);
                default            -> result = service.restore(svc.getProvider(), checkpoint);
            }

            List<String> lines = new ArrayList<>();

            if (result.success()) {
                lines.add(GREEN + "Restore complete: " + result.message());
                lines.add(YELLOW + "Restart the server now so the restored data is fully in use.");
            } else {
                lines.add(RED + "Restore failed: " + result.message());
                lines.add(result.rolledBack()
                    ? GREEN + "The previous state was rolled back from the safety copy."
                    : YELLOW + "No rollback was needed or possible, see the steps below.");
            }

            if (result.safetyBackupId() != null) {
                lines.add(GRAY + "Safety copy: " + WHITE + result.safetyBackupId()
                    + GRAY + " in " + service.getSafetyDirectory().getPath());
            }

            lines.add(GRAY + "Scope: " + WHITE + result.scope().getDisplayName());
            appendSteps(lines, result.steps());
            lines.add(GRAY + "Checkpoint history is unchanged: " + WHITE + service.list().size()
                + GRAY + " checkpoint(s) still on disk.");

            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchWorlds(@Nonnull CommandSender sender, @Nonnull String[] args) {
        String action = args.length >= 3 ? args[2].toLowerCase() : "list";

        switch (action) {
            case "create"  -> dispatchWorldsCreate(sender);
            case "list"    -> dispatchWorldsList(sender);
            case "info"    -> dispatchWorldsInfo(sender, args);
            case "restore" -> dispatchWorldsRestore(sender, args);
            default        -> dispatchWorldsList(sender);
        }
    }

    private void dispatchWorldsCreate(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        WorldBackupService service = getWorldService();
        if (service == null) { sendNotReady(sender); return; }

        if (!service.isEnabled()) {
            sender.sendMessage(RED + "World backups are disabled in database.yml.");
            sender.sendMessage(GRAY + "Set world-backup.enabled to true and restart the server.");
            return;
        }

        if (service.isBusy()) {
            sender.sendMessage(YELLOW + "Another world backup operation is already running.");
            return;
        }

        sender.sendMessage(GOLD + "Backing up the world folders: saving, then zipping off-thread...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            WorldBackupService.WorldBackupResult result = service.create(false);
            List<String> lines = new ArrayList<>();

            if (result.success()) {
                lines.add(GREEN + "World backup created: " + WHITE + result.snapshotId());
                lines.add(GRAY + "  Took             : " + WHITE + result.durationMillis() + " ms");

                if (!result.purged().isEmpty()) {
                    lines.add(GRAY + "  Retention removed: " + WHITE + String.join(", ", result.purged()));
                }
            } else {
                lines.add(RED + "World backup failed: " + result.message());
            }

            appendSteps(lines, result.steps());
            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchWorldsList(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        WorldBackupService service = getWorldService();
        if (service == null) { sendNotReady(sender); return; }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<WorldSnapshot> snapshots = service.list();

            if (snapshots.isEmpty()) {
                syncMessage(sender,
                    GRAY + "No world backups found in " + service.getDirectory().getPath(),
                    service.isEnabled()
                        ? GRAY + "Run " + WHITE + "/sf checkpoint worlds create" + GRAY + " to make the first one."
                        : GRAY + "World backups are disabled. Enable world-backup in database.yml.");
                return;
            }

            List<String> lines = new ArrayList<>();
            lines.add(GOLD + "===== World backups (" + snapshots.size()
                + " kept, limit " + service.getKeep() + ") =====");

            for (WorldSnapshot snapshot : snapshots) {
                lines.add(WHITE + snapshot.id() + GRAY + "  " + FMT.format(snapshot.createdAt())
                    + "  " + (snapshot.isRestorable() ? GREEN + "restorable" : RED + "unusable"));
                lines.add(GRAY + "    worlds: " + WHITE + String.join(", ", snapshot.worlds())
                    + GRAY + " (" + snapshot.archive().totalEntries() + " files, " + kilobytes(snapshot.totalBytes()) + ")");
            }

            lines.add(GRAY + "Detail: " + WHITE + "/sf checkpoint worlds info <id>");
            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchWorldsInfo(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_VIEW)) return;

        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf checkpoint worlds info <id>");
            sender.sendMessage(GRAY + "Accepts 3, 003 or world-003.");
            return;
        }

        WorldBackupService service = getWorldService();
        if (service == null) { sendNotReady(sender); return; }

        String reference = args[3];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            WorldSnapshot snapshot = service.find(reference);

            if (snapshot == null) {
                syncMessage(sender, RED + "No world backup matches: " + reference,
                    GRAY + "Run " + WHITE + "/sf checkpoint worlds" + GRAY + " to see the ids.");
                return;
            }

            CheckpointComponent archive = snapshot.archive();
            List<String> lines = new ArrayList<>();

            lines.add(GOLD + "===== " + snapshot.id() + " =====");
            lines.add(GRAY + "Created    : " + WHITE + FMT.format(snapshot.createdAt()));
            lines.add(GRAY + "Slimefun   : " + WHITE + snapshot.slimefunVersion());
            lines.add(GRAY + "Server     : " + WHITE + snapshot.serverVersion());
            lines.add(GRAY + "Restorable : " + yesNo(snapshot.isRestorable()));
            lines.add(GRAY + "Directory  : " + WHITE + snapshot.directory().getPath());
            lines.add(GRAY + "Worlds     : " + WHITE + String.join(", ", snapshot.worlds()));
            lines.add(GRAY + "Archive    : " + WHITE + archive.fileName()
                + GRAY + " (" + archive.format().getDisplayName() + ")");
            lines.add(GRAY + "Files      : " + WHITE + archive.totalEntries());
            lines.add(GRAY + "Size       : " + WHITE + kilobytes(snapshot.totalBytes()));
            lines.add(GRAY + "Checksum   : " + WHITE + shorten(archive.checksum()));
            lines.add(GRAY + "Contents   : " + WHITE + describeCounts(archive.counts()));
            lines.add(GRAY + "World data is a separate pool; it is never part of a checkpoint.");

            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void dispatchWorldsRestore(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;

        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(RED + "/sf checkpoint worlds restore is console-only.");
            sender.sendMessage(GRAY + "It overwrites the live world folders on the next startup,");
            sender.sendMessage(GRAY + "so it must be run from the server console.");
            return;
        }

        if (args.length != 5 || !args[4].equalsIgnoreCase("confirm")) {
            sender.sendMessage(YELLOW + "This stages a full world restore, applied on the next startup.");
            sender.sendMessage(RED + "Confirm with: /sf checkpoint worlds restore "
                + (args.length >= 4 ? args[3] : "<id>") + " confirm");
            return;
        }

        WorldBackupService service = getWorldService();
        if (service == null) { sendNotReady(sender); return; }

        String reference = args[3];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            WorldSnapshot snapshot = service.find(reference);

            if (snapshot == null) {
                syncMessage(sender, RED + "No world backup matches: " + reference,
                    GRAY + "Run " + WHITE + "/sf checkpoint worlds" + GRAY + " to see the ids.");
                return;
            }

            int online = StorageMigrator.onlinePlayers(plugin).getAsInt();

            if (online > 0) {
                syncMessage(sender,
                    RED + "Refusing to stage a world restore while " + online + " player(s) are online.",
                    GRAY + "Empty the server first. Nothing was staged and nothing was changed.");
                return;
            }

            syncMessage(sender, YELLOW + "Staging world restore " + snapshot.id() + ", this may take a while...");

            WorldBackupService.WorldRestoreResult result = service.restore(snapshot);
            List<String> lines = new ArrayList<>();

            if (result.success()) {
                lines.add(GREEN + "World restore staged: " + result.message());
                lines.add(YELLOW + "Restart the server now to apply it — the worlds are replaced before they load.");
            } else {
                lines.add(RED + "World restore failed: " + result.message());
            }

            appendSteps(lines, result.steps());
            syncMessage(sender, lines.toArray(new String[0]));
        });
    }

    private void sendHelp(@Nonnull CommandSender sender) {
        sender.sendMessage(GOLD + "===== /sf checkpoint — Sub-commands =====");
        sender.sendMessage(GRAY + "/sf checkpoint create                  " + WHITE + "[admin] Create a checkpoint now");
        sender.sendMessage(GRAY + "/sf checkpoint list                    " + WHITE + "— Every valid checkpoint");
        sender.sendMessage(GRAY + "/sf checkpoint info <id>               " + WHITE + "— One checkpoint in detail");
        sender.sendMessage(GRAY + "/sf checkpoint status                  " + WHITE + "— Changed since the newest one?");
        sender.sendMessage(GRAY + "/sf checkpoint restore storage <id>    " + WHITE + "[console] Restore storage only");
        sender.sendMessage(GRAY + "/sf checkpoint restore database <id>   " + WHITE + "[console] Restore database only");
        sender.sendMessage(GRAY + "/sf checkpoint restore <id> confirm    " + WHITE + "[console] Restore both halves");
        sender.sendMessage(GRAY + "/sf checkpoint worlds                  " + WHITE + "— List world backups (separate pool)");
        sender.sendMessage(GRAY + "/sf checkpoint worlds create           " + WHITE + "[admin] Back up the world folders now");
        sender.sendMessage(GRAY + "/sf checkpoint worlds info <id>        " + WHITE + "— One world backup in detail");
        sender.sendMessage(GRAY + "/sf checkpoint worlds restore <id> confirm " + WHITE + "[console] Stage a world restore for next startup");
        sender.sendMessage(GRAY + "A checkpoint is a storage backup plus a database backup, kept together.");
        sender.sendMessage(GRAY + "The two halves can be restored independently for precision.");
        sender.sendMessage(GRAY + "Changing provider is a different job: " + WHITE + "/sf storage migrate");
    }

    private void appendHalf(@Nonnull List<String> lines, @Nonnull String title,
                            @Nonnull CheckpointComponent half, @Nonnull String subdirectory) {
        lines.add(GOLD + "--- " + title + " ---");
        lines.add(GRAY + "  Status   : " + statusColour(half.status()) + half.status().getDisplayName()
            + GRAY + (half.detail().isBlank() ? "" : " (" + half.detail() + ")"));

        if (!half.isPresent()) {
            return;
        }

        lines.add(GRAY + "  File     : " + WHITE + subdirectory + "/" + half.fileName());
        lines.add(GRAY + "  Format   : " + WHITE + half.format().getDisplayName());
        lines.add(GRAY + "  Entries  : " + WHITE + half.totalEntries());
        lines.add(GRAY + "  Size     : " + WHITE + kilobytes(half.bytes()));
        lines.add(GRAY + "  Checksum : " + WHITE + shorten(half.checksum()));
        lines.add(GRAY + "  Contents : " + WHITE + describeCounts(half.counts()));
    }

    private void appendSteps(@Nonnull List<String> lines, @Nonnull List<String> steps) {
        int shown = 0;

        for (String step : steps) {
            if (shown++ < STEP_LINE_CAP) {
                lines.add(GRAY + "  - " + step);
            }
        }

        if (shown > STEP_LINE_CAP) {
            lines.add(GRAY + "  - " + (shown - STEP_LINE_CAP) + " more step(s) not shown");
        }
    }

    @Nonnull
    private String describeHalf(@Nonnull CheckpointComponent half) {
        String state = statusColour(half.status()) + half.status().getDisplayName();

        if (!half.isPresent()) {
            return state;
        }

        return state + GRAY + " (" + half.totalEntries() + " entries, " + kilobytes(half.bytes()) + ")";
    }

    @Nonnull
    private String describeSchedule(@Nonnull CheckpointService service) {
        if (!service.isEnabled()) {
            return "disabled";
        }

        if (!service.isAutomatic()) {
            return "manual only";
        }

        return "automatic every " + service.getIntervalHours() + "h"
            + (service.isOnlyWhenChanged() ? ", skipped when nothing changed" : "");
    }

    @Nonnull
    private static ChatColor statusColour(@Nonnull CheckpointStatus status) {
        return switch (status) {
            case OK -> GREEN;
            case SKIPPED -> YELLOW;
            case FAILED -> RED;
        };
    }

    @Nonnull
    private static String yesNo(boolean value) {
        return value ? GREEN + "YES" : GRAY + "NO";
    }

    @Nonnull
    private static String kilobytes(long bytes) {
        if (bytes <= 0L) {
            return "0 KB";
        }

        return Math.max(1L, bytes / 1024L) + " KB";
    }

    @Nonnull
    private static String shorten(@Nonnull String checksum) {
        if (checksum.isBlank()) {
            return "none";
        }

        return checksum.length() <= CHECKSUM_PREVIEW
            ? checksum
            : checksum.substring(0, CHECKSUM_PREVIEW) + "...";
    }

    @Nonnull
    private static String describeCounts(@Nonnull Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return "none recorded";
        }

        StringBuilder builder = new StringBuilder();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }

            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }

        return builder.toString();
    }

    private void syncMessage(@Nonnull CommandSender sender, @Nonnull String... lines) {
        Runnable send = () -> {
            for (String line : lines) {
                sender.sendMessage(line);
            }
        };

        if (plugin.getServer().isPrimaryThread()) {
            send.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, send);
        }
    }

    private boolean checkPerm(@Nonnull CommandSender sender, @Nonnull String perm) {
        if (sender.hasPermission(perm)) {
            return true;
        }

        sender.sendMessage(RED + "You do not have permission to use this command.");
        return false;
    }

    private void sendNotReady(@Nonnull CommandSender sender) {
        sender.sendMessage(RED + "The checkpoint service is not available yet.");
    }

    private StorageEngineService getService() {
        return Slimefun.instance() != null ? Slimefun.instance().getStorageEngineService() : null;
    }

    private WorldBackupService getWorldService() {
        StorageEngineService svc = getService();
        return svc == null ? null : svc.getWorldBackupService();
    }
}
