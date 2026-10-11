package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage.StorageGuiMenu;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;
import io.github.thebusybiscuit.slimefun4.storage.backup.BlockStorageBackup;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseRestoreService;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationException;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationReport;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationTargets;
import io.github.thebusybiscuit.slimefun4.storage.migration.StorageMigrator;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaRepair;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Implements the {@code /sf storage} sub-command tree.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code /sf storage}             — alias for {@code info}</li>
 *   <li>{@code /sf storage gui}         — open the 54-slot storage control panel</li>
 *   <li>{@code /sf storage info}        — provider name, status, ping, pool</li>
 *   <li>{@code /sf storage stats}       — cache hit rate, queue depth</li>
 *   <li>{@code /sf storage status}      — one-line health summary</li>
 *   <li>{@code /sf storage provider}    — current provider type</li>
 *   <li>{@code /sf storage verify}      — run schema verify, list issues</li>
 *   <li>{@code /sf storage repair}      — run SchemaRepair, report fixes</li>
 *   <li>{@code /sf storage cache}       — show cache stats</li>
 *   <li>{@code /sf storage cache clear} — invalidate entire cache</li>
 *   <li>{@code /sf storage backup create}           — create a backup</li>
 *   <li>{@code /sf storage backup list}             — list backups</li>
 *   <li>{@code /sf storage backup restore &lt;id&gt;} — restore a backup</li>
 *   <li>{@code /sf storage backup delete &lt;id&gt;}  — delete a backup</li>
 *   <li>{@code /sf storage backup verify &lt;id&gt;}  — verify a backup's checksum</li>
 *   <li>{@code /sf storage backup blocks}           — archive the block data tree</li>
 *   <li>{@code /sf storage migrate targets}         — list every migration target</li>
 *   <li>{@code /sf storage migrate check &lt;target&gt;} — dry run, writes nothing</li>
 *   <li>{@code /sf storage migrate start &lt;target&gt; confirm [overwrite]} — copy everything</li>
 *   <li>{@code /sf storage migrate status}          — the last migration report</li>
 *   <li>{@code /sf storage migrate apply &lt;target&gt;} — activate a verified destination</li>
 * </ul>
 *
 * <p>All database operations run asynchronously via Bukkit's scheduler.
 *
 * @author SlimefunCore Contributors
 */
@Beta
class StorageCommand extends SubCommand {

    private static final ChatColor GOLD   = ChatColor.GOLD;
    private static final ChatColor GREEN  = ChatColor.GREEN;
    private static final ChatColor RED    = ChatColor.RED;
    private static final ChatColor GRAY   = ChatColor.GRAY;
    private static final ChatColor YELLOW = ChatColor.YELLOW;
    private static final ChatColor WHITE  = ChatColor.WHITE;

    private static final String PERM_VIEW   = "slimefun.command.storage";
    private static final String PERM_MANAGE = "slimefun.admin.storage";

    private static final int REPORT_LINE_CAP = 10;

    private static final DateTimeFormatter FMT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    @ParametersAreNonnullByDefault
    StorageCommand(Slimefun plugin, SlimefunCommand cmd) {
        super(plugin, cmd, "storage", false);
    }

    @Override
    public void onExecute(@Nonnull CommandSender sender, @Nonnull String[] args) {
        // args[0] is "storage"; args[1+] are sub-sub-commands
        String sub = args.length >= 2 ? args[1].toLowerCase() : "info";

        switch (sub) {
            case "info"     -> dispatchInfo(sender);
            case "gui"      -> dispatchGui(sender);
            case "stats"    -> dispatchStats(sender);
            case "status"   -> dispatchStatus(sender);
            case "provider" -> dispatchProvider(sender);
            case "verify"   -> dispatchVerify(sender);
            case "repair"   -> dispatchRepair(sender);
            case "cache"    -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
                    dispatchCacheClear(sender);
                } else {
                    dispatchCacheStats(sender);
                }
            }
            case "backup"   -> {
                String bsub = args.length >= 3 ? args[2].toLowerCase() : "list";
                switch (bsub) {
                    case "create"  -> dispatchBackupCreate(sender);
                    case "blocks"  -> dispatchBackupBlocks(sender);
                    case "list"    -> dispatchBackupList(sender);
                    case "restore" -> dispatchBackupRestore(sender, args);
                    case "delete"  -> dispatchBackupDelete(sender, args);
                    case "verify"  -> dispatchBackupVerify(sender, args);
                    default        -> sendHelp(sender);
                }
            }
            case "migrate"  -> {
                String msub = args.length >= 3 ? args[2].toLowerCase() : "";
                switch (msub) {
                    case "targets" -> dispatchMigrateTargets(sender);
                    case "check"   -> dispatchMigrateCheck(sender, args);
                    case "start"   -> dispatchMigrateStart(sender, args);
                    case "status"  -> dispatchMigrateStatus(sender);
                    case "apply"   -> dispatchMigrateApply(sender, args);
                    default        -> sendMigrateUsage(sender);
                }
            }
            default -> sendHelp(sender);
        }
    }
    // View sub-commands (PERM_VIEW)
    private void dispatchInfo(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        StorageProvider prov = svc.getProvider();
        StoragePoolStats pool = prov.getPoolStats();
        long ping = prov.pingMillis();

        sender.sendMessage(GOLD + "===== Slimefun Storage Engine =====");
        sender.sendMessage(GRAY + "Provider  : " + WHITE + prov.getProviderName());
        sender.sendMessage(GRAY + "Type      : " + WHITE + prov.getProviderType());
        sender.sendMessage(GRAY + "Status    : " + (prov.isAvailable() ? GREEN + "Available" : RED + "Unavailable"));
        sender.sendMessage(GRAY + "Ping      : " + WHITE + (ping < 0 ? "N/A" : ping + " ms"));
        sender.sendMessage(GRAY + "Pool      : " + WHITE + pool.active() + " active / " + pool.max() + " max");
        sender.sendMessage(GRAY + "Schema    : " + WHITE + prov.getSchemaVersion());
    }

    private void dispatchGui(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(RED + "/sf storage gui is an in-game menu — run it as a player.");
            sender.sendMessage(GRAY + "From the console, use /sf storage info, backup list, backup create.");
            return;
        }

        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        StorageGuiMenu.open(plugin, player);
    }

    private void dispatchStats(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "===== Storage Cache Stats =====");
        StorageCache.CacheStats cs = svc.getCacheStats();
        if (cs == null) {
            sender.sendMessage(GRAY + "Cache: Not active (unbounded or disabled)");
        } else {
            sender.sendMessage(GRAY + "Entries   : " + WHITE + cs.size() + " / " + cs.maxSize());
            sender.sendMessage(GRAY + "Hits      : " + WHITE + cs.hits());
            sender.sendMessage(GRAY + "Misses    : " + WHITE + cs.misses());
            sender.sendMessage(GRAY + "Evictions : " + WHITE + cs.evictions());
            sender.sendMessage(GRAY + "Hit Rate  : " + WHITE + String.format("%.1f%%", cs.hitRate() * 100));
        }
    }

    private void dispatchStatus(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        String status = svc.getStatus();
        ChatColor col = status.equals("Connected") ? GREEN : (status.equals("Legacy YAML") ? YELLOW : RED);
        sender.sendMessage(GRAY + "Storage: " + col + status
            + GRAY + " via " + WHITE + svc.getProvider().getProviderName());
    }

    private void dispatchProvider(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GRAY + "Provider: " + WHITE + svc.getProvider().getProviderName()
            + GRAY + " (" + svc.getProvider().getProviderType() + ")");
    }
    // Admin sub-commands (PERM_MANAGE)
    private void dispatchVerify(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Running schema verification...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> issues = svc.verifySchemaNow();
            StorageEngineService.IntegrityStatus status = svc.getIntegrityStatus();

            if (status == StorageEngineService.IntegrityStatus.NOT_APPLICABLE) {
                syncMessage(sender, YELLOW + "Provider " + svc.getProvider().getProviderName()
                    + " has no SQL schema to verify.");
                return;
            }

            if (status == StorageEngineService.IntegrityStatus.FAILED) {
                syncMessage(sender, RED + "Verification could not run: " + svc.getIntegrityDescription());
                return;
            }

            if (issues.isEmpty()) {
                syncMessage(sender, GREEN + "Schema integrity check passed. "
                    + svc.getVerifiedTableCount() + " tables and all columns OK.");
                return;
            }

            String[] lines = new String[issues.size() + 2];
            lines[0] = RED + "Schema issues found (" + issues.size() + "):";
            for (int i = 0; i < issues.size(); i++) {
                lines[i + 1] = GRAY + "  - " + issues.get(i);
            }
            lines[lines.length - 1] = YELLOW + "Run /sf storage repair to attempt automatic repair.";
            syncMessage(sender, lines);
        });
    }

    private void dispatchRepair(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Running schema repair...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!svc.getProvider().supportsSql()) {
                syncMessage(sender, YELLOW + "Provider " + svc.getProvider().getProviderName()
                    + " has no SQL schema to repair.");
                return;
            }

            try {
                List<String> issues = runVerify(svc.getProvider());
                int repaired = issues.isEmpty() ? 0 : runRepair(svc.getProvider(), issues);

                if (issues.isEmpty()) {
                    syncMessage(sender, GREEN + "Schema is already healthy — nothing to repair.");
                } else {
                    syncMessage(sender,
                        GREEN + "Repaired " + repaired + " / " + issues.size() + " schema issues.",
                        GRAY + "Run /sf storage verify to confirm.");
                }
            } catch (Exception x) {
                syncMessage(sender, RED + "Repair failed: " + describe(x));
                plugin.getLogger().log(Level.WARNING, "[Storage] Schema repair via command failed.", x);
            }
        });
    }

    private void dispatchCacheStats(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        dispatchStats(sender);
    }

    private void dispatchCacheClear(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        if (svc.getStorage() instanceof StorageCache cache) {
            int dropped = cache.invalidateAll();
            sender.sendMessage(GREEN + "Cache cleared: " + WHITE + dropped + GREEN + " entries dropped.");
        } else {
            sender.sendMessage(GRAY + "The active storage is not cache-wrapped — nothing to clear.");
        }
    }
    // Backup sub-commands
    private void dispatchBackupCreate(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Creating backup...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            StorageEngineService.BackupOutcome outcome = svc.performBackup(false);

            if (!outcome.success()) {
                syncMessage(sender,
                    RED + "Backup failed: " + outcome.detail(),
                    GRAY + "Nothing was written and no existing backup was removed.");
                return;
            }

            BackupEntry entry = DatabaseBackupService.listBackups(svc.getDbConfig()).stream()
                .filter(e -> e.metadata().backupId().equals(outcome.detail()))
                .findFirst().orElse(null);

            String size = entry == null ? "?" : String.valueOf(entry.sizeBytes() / 1024);
            syncMessage(sender, GREEN + "Backup created: " + WHITE + outcome.detail()
                + GRAY + " (" + size + " KB, " + outcome.entries() + " entries)");
        });
    }

    private void dispatchBackupList(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());

            if (backups.isEmpty()) {
                syncMessage(sender, GRAY + "No backups found.");
                return;
            }

            String[] lines = new String[backups.size() + 1];
            lines[0] = GOLD + "===== Backups (" + backups.size() + ") =====";

            for (int i = 0; i < backups.size(); i++) {
                BackupEntry e = backups.get(i);
                String id      = e.metadata().backupId().substring(0, 8) + "...";
                String created = FMT.format(e.metadata().createdAt());
                String auto    = e.metadata().automatic() ? "[auto]" : "[manual]";
                long   kb      = e.sizeBytes() / 1024;
                ChatColor health = e.metadata().isEmpty() ? RED : GRAY;
                String state   = e.metadata().isEmpty() ? " EMPTY" : "";

                lines[i + 1] = health + id + WHITE + " " + created
                    + GRAY + " " + auto + " " + e.metadata().format().getDisplayName()
                    + ", " + e.metadata().totalRows() + " entries, " + kb + " KB" + RED + state;
            }

            syncMessage(sender, lines);
        });
    }

    private void dispatchBackupRestore(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage backup restore <id-prefix>");
            return;
        }
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        String prefix = args[3];
        sender.sendMessage(GOLD + "Looking up backup " + prefix + "...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BackupEntry target = findBackup(svc, prefix);

            if (target == null) {
                syncMessage(sender, RED + "No backup found matching: " + prefix);
                return;
            }

            syncMessage(sender, YELLOW + "Restoring backup " + target.metadata().backupId() + "...");
            DatabaseRestoreService.RestoreResult result =
                DatabaseRestoreService.restore(target, svc.getProvider());

            if (result.success()) {
                syncMessage(sender, GREEN + "Restore complete: " + result.message());
            } else {
                syncMessage(sender, RED + "Restore failed: " + result.message());
            }
        });
    }

    private void dispatchBackupDelete(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage backup delete <id-prefix>");
            return;
        }
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        String prefix = args[3];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BackupEntry target = findBackup(svc, prefix);

            if (target == null) {
                syncMessage(sender, RED + "No backup found matching: " + prefix);
                return;
            }

            DatabaseBackupService.deleteBackup(target);
            syncMessage(sender, GREEN + "Deleted backup: " + target.metadata().backupId());
        });
    }

    private void dispatchBackupVerify(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage backup verify <id-prefix>");
            return;
        }
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        String prefix = args[3];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BackupEntry target = findBackup(svc, prefix);

            if (target == null) {
                syncMessage(sender, RED + "No backup found matching: " + prefix);
                return;
            }

            if (target.metadata().isEmpty()) {
                syncMessage(sender, RED + "Backup " + target.metadata().backupId()
                    + " captured no data and cannot be restored.");
                return;
            }

            boolean ok = DatabaseBackupService.verifyBackup(target);

            if (ok) {
                syncMessage(sender, GREEN + "Checksum OK for backup: " + target.metadata().backupId());
            } else {
                syncMessage(sender, RED + "Checksum MISMATCH for backup: " + target.metadata().backupId()
                    + " — this backup may be corrupted!");
            }
        });
    }

    private void dispatchBackupBlocks(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        File root = BlockStorageBackup.defaultRoot();

        if (!BlockStorageBackup.hasData(root)) {
            sender.sendMessage(YELLOW + "No block data was found under " + root.getPath() + ".");
            sender.sendMessage(GRAY + "There is nothing to archive yet.");
            return;
        }

        sender.sendMessage(GOLD + "Flushing and archiving block data...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                StorageMigrator.flushBlockData(plugin);

                BackupEntry entry = DatabaseBackupService.createBackup(
                    svc.getProvider(),
                    DatabaseBackupService.resolveDirectory(svc.getDbConfig()),
                    svc.getDbConfig().getBoolean("backup.compress"),
                    svc.getDbConfig().getInt("backup.keep-last"),
                    false,
                    BackupFormat.BLOCK_FILES);

                syncMessage(sender,
                    GREEN + "Block archive created: " + WHITE + entry.metadata().backupId(),
                    GRAY + "" + entry.metadata().totalRows() + " file(s), " + (entry.sizeBytes() / 1024)
                        + " KB, checksum " + (DatabaseBackupService.verifyBackup(entry)
                        ? GREEN + "OK" : RED + "MISMATCH"));
            } catch (MigrationException | IOException | RuntimeException x) {
                plugin.getLogger().log(Level.SEVERE, "Could not archive block data", x);
                syncMessage(sender, RED + "Block archive failed: " + describe(x));
            }
        });
    }

    private void sendMigrateUsage(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "===== /sf storage migrate =====");
        sender.sendMessage(GRAY + "Current provider : " + WHITE + svc.getProvider().getProviderName());
        sender.sendMessage(GRAY + "A migration copies player data only. Machines, chests and chunk data");
        sender.sendMessage(GRAY + "live in YAML on every provider, so they are archived, never moved.");
        sender.sendMessage(GREEN + "/sf storage migrate targets" + WHITE + " - what you can migrate into");
        sender.sendMessage(GREEN + "/sf storage migrate check <target>" + WHITE + " - dry run, writes nothing");
        sender.sendMessage(GREEN + "/sf storage migrate start <target> confirm [overwrite]");
        sender.sendMessage(GREEN + "/sf storage migrate status" + WHITE + " - the last report");
        sender.sendMessage(GREEN + "/sf storage migrate apply <target>" + WHITE + " - activate it, then restart");
        sender.sendMessage(GRAY + "Targets : " + WHITE + String.join(", ", MigrationTargets.getTokens()));
        sender.sendMessage(GRAY + "A real run needs an empty server. " + WHITE + "check" + GRAY
            + " runs with players online.");
    }

    private void dispatchMigrateTargets(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        StorageProviderType current = svc.getProvider().getProviderType();
        List<String> lines = new ArrayList<>();

        lines.add(GOLD + "===== Migration targets =====");

        for (MigrationTargets.Readiness readiness : MigrationTargets.inspectAll(svc.getDbConfig())) {
            String state = readiness.type() == current
                ? YELLOW + "active"
                : readiness.complete() ? GREEN + "ready" : RED + "incomplete";

            lines.add(WHITE + MigrationTargets.token(readiness.type()) + GRAY + " - " + state
                + GRAY + " (" + readiness.detail() + ")");
            lines.add(GRAY + "    " + readiness.describeCredentials());

            if (!readiness.complete()) {
                lines.add(RED + "    missing: " + String.join(", ", readiness.missingKeys()));
            }
        }

        lines.add(GRAY + "Read from database.yml only, no connection was opened.");
        sender.sendMessage(lines.toArray(new String[0]));
    }

    private void dispatchMigrateCheck(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;

        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage migrate check <target>");
            return;
        }

        runMigration(sender, args[3], true, false);
    }

    private void dispatchMigrateStart(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;

        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage migrate start <target> confirm [overwrite]");
            return;
        }

        String token = args[3].toLowerCase();
        boolean confirmed = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
        boolean overwrite = args.length >= 6 && args[5].equalsIgnoreCase("overwrite");

        if (!confirmed) {
            sender.sendMessage(YELLOW + "This copies every player record into " + token + ".");
            sender.sendMessage(GRAY + "No player may be online, and two verified backups are taken first:");
            sender.sendMessage(GRAY + "your player data and your block data.");
            sender.sendMessage(GRAY + "Nothing is removed from the current backend, it stays as your fallback.");
            sender.sendMessage(GOLD + "Run: /sf storage migrate start " + token + " confirm");
            return;
        }

        runMigration(sender, token, false, overwrite);
    }

    private void dispatchMigrateStatus(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        MigrationReport report = svc.getLastMigrationResult();

        if (report == null) {
            sender.sendMessage(GRAY + "No migration has been attempted since the last restart.");
            sender.sendMessage(GRAY + "Run " + WHITE + "/sf storage migrate targets" + GRAY + " to begin.");
            return;
        }

        sender.sendMessage(reportLines(report));
        sender.sendMessage(GRAY + "Finished at " + WHITE + FMT.format(report.getTimestamp()));
    }

    private void dispatchMigrateApply(@Nonnull CommandSender sender, @Nonnull String[] args) {
        if (!checkPerm(sender, PERM_MANAGE)) return;

        if (args.length < 4) {
            sender.sendMessage(RED + "Usage: /sf storage migrate apply <target>");
            return;
        }

        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        StorageProviderType target = MigrationTargets.parse(args[3]).orElse(null);

        if (target == null) {
            sender.sendMessage(RED + "Unknown target: " + args[3]);
            sender.sendMessage(GRAY + "Targets : " + String.join(", ", MigrationTargets.getTokens()));
            return;
        }

        MigrationReport report = svc.getLastSuccessfulMigration();

        if (report == null || report.getDestination() != target) {
            sender.sendMessage(RED + "There is no verified migration into "
                + MigrationTargets.token(target) + " to activate.");
            sender.sendMessage(GRAY + "Run " + WHITE + "/sf storage migrate start "
                + MigrationTargets.token(target) + " confirm" + GRAY + " first.");
            return;
        }

        MigrationReport latest = svc.getLastMigrationResult();

        if (latest != null && latest != report) {
            sender.sendMessage(YELLOW + "Note: a later attempt (" + latest.getOutcome().getLabel()
                + ") ran after this verified migration.");
            sender.sendMessage(GRAY + "Check " + WHITE + "/sf storage migrate status" + GRAY
                + " if that was not expected.");
        }

        try {
            MigrationTargets.applyProvider(svc.getDbConfig().getFile(), target);
        } catch (IOException | RuntimeException x) {
            plugin.getLogger().log(Level.SEVERE, "Could not rewrite database.yml", x);
            sender.sendMessage(RED + "database.yml could not be updated: " + describe(x));
            sender.sendMessage(GRAY + "Nothing changed. The migrated data is still there.");
            return;
        }

        sender.sendMessage(GREEN + "database.yml now reads " + WHITE + "provider: "
            + MigrationTargets.token(target));
        sender.sendMessage(YELLOW + "Restart the server to start on " + MigrationTargets.token(target) + ".");
        sender.sendMessage(GRAY + "Your previous data is untouched and remains your fallback.");
    }

    private void runMigration(@Nonnull CommandSender sender, @Nonnull String token,
            boolean dryRun, boolean overwrite) {
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        StorageProviderType target = MigrationTargets.parse(token).orElse(null);

        if (target == null) {
            sender.sendMessage(RED + "Unknown target: " + token);
            sender.sendMessage(GRAY + "Targets : " + String.join(", ", MigrationTargets.getTokens()));
            return;
        }

        sender.sendMessage(GOLD + (dryRun ? "Checking a migration into " : "Migrating into ")
            + MigrationTargets.token(target) + "...");
        sender.sendMessage(GRAY + "Every step is logged to the server console.");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            MigrationReport report = StorageMigrator.run(plugin, svc.getProvider(), svc.getDbConfig(),
                new StorageMigrator.Request(target, dryRun, overwrite,
                    StorageMigrator.onlinePlayers(plugin), true));

            svc.setLastMigrationResult(report);
            plugin.getLogger().log(Level.INFO, "[Storage] {0}", report.describe());
            syncMessage(sender, reportLines(report));
        });
    }

    @Nonnull
    private String[] reportLines(@Nonnull MigrationReport report) {
        List<String> lines = new ArrayList<>();
        String head = report.isSuccess() ? GREEN.toString()
            : report.getOutcome() == MigrationReport.Outcome.DRY_RUN ? YELLOW.toString() : RED.toString();

        lines.add(head + "===== Migration " + report.getOutcome().getLabel() + " =====");
        lines.add(GRAY + "Direction : " + WHITE + MigrationTargets.token(report.getSource())
            + " -> " + MigrationTargets.token(report.getDestination())
            + (report.isOverwrite() ? GRAY + " (overwrite)" : ""));
        lines.add(GRAY + "Players   : " + WHITE + report.getPlayers()
            + GRAY + "  entries: " + WHITE + report.getCopiedEntries()
            + GRAY + "  took: " + WHITE + report.getDuration().toMillis() + "ms");
        lines.add(GRAY + "Detail    : " + WHITE + report.getDetail());

        report.getSourceCounts().forEach((section, count) ->
            lines.add(GRAY + "  source " + section + ": " + WHITE + count));
        report.getDestinationCounts().forEach((section, count) ->
            lines.add(GRAY + "  destination " + section + ": " + WHITE + count));

        if (report.getPlayerBackupId() != null) {
            lines.add(GRAY + "Player backup : " + WHITE + report.getPlayerBackupId());
        }

        if (report.getBlockBackupId() != null) {
            lines.add(GRAY + "Block backup  : " + WHITE + report.getBlockBackupId());
        }

        for (String warning : report.getWarnings()) {
            lines.add(YELLOW + "  ! " + warning);
        }

        int shown = 0;

        for (String problem : report.getProblems()) {
            if (shown++ < REPORT_LINE_CAP) {
                lines.add(RED + "  x " + problem);
            }
        }

        for (Map.Entry<UUID, String> mismatch : report.getMismatches().entrySet()) {
            if (shown++ < REPORT_LINE_CAP) {
                lines.add(RED + "  x " + mismatch.getKey() + ": " + mismatch.getValue());
            }
        }

        if (shown > REPORT_LINE_CAP) {
            lines.add(RED + "  x " + (shown - REPORT_LINE_CAP)
                + " more, the full list is in the server log");
        }

        if (report.isSuccess()) {
            lines.add(GREEN + "Run " + WHITE + "/sf storage migrate apply "
                + MigrationTargets.token(report.getDestination())
                + GREEN + " to activate it, then restart the server.");
        }

        return lines.toArray(new String[0]);
    }

    private BackupEntry findBackup(@Nonnull StorageEngineService svc, @Nonnull String prefix) {
        return DatabaseBackupService.listBackups(svc.getDbConfig()).stream()
            .filter(e -> e.metadata().backupId().startsWith(prefix))
            .findFirst().orElse(null);
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
    // Help
    private void sendHelp(@Nonnull CommandSender sender) {
        sender.sendMessage(GOLD + "===== /sf storage — Sub-commands =====");
        sender.sendMessage(GREEN + "/sf storage gui          " + WHITE + "— Open the storage control panel");
        sender.sendMessage(GRAY + "/sf storage info         " + WHITE + "— Provider & pool info");
        sender.sendMessage(GRAY + "/sf storage stats        " + WHITE + "— Cache statistics");
        sender.sendMessage(GRAY + "/sf storage status       " + WHITE + "— One-line health status");
        sender.sendMessage(GRAY + "/sf storage provider     " + WHITE + "— Current provider type");
        sender.sendMessage(GRAY + "/sf storage verify       " + WHITE + "[admin] Schema integrity check");
        sender.sendMessage(GRAY + "/sf storage repair       " + WHITE + "[admin] Repair missing columns");
        sender.sendMessage(GRAY + "/sf storage cache        " + WHITE + "— Cache hit rate & size");
        sender.sendMessage(GRAY + "/sf storage cache clear  " + WHITE + "[admin] Evict all cached players");
        sender.sendMessage(GRAY + "/sf storage backup create             " + WHITE + "[admin] Create backup now");
        sender.sendMessage(GRAY + "/sf storage backup list               " + WHITE + "— List all backups");
        sender.sendMessage(GRAY + "/sf storage backup restore <id>       " + WHITE + "[admin] Restore a backup");
        sender.sendMessage(GRAY + "/sf storage backup delete <id>        " + WHITE + "[admin] Delete a backup");
        sender.sendMessage(GRAY + "/sf storage backup verify <id>        " + WHITE + "— Verify backup checksum");
        sender.sendMessage(GRAY + "/sf storage backup blocks             " + WHITE + "[admin] Archive block data");
        sender.sendMessage(GRAY + "/sf storage migrate                   " + WHITE + "[admin] Migration usage");
        sender.sendMessage(GRAY + "/sf storage migrate targets           " + WHITE + "[admin] List targets");
        sender.sendMessage(GRAY + "/sf storage migrate check <target>    " + WHITE + "[admin] Dry run only");
        sender.sendMessage(GRAY + "/sf storage migrate start <target> confirm");
        sender.sendMessage(GRAY + "                                     " + WHITE + "[admin] Copy every record");
        sender.sendMessage(GRAY + "/sf storage migrate status            " + WHITE + "— Last migration report");
        sender.sendMessage(GRAY + "/sf storage migrate apply <target>    " + WHITE + "[admin] Activate + restart");
    }
    // Utilities
    private java.util.List<String> runVerify(@Nonnull StorageProvider provider) throws Exception {
        try (java.sql.Connection conn = openConnection(provider)) {
            return SchemaManager.verify(conn, provider.getProviderType());
        }
    }

    private int runRepair(@Nonnull StorageProvider provider,
                          @Nonnull java.util.List<String> issues) throws Exception {
        try (java.sql.Connection conn = openConnection(provider)) {
            return SchemaRepair.repair(conn, provider.getProviderType(), issues);
        }
    }

    private java.sql.Connection openConnection(@Nonnull StorageProvider provider) throws Exception {
        return provider.borrowConnection().orElseThrow(() -> new IllegalStateException(
            "Provider \"" + provider.getProviderName() + "\" is not SQL-backed"));
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
        sender.sendMessage(RED + "The storage engine is not yet initialized.");
    }

    private StorageEngineService getService() {
        return Slimefun.instance() != null ? Slimefun.instance().getStorageEngineService() : null;
    }
}
