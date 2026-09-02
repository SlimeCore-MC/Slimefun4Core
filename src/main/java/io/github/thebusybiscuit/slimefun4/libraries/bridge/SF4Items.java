package io.github.thebusybiscuit.slimefun4.libraries.bridge;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.github.bakedlibs.dough.items.CustomItemStack;

/**
 * Bridge for {@link CustomItemStack}.
 * When dough updates CustomItemStack, edit only this class.
 */
public final class SF4Items {

    private SF4Items() {}

    /** Delegates to {@link CustomItemStack#create(ItemStack, Consumer)}. */
    public static @Nonnull ItemStack create(@Nonnull ItemStack item, @Nonnull Consumer<ItemMeta> meta) {
        return CustomItemStack.create(item, meta);
    }

    /** Delegates to {@link CustomItemStack#create(Material, Consumer)}. */
    public static @Nonnull ItemStack create(@Nonnull Material material, @Nonnull Consumer<ItemMeta> meta) {
        return CustomItemStack.create(material, meta);
    }

    /** Delegates to {@link CustomItemStack#create(ItemStack, String, String...)}. */
    public static @Nonnull ItemStack create(@Nonnull ItemStack item, @Nonnull String name, @Nonnull String... lore) {
        return CustomItemStack.create(item, name, lore);
    }

    /** Delegates to {@link CustomItemStack#create(Material, String, String...)}. */
    public static @Nonnull ItemStack create(@Nonnull Material material, @Nonnull String name, @Nonnull String... lore) {
        return CustomItemStack.create(material, name, lore);
    }

    /** Delegates to {@link CustomItemStack#create(Material, String, List)}. */
    public static @Nonnull ItemStack create(@Nonnull Material material, @Nonnull String name, @Nonnull List<String> lore) {
        return CustomItemStack.create(material, name, lore);
    }

    /** Delegates to {@link CustomItemStack#create(ItemStack, List)}. */
    public static @Nonnull ItemStack create(@Nonnull ItemStack item, @Nonnull List<String> lore) {
        return CustomItemStack.create(item, lore);
    }

    /** Delegates to {@link CustomItemStack#create(Material, List)}. */
    public static @Nonnull ItemStack create(@Nonnull Material material, @Nonnull List<String> lore) {
        return CustomItemStack.create(material, lore);
    }

    /** Delegates to {@link CustomItemStack#create(ItemStack, int)}. */
    public static @Nonnull ItemStack create(@Nonnull ItemStack item, int amount) {
        return CustomItemStack.create(item, amount);
    }

    /** Delegates to {@link CustomItemStack#create(ItemStack, Material)}. */
    public static @Nonnull ItemStack create(@Nonnull ItemStack item, @Nonnull Material material) {
        return CustomItemStack.create(item, material);
    }
}
