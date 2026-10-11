package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared building blocks for the {@code /sf storage gui} screens.
 *
 * <p>Every screen is a 54-slot chest whose bottom row is a navigation bar. Slot 53 is
 * always populated so that {@link ChestMenu} sizes the inventory to six rows.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class StorageMenuLayout {

    static final String PERM_VIEW = "slimefun.command.storage";
    static final String PERM_MANAGE = "slimefun.admin.storage";

    static final int NAV_ROW_START = 45;
    static final int SLOT_PREVIOUS = 46;
    static final int SLOT_CENTER = 49;
    static final int SLOT_NEXT = 52;
    static final int SLOT_BACK = 53;
    static final int PAGE_SIZE = 45;

    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private StorageMenuLayout() {}

    @Nonnull
    static ChestMenu newMenu(@Nonnull String title) {
        ChestMenu menu = new ChestMenu(title);
        menu.setEmptySlotsClickable(false);
        menu.setPlayerInventoryClickable(false);
        return menu;
    }

    static void paintNavigationRow(@Nonnull ChestMenu menu) {
        for (int slot = NAV_ROW_START; slot < 54; slot++) {
            menu.addItem(slot, ChestMenuUtils.getBackground());
            menu.addMenuClickHandler(slot, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    static void fillBackground(@Nonnull ChestMenu menu) {
        for (int slot = 0; slot < 54; slot++) {
            menu.addItem(slot, ChestMenuUtils.getBackground());
            menu.addMenuClickHandler(slot, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    static void addBackButton(@Nonnull ChestMenu menu, @Nonnull Player player, @Nonnull Runnable target) {
        menu.addItem(SLOT_BACK, ChestMenuUtils.getBackButton(player, "", "&7Return to the previous screen"));
        menu.addMenuClickHandler(SLOT_BACK, (pl, slot, item, action) -> {
            target.run();
            return false;
        });
    }

    static void addCloseButton(@Nonnull ChestMenu menu, int slot) {
        menu.addItem(slot, CustomItemStack.create(Material.BARRIER, "&c> Close"));
        menu.addMenuClickHandler(slot, (pl, s, item, action) -> {
            pl.closeInventory();
            return false;
        });
    }

    static void addButton(
            @Nonnull ChestMenu menu,
            int slot,
            @Nonnull ItemStack item,
            @Nonnull MenuAction handler) {

        menu.addItem(slot, item);
        menu.addMenuClickHandler(slot, (pl, clicked, stack, action) -> {
            handler.onClick(pl, action.isRightClicked(), action.isShiftClicked());
            return false;
        });
    }

    /**
     * Adds a button that re-checks {@link #PERM_MANAGE} at click time.
     *
     * <p>Opening a screen only requires {@link #PERM_VIEW}. Permissions can be revoked
     * while a menu is open, so every destructive action is checked again here rather
     * than trusting the check that was made when the menu was built.
     */
    static void addManagedButton(
            @Nonnull ChestMenu menu,
            int slot,
            @Nonnull ItemStack item,
            @Nonnull MenuAction handler) {

        menu.addItem(slot, item);
        menu.addMenuClickHandler(slot, (pl, clicked, stack, action) -> {
            if (!requireManage(pl)) {
                return false;
            }

            handler.onClick(pl, action.isRightClicked(), action.isShiftClicked());
            return false;
        });
    }

    /**
     * Checks {@link #PERM_MANAGE} and tells the player when they are missing it.
     *
     * @param player The clicking player
     * @return {@code true} when the action may proceed
     */
    static boolean requireManage(@Nonnull Player player) {
        if (player.hasPermission(PERM_MANAGE)) {
            return true;
        }

        player.sendMessage(ChatColor.RED + "You need " + PERM_MANAGE + " to do that.");
        return false;
    }

    static void addInfoItem(
            @Nonnull ChestMenu menu,
            int slot,
            @Nonnull Material material,
            @Nonnull String name,
            @Nonnull List<String> lore) {

        menu.addItem(slot, CustomItemStack.create(material, name, lore));
        menu.addMenuClickHandler(slot, ChestMenuUtils.getEmptyClickHandler());
    }

    @Nonnull
    static List<String> lore(@Nonnull String... lines) {
        return new ArrayList<>(List.of(lines));
    }

    @Nonnull
    static String formatTimestamp(@Nullable Instant instant) {
        return instant == null ? "unknown" : TIMESTAMP.format(instant);
    }

    @Nonnull
    static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }

        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }

        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Nonnull
    static String shorten(@Nonnull String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    static void runAsync(@Nonnull Plugin plugin, @Nonnull Runnable work) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, work);
    }

    static void sync(@Nonnull Runnable work) {
        Slimefun.runSync(work);
    }

    @FunctionalInterface
    interface MenuAction {
        void onClick(@Nonnull Player player, boolean rightClick, boolean shiftClick);
    }
}
