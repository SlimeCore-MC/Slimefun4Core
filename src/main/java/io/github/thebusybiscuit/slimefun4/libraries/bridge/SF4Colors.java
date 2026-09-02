package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import javax.annotation.Nonnull;

import org.bukkit.ChatColor;

import io.github.bakedlibs.dough.common.ChatColors;

/**
 * Bridge for {@link ChatColors}.
 * When dough updates ChatColors, edit only this class.
 */
public final class SF4Colors {

    private SF4Colors() {}

    /** Delegates to {@link ChatColors#color(String)}. */
    public static @Nonnull String color(@Nonnull String text) {
        return ChatColors.color(text);
    }

    /** Delegates to {@link ChatColors#alternating(String, ChatColor...)}. */
    public static @Nonnull String alternating(@Nonnull String text, @Nonnull ChatColor... colors) {
        return ChatColors.alternating(text, colors);
    }
}
