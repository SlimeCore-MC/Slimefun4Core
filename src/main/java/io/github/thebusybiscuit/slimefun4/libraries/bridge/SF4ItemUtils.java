package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.inventory.ItemStack;

import io.github.bakedlibs.dough.items.ItemUtils;

/**
 * Bridge for {@link ItemUtils}.
 * When dough updates ItemUtils, edit only this class.
 */
public final class SF4ItemUtils {

    private SF4ItemUtils() {}

    /** Delegates to {@link ItemUtils#getItemName(ItemStack)}. */
    public static @Nonnull String getItemName(@Nullable ItemStack item) {
        return ItemUtils.getItemName(item);
    }

    /** Delegates to {@link ItemUtils#canStack(ItemStack, ItemStack)}. */
    public static boolean canStack(@Nullable ItemStack a, @Nullable ItemStack b) {
        return ItemUtils.canStack(a, b);
    }

    /** Delegates to {@link ItemUtils#damageItem(ItemStack, boolean)}. */
    public static void damageItem(@Nullable ItemStack item, boolean replaceConsumables) {
        ItemUtils.damageItem(item, replaceConsumables);
    }

    /** Delegates to {@link ItemUtils#damageItem(ItemStack, int, boolean)}. */
    public static void damageItem(@Nullable ItemStack item, int amount, boolean replaceConsumables) {
        ItemUtils.damageItem(item, amount, replaceConsumables);
    }

    /** Delegates to {@link ItemUtils#consumeItem(ItemStack, boolean)}. */
    public static void consumeItem(@Nullable ItemStack item, boolean replaceConsumables) {
        ItemUtils.consumeItem(item, replaceConsumables);
    }

    /** Delegates to {@link ItemUtils#consumeItem(ItemStack, int, boolean)}. */
    public static void consumeItem(@Nullable ItemStack item, int amount, boolean replaceConsumables) {
        ItemUtils.consumeItem(item, amount, replaceConsumables);
    }
}
