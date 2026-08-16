package io.github.thebusybiscuit.slimefun4.core.sfcloader.model;

import javax.annotation.Nonnull;

public record CapturedFile(
    @Nonnull String path,
    long bytes,
    @Nonnull String status
) {}
