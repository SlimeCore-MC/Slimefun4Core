package io.github.thebusybiscuit.slimefun4.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;

import org.mockbukkit.mockbukkit.MockBukkit;

class TestSlimefunUtils {

    @BeforeAll
    public static void load() {
        MockBukkit.mock();
        MockBukkit.load(Slimefun.class);
    }

    @AfterAll
    public static void unload() {
        MockBukkit.unmock();
    }

    @Test
    void testIsItemSimilar() {
        // Test that generic vanilla items match generic vanilla items
        Assertions.assertTrue(SlimefunUtils.isItemSimilar(new ItemStack(Material.SUGAR), new ItemStack(Material.SUGAR), true));
        
        // Test that SlimefunItems that happen to be based on the same Material
        // (like ALUMINUM_DUST on SUGAR) do NOT match generic vanilla recipe ingredients
        Assertions.assertFalse(SlimefunUtils.isItemSimilar(SlimefunItems.ALUMINUM_DUST.item(), new ItemStack(Material.SUGAR), true));
    }
}
