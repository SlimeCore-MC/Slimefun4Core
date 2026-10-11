package io.github.thebusybiscuit.slimefun4.core.monitor.sinks;

import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import io.github.thebusybiscuit.slimefun4.core.report.ReportSessionManager;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InGameAnnounceSink implements NotificationSink, Listener {
    private final Slimefun plugin;
    private final Set<MonitorEvent> pendingEvents = ConcurrentHashMap.newKeySet();
    private static final String PERMISSION = "slimefun.monitor.notify";

    public InGameAnnounceSink(Slimefun plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void handle(@Nonnull MonitorEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String message = formatMessage(event);
            if (message == null) return;
            
            boolean delivered = false;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(PERMISSION)) {
                    player.sendMessage(message);
                    delivered = true;
                }
            }
            
            if (!delivered) {
                pendingEvents.add(event);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();

        // Broadcast the community Discord invite on join, and remind players they can file a
        // bug report in-game with /report. Staff/admins (who hold the monitor permission or are
        // server operators) get the line highlighted so they always know where to reach support.
        // Gated behind monitor.invite.broadcast-on-join so operators can turn it off.
        if (plugin.getConfig().getBoolean("monitor.invite.broadcast-on-join")) {
            boolean isStaff = player.isOp() || player.hasPermission(PERMISSION);
            player.sendMessage("");
            player.sendMessage("§b§l  SlimefunCore");
            player.sendMessage("§7  ────────────────────");
            player.sendMessage("§f  💬 Join our Discord: §9§n" + ReportSessionManager.DISCORD_INVITE);
            player.sendMessage("§f  🐛 Found a bug? Report it with §a§o/sf report");
            if (isStaff) {
                player.sendMessage("§8  (staff/admin support & live reports)");
            }
            player.sendMessage("");
        }

        if (player.hasPermission(PERMISSION) && !pendingEvents.isEmpty()) {
            for (MonitorEvent event : pendingEvents) {
                String message = formatMessage(event);
                if (message != null) {
                    player.sendMessage(message);
                }
            }
            pendingEvents.clear();
        }
    }

    private String formatMessage(MonitorEvent event) {
        if (event instanceof MonitorEvent.CoreUpdateAvailable coreUpdate) {
            return "§c🔥 §eSlimefunCore update available! Current build: §a" + coreUpdate.currentBuild() + " §e-> Latest build: §a" + coreUpdate.latestBuild() + "\n§eChangelog: §b" + coreUpdate.changelogUrl();
        } else if (event instanceof MonitorEvent.AddonNewlyDiscovered addonNew) {
            return "§b🧩 §aNew addon discovered: §e" + addonNew.addonName() + " §av" + addonNew.version() + " §7(compatible: " + addonNew.compatibleMcVersion() + ")";
        } else if (event instanceof MonitorEvent.AddonUpdateAvailable addonUpdate) {
            return "§b🧩 §aAddon update available: §e" + addonUpdate.addonName() + " §7" + addonUpdate.oldVersion() + " -> §a" + addonUpdate.newVersion() + " §7(build #" + addonUpdate.buildNumber() + ")";
        } else if (event instanceof MonitorEvent.AddonManuallyDropped addonDrop) {
            return "§6📦 §eNew JAR detected in addons folder: §f" + addonDrop.fileName() + " §7(recognized: " + (addonDrop.recognized() ? "§ayes" : "§cno") + "§7)";
        } else if (event instanceof MonitorEvent.ReportSubmitted reportSubmitted) {
            var ticket = reportSubmitted.ticket();
            return "§d📋 §eNew report §f" + ticket.shortId() + " §7[" + ticket.category().getDisplayName() + "] §efrom §f" + ticket.username();
        }
        return null;
    }
}
