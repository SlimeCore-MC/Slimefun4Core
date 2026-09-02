package io.github.thebusybiscuit.slimefun4.implementation.items.cargo;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.HologramOwner;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;

import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;

public class CargoManager extends SlimefunItem implements HologramOwner {

    private final CargoGridMonitor monitor = new CargoGridMonitor();

    @ParametersAreNonnullByDefault
    public CargoManager(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);

        addItemHandler(onBreak());
    }

    @Nonnull
    private BlockBreakHandler onBreak() {
        return new SimpleBlockBreakHandler() {

            @Override
            public void onBlockBreak(@Nonnull Block b) {
                removeHologram(b);
                monitor.clear(b);
            }
        };
    }

    @Override
    public void preRegister() {
        Slimefun plugin = Slimefun.instance();
        if (plugin != null) {
            monitor.registerListeners(plugin);
        }

        new BlockMenuPreset("CARGO_MANAGER", "Cargo Grid Monitor") {

            @Override
            public void init() {
                monitor.initPreset(this);
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                monitor.refreshMonitor(menu, b);
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                return p.hasPermission("slimefun.cargo.bypass") || Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), io.github.bakedlibs.dough.protection.Interaction.INTERACT_BLOCK);
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return new int[0];
            }
        };

        addItemHandler(new BlockTicker() {

            @Override
            public void tick(Block b, SlimefunItem item, Config data) {
                CargoNet.getNetworkFromLocationOrCreate(b.getLocation()).tick(b);
            }

            @Override
            public boolean isSynchronized() {
                return false;
            }

        }, new BlockUseHandler() {

            @Override
            public void onRightClick(PlayerRightClickEvent e) {
                e.cancel();

                e.getClickedBlock().ifPresent(b -> {
                    Player p = e.getPlayer();
                    
                    if (p.isSneaking()) {
                        String current = BlockStorage.getLocationInfo(b.getLocation(), "visualizer");
                        
                        if (current != null) {
                            BlockStorage.addBlockInfo(b.getLocation(), "visualizer", null);
                            p.sendMessage(io.github.bakedlibs.dough.common.ChatColors.color("\u0026aCargo Network visualizer is now \u00262\u0026lENABLED"));
                        } else {
                            BlockStorage.addBlockInfo(b.getLocation(), "visualizer", "false");
                            p.sendMessage(io.github.bakedlibs.dough.common.ChatColors.color("\u0026cCargo Network visualizer is now \u00264\u0026lDISABLED"));
                        }
                    } else {
                        BlockMenu menu = BlockStorage.getInventory(b);
                        if (menu != null && menu.canOpen(b, p)) {
                            monitor.open(menu, b, p);
                        }
                    }
                });
            }
        });
    }

}
