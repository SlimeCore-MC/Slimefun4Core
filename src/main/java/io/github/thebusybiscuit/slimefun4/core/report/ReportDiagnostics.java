package io.github.thebusybiscuit.slimefun4.core.report;

import java.util.List;
import javax.annotation.Nonnull;

public record ReportDiagnostics(
    @Nonnull String serverVersion,
    @Nonnull String slimefunBuild,
    @Nonnull String branch,
    @Nonnull List<String> installedAddons,
    long usedRamMb,
    long maxRamMb,
    int onlinePlayers,
    @Nonnull String javaVersion
) {}
