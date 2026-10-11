package io.github.thebusybiscuit.slimefun4.core.monitor.sinks;

import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import javax.annotation.Nonnull;
import java.util.logging.Level;

public class ConsoleLogSink implements NotificationSink {
    private final Slimefun plugin;

    public ConsoleLogSink(Slimefun plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(@Nonnull MonitorEvent event) {
        if (event instanceof MonitorEvent.CoreUpdateAvailable coreUpdate) {
            plugin.getLogger().log(Level.WARNING, "🔥 SlimefunCore update available! Current build: {0} -> Latest build: {1}  Changelog: {2}", 
                new Object[]{coreUpdate.currentBuild(), coreUpdate.latestBuild(), coreUpdate.changelogUrl()});
        } else if (event instanceof MonitorEvent.AddonNewlyDiscovered addonNew) {
            plugin.getLogger().log(Level.INFO, "🧩 New addon discovered: {0} v{1} (compatible: {2})",
                new Object[]{addonNew.addonName(), addonNew.version(), addonNew.compatibleMcVersion()});
        } else if (event instanceof MonitorEvent.AddonUpdateAvailable addonUpdate) {
            plugin.getLogger().log(Level.INFO, "🧩 Addon update available: {0} {1} -> {2} (build #{3})",
                new Object[]{addonUpdate.addonName(), addonUpdate.oldVersion(), addonUpdate.newVersion(), addonUpdate.buildNumber()});
        } else if (event instanceof MonitorEvent.AddonManuallyDropped addonDrop) {
            plugin.getLogger().log(Level.INFO, "📦 New JAR detected in addons folder: {0} (recognized: {1})",
                new Object[]{addonDrop.fileName(), addonDrop.recognized() ? "yes" : "no"});
        } else if (event instanceof MonitorEvent.ReportSubmitted reportSubmitted) {
            var ticket = reportSubmitted.ticket();
            plugin.getLogger().log(Level.INFO, "📋 New report {0} [{1}] from {2}",
                new Object[]{ticket.shortId(), ticket.category().getDisplayName(), ticket.username()});
        }
    }
}
