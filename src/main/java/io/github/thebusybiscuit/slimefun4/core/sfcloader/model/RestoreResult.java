package io.github.thebusybiscuit.slimefun4.core.sfcloader.model;

import java.util.List;
import javax.annotation.Nonnull;

public record RestoreResult(
    boolean success,
    @Nonnull List<String> appliedLive,
    @Nonnull List<String> needsRestart,
    @Nonnull String message
) {}
