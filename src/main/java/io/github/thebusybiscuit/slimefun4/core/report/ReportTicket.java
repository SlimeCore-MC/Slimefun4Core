package io.github.thebusybiscuit.slimefun4.core.report;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ReportTicket(
    int id,
    @Nonnull ReportCategory category,
    @Nonnull String username,
    @Nonnull UUID uuid,
    @Nullable String discordUsername,
    @Nonnull String description,
    @Nullable String mclogsUrl,
    @Nonnull String role,
    @Nonnull ReportDiagnostics diagnostics,
    @Nonnull Instant timestamp
) {
    public String shortId() {
        return String.format("#%04d", id);
    }
}
