package io.github.thebusybiscuit.slimefun4.core.monitor;

public sealed interface MonitorEvent {
    record CoreUpdateAvailable(String currentBuild, String latestBuild, String changelogUrl) implements MonitorEvent {}
    record AddonNewlyDiscovered(String addonName, String version, String compatibleMcVersion) implements MonitorEvent {}
    record AddonUpdateAvailable(String addonName, String oldVersion, String newVersion, String buildNumber) implements MonitorEvent {}
    record AddonManuallyDropped(String fileName, boolean recognized) implements MonitorEvent {}
    record ReportSubmitted(io.github.thebusybiscuit.slimefun4.core.report.ReportTicket ticket) implements MonitorEvent {}
}
