package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.github.bakedlibs.dough.chat.ChatInput;

/**
 * Bridge for {@link ChatInput}.
 * When dough updates ChatInput, edit only this class.
 */
public final class SF4ChatInput {

    private SF4ChatInput() {}

    /** Delegates to {@link ChatInput#waitForPlayer(Plugin, Player, Consumer)}. */
    public static void waitForPlayer(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull Consumer<String> callback) {
        ChatInput.waitForPlayer(plugin, player, callback);
    }
}
