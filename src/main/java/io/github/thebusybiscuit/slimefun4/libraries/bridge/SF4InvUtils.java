package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import io.github.bakedlibs.dough.inventory.InvUtils;

/**
 * Bridge for {@link InvUtils}.
 * When dough updates InvUtils, edit only this class.
 */
public final class SF4InvUtils {

    private SF4InvUtils() {}

    /** Delegates to {@link InvUtils#fits(Inventory, ItemStack, int...)}. */
    public static boolean fits(@Nonnull Inventory inv, @Nonnull ItemStack item, int... slots) {
        return InvUtils.fits(inv, item, slots);
    }

    /** Delegates to {@link InvUtils#fitAll(Inventory, ItemStack[], int...)}. */
    public static boolean fitAll(@Nonnull Inventory inv, @Nonnull ItemStack[] items, int... slots) {
        return InvUtils.fitAll(inv, items, slots);
    }

    /** Delegates to {@link InvUtils#removeItem(Inventory, int, boolean, Predicate)}. */
    public static boolean removeItem(@Nonnull Inventory inv, int amount, boolean replaceConsumables, @Nonnull Predicate<ItemStack> predicate) {
        return InvUtils.removeItem(inv, amount, replaceConsumables, predicate);
    }

    /** Delegates to {@link InvUtils#isItemAllowed(Material, InventoryType)}. */
    public static boolean isItemAllowed(@Nonnull Material material, @Nonnull InventoryType type) {
        return InvUtils.isItemAllowed(material, type);
    }
}
