package io.github.thebusybiscuit.slimefun4.core.report;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Collects server-side diagnostics for report tickets.
 * All values are pulled automatically — the player is never asked for these.
 */
public final class DiagnosticsCollector {

    private DiagnosticsCollector() {}

    /**
     * Collects a snapshot of all relevant server diagnostics.
     *
     * @param plugin The Slimefun instance
     * @return A populated {@link ReportDiagnostics} record
     */
    public static ReportDiagnostics collect(Slimefun plugin) {
        String serverVersion = Bukkit.getVersion() + " (" + Bukkit.getBukkitVersion() + ")";
        String sfBuild = plugin.getDescription().getVersion();
        String branch = Slimefun.getUpdater().getBranch().name();

        List<String> addons = new ArrayList<>();
        try {
            for (var status : Slimefun.getAddonManager().listStatuses()) {
                String version = status.cachedVersion() != null ? status.cachedVersion() : "unknown";
                addons.add(status.definition().name() + " v" + version);
            }
        } catch (Exception ignored) {
            // AddonManager may not be initialized yet
        }

        long usedRam = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long maxRam = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        int online = Bukkit.getOnlinePlayers().size();
        String javaVersion = System.getProperty("java.version");

        return new ReportDiagnostics(serverVersion, sfBuild, branch, addons, usedRam, maxRam, online, javaVersion);
    }
}
