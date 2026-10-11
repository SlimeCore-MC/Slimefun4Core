package io.github.thebusybiscuit.slimefun4.implementation.items.blocks;

import java.util.concurrent.ThreadLocalRandom;

import javax.annotation.Nonnull;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Furnace;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.VanillaInventoryDropHandler;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.papermc.lib.PaperLib;
import io.papermc.lib.features.blockstatesnapshot.BlockStateSnapshotResult;

import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * The {@link EnhancedFurnace} is an upgraded version of a {@link Furnace}.
 * It has a custom speed, efficiency and also a level of fortune.
 * All of these values are tweaked for every instance of this class.
 * 
 * It uses a {@link BlockTicker} to manipulate the {@link Furnace} into working faster.
 * 
 * @author TheBusyBiscuit
 *
 */
public class EnhancedFurnace extends SimpleSlimefunItem<BlockTicker> {

    private final int speed;
    private final int efficiency;
    private final int fortuneLevel;

    public EnhancedFurnace(ItemGroup itemGroup, int speed, int efficiency, int fortune, SlimefunItemStack item, ItemStack[] recipe) {
        super(itemGroup, item, RecipeType.ENHANCED_CRAFTING_TABLE, recipe);

        this.speed = speed - 1;
        this.efficiency = efficiency - 1;
        this.fortuneLevel = fortune - 1;

        addItemHandler(new VanillaInventoryDropHandler<>(Furnace.class));
    }

    /**
     * This returns the processing speed of this {@link EnhancedFurnace}.
     * 
     * @return The processing speed
     */
    public int getProcessingSpeed() {
        return speed;
    }

    /**
     * This returns the fuel efficiency of this {@link EnhancedFurnace}.
     * The fuel efficiency is a multiplier that is applied to any fuel burnt in this {@link EnhancedFurnace}.
     * 
     * @return The fuel multiplier
     */
    public int getFuelEfficiency() {
        return efficiency;
    }

    public int getRandomOutputAmount() {
        return 1 + fortuneLevel;
    }

    @Override
    public BlockTicker getItemHandler() {
        return new BlockTicker() {

            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                if (b.getType() != Material.FURNACE) {
                    // The Furnace has been destroyed, we can clear the block data
                    BlockStorage.clearBlockInfo(b);
                } else {
                    BlockStateSnapshotResult result = PaperLib.getBlockState(b, false);
                    BlockState state = result.getState();

                    // Check if the BlockState is a Furnace and cooking something
                    if (state instanceof Furnace furnace && furnace.getCookTime() > 0) {
                        setProgress(furnace);
                        state.update(true, false);
                    }
                }
            }

            @Override
            public boolean isSynchronized() {
                // This messes with BlockStates, so it needs to be synchronized
                return true;
            }
        };
    }

    private void setProgress(@Nonnull Furnace furnace) {
        // Update the cooktime
        int tickRate = Math.max(1, io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getTickerTask().getTickRate());
        
        int totalCookTime = furnace.getCookTimeTotal();
        if (totalCookTime <= 0) {
            totalCookTime = 200;
        }

        int cookTime = furnace.getCookTime() + getProcessingSpeed() * tickRate;

        if (cookTime >= totalCookTime) {
            int extraItems = (cookTime / totalCookTime) - 1;
            cookTime = cookTime % totalCookTime;

            if (extraItems > 0) {
                smeltExtraItems(furnace, extraItems);
            }
            
            // To ensure vanilla smelts exactly 1 item on the next tick, we set it to total - 1.
            // If we actually smelted all input items, we just set it to whatever modulo is left.
            if (furnace.getInventory().getSmelting() != null && furnace.getInventory().getSmelting().getAmount() > 0) {
                cookTime = totalCookTime - 1;
            }
        }
        
        furnace.setCookTime((short) Math.min(cookTime, totalCookTime - 1));
    }

    @SuppressWarnings("removal")
    private void smeltExtraItems(@Nonnull Furnace furnace, int amountToSmelt) {
        org.bukkit.inventory.FurnaceInventory inv = furnace.getInventory();
        ItemStack input = inv.getSmelting();
        if (input == null || input.getAmount() == 0) return;

        java.util.Optional<ItemStack> recipeResult = io.github.thebusybiscuit.slimefun4.implementation.Slimefun.getMinecraftRecipeService().getFurnaceOutput(input);
        if (recipeResult.isEmpty()) return;

        for (int i = 0; i < amountToSmelt; i++) {
            if (input.getAmount() <= 0) {
                inv.setSmelting(null);
                break;
            }

            ItemStack outputBase = recipeResult.get().clone();
            org.bukkit.event.inventory.FurnaceSmeltEvent event = new org.bukkit.event.inventory.FurnaceSmeltEvent(furnace.getBlock(), input, outputBase);
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
            
            if (event.isCancelled()) break;

            ItemStack result = event.getResult();
            ItemStack currentOutput = inv.getResult();

            if (currentOutput == null || currentOutput.getType() == Material.AIR) {
                inv.setResult(result);
                input.setAmount(input.getAmount() - 1);
            } else if (currentOutput.isSimilar(result)) {
                int newAmount = currentOutput.getAmount() + result.getAmount();
                if (newAmount <= currentOutput.getMaxStackSize()) {
                    currentOutput.setAmount(newAmount);
                    input.setAmount(input.getAmount() - 1);
                } else {
                    break; // Output slot is full
                }
            } else {
                break; // Output slot has a different item
            }
            
            if (input.getAmount() <= 0) {
                inv.setSmelting(null);
                break;
            }
        }
    }
}
