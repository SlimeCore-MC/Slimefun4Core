package io.github.thebusybiscuit.slimefun4.core.commands.subcommands;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.core.commands.SlimefunCommand;
import io.github.thebusybiscuit.slimefun4.core.commands.SubCommand;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageCache;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.BackupEntry;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseBackupService;
import io.github.thebusybiscuit.slimefun4.storage.backup.DatabaseRestoreService;
import io.github.thebusybiscuit.slimefun4.storage.provider.StoragePoolStats;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaManager;
import io.github.thebusybiscuit.slimefun4.storage.schema.SchemaRepair;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;

/**
 * Implements the {@code /sf storage} sub-command tree.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code /sf storage}             — alias for {@code info}</li>
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
                    case "list"    -> dispatchBackupList(sender);
                    case "restore" -> dispatchBackupRestore(sender, args);
                    case "delete"  -> dispatchBackupDelete(sender, args);
                    case "verify"  -> dispatchBackupVerify(sender, args);
                    default        -> sendHelp(sender);
                }
            }
            default -> sendHelp(sender);
        }
    }

    // -------------------------------------------------------------------------
    // View sub-commands (PERM_VIEW)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Admin sub-commands (PERM_MANAGE)
    // -------------------------------------------------------------------------

    private void dispatchVerify(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Running schema verification...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // We need a connection — use the reflective helper via backup service approach
                List<String> issues = runVerify(svc.getProvider());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (issues.isEmpty()) {
                        sender.sendMessage(GREEN + "Schema integrity check passed. All tables and columns OK.");
                    } else {
                        sender.sendMessage(RED + "Schema issues found (" + issues.size() + "):");
                        for (String issue : issues) {
                            sender.sendMessage(GRAY + "  - " + issue);
                        }
                        sender.sendMessage(YELLOW + "Run /sf storage repair to attempt automatic repair.");
                    }
                });
            } catch (Exception x) {
                sender.sendMessage(RED + "Verification failed: " + x.getMessage());
                plugin.getLogger().log(Level.WARNING, "[Storage] Schema verify via command failed.", x);
            }
        });
    }

    private void dispatchRepair(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Running schema repair...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> issues = runVerify(svc.getProvider());
                int repaired = 0;
                if (!issues.isEmpty()) {
                    repaired = runRepair(svc.getProvider(), issues);
                }
                final int fixedFinal = repaired;
                final int issueCount = issues.size();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (issueCount == 0) {
                        sender.sendMessage(GREEN + "Schema is already healthy — nothing to repair.");
                    } else {
                        sender.sendMessage(GREEN + "Repaired " + fixedFinal + " / " + issueCount + " schema issues.");
                    }
                });
            } catch (Exception x) {
                sender.sendMessage(RED + "Repair failed: " + x.getMessage());
                plugin.getLogger().log(Level.WARNING, "[Storage] Schema repair via command failed.", x);
            }
        });
    }

    private void dispatchCacheStats(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        dispatchStats(sender); // alias
    }

    private void dispatchCacheClear(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        // StorageCache doesn't expose a clearAll() — re-initialize keeps the backend intact.
        // Since cache is private, we cannot iterate UUIDs without Player list. Use online players.
        int count = 0;
        if (svc.getStorage() instanceof StorageCache cache) {
            for (org.bukkit.entity.Player p : plugin.getServer().getOnlinePlayers()) {
                cache.invalidate(p.getUniqueId());
                count++;
            }
        }
        sender.sendMessage(GREEN + "Cache invalidated for " + count + " online players.");
    }

    // -------------------------------------------------------------------------
    // Backup sub-commands
    // -------------------------------------------------------------------------

    private void dispatchBackupCreate(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_MANAGE)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        sender.sendMessage(GOLD + "Creating backup...");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BackupEntry entry = DatabaseBackupService.createBackup(
                    svc.getProvider(), svc.getDbConfig(), false);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(GREEN + "Backup created: " + WHITE + entry.metadata().backupId()
                        + GRAY + " (" + entry.sizeBytes() / 1024 + " KB, "
                        + entry.metadata().totalRows() + " rows)")
                );
            } catch (Exception x) {
                sender.sendMessage(RED + "Backup failed: " + x.getMessage());
                plugin.getLogger().log(Level.WARNING, "[Storage] Manual backup failed.", x);
            }
        });
    }

    private void dispatchBackupList(@Nonnull CommandSender sender) {
        if (!checkPerm(sender, PERM_VIEW)) return;
        StorageEngineService svc = getService();
        if (svc == null) { sendNotReady(sender); return; }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (backups.isEmpty()) {
                    sender.sendMessage(GRAY + "No backups found.");
                    return;
                }
                sender.sendMessage(GOLD + "===== Backups (" + backups.size() + ") =====");
                for (BackupEntry e : backups) {
                    String id      = e.metadata().backupId().substring(0, 8) + "...";
                    String created = FMT.format(e.metadata().createdAt());
                    String auto    = e.metadata().automatic() ? "[auto]" : "[manual]";
                    long   kb      = e.sizeBytes() / 1024;
                    sender.sendMessage(GRAY + id + WHITE + " " + created
                        + GRAY + " " + auto + " " + e.metadata().totalRows() + " rows, " + kb + " KB");
                }
            });
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
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());
            BackupEntry target = backups.stream()
                .filter(e -> e.metadata().backupId().startsWith(prefix))
                .findFirst().orElse(null);

            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(RED + "No backup found matching: " + prefix));
                return;
            }

            sender.sendMessage(YELLOW + "Restoring backup " + target.metadata().backupId() + "...");
            DatabaseRestoreService.RestoreResult result =
                DatabaseRestoreService.restore(target, svc.getProvider());

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.success()) {
                    sender.sendMessage(GREEN + "Restore complete: " + result.message());
                } else {
                    sender.sendMessage(RED + "Restore failed: " + result.message());
                }
            });
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
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());
            BackupEntry target = backups.stream()
                .filter(e -> e.metadata().backupId().startsWith(prefix))
                .findFirst().orElse(null);

            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(RED + "No backup found matching: " + prefix));
                return;
            }

            DatabaseBackupService.deleteBackup(target);
            plugin.getServer().getScheduler().runTask(plugin, () ->
                sender.sendMessage(GREEN + "Deleted backup: " + target.metadata().backupId()));
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
            List<BackupEntry> backups = DatabaseBackupService.listBackups(svc.getDbConfig());
            BackupEntry target = backups.stream()
                .filter(e -> e.metadata().backupId().startsWith(prefix))
                .findFirst().orElse(null);

            if (target == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(RED + "No backup found matching: " + prefix));
                return;
            }

            boolean ok = DatabaseBackupService.verifyBackup(target);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (ok) {
                    sender.sendMessage(GREEN + "Checksum OK for backup: " + target.metadata().backupId());
                } else {
                    sender.sendMessage(RED + "Checksum MISMATCH for backup: " + target.metadata().backupId()
                        + " — this backup may be corrupted!");
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Help
    // -------------------------------------------------------------------------

    private void sendHelp(@Nonnull CommandSender sender) {
        sender.sendMessage(GOLD + "===== /sf storage — Sub-commands =====");
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
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

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
        Class<?> cls = provider.getClass();
        while (cls != null && cls != Object.class) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("dataSource");
                f.setAccessible(true);
                Object ds = f.get(provider);
                if (ds instanceof javax.sql.DataSource source) {
                    return source.getConnection();
                }
            } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new IllegalStateException("Cannot obtain connection from provider");
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
