package io.github.thebusybiscuit.slimefun4.implementation.listeners;

import java.util.Iterator;

import javax.annotation.Nonnull;

import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import me.mrCookieSlime.Slimefun.api.BlockStorage;

/**
 * The {@link ExplosionsListener} makes every Slimefun-registered block immune to
 * explosions (TNT, creepers, Wind Charge bursts, etc.) and to Wither block griefing.
 * Slimefun blocks can only ever be removed by a player mining them.
 */
public class ExplosionsListener implements Listener {

    public ExplosionsListener(@Nonnull Slimefun plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        protectBlocks(e.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        protectBlocks(e.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBreak(EntityChangeBlockEvent e) {
        if (e.getEntity().getType() == EntityType.WITHER || e.getEntity().getType() == EntityType.WITHER_SKULL) {
            SlimefunItem item = BlockStorage.check(e.getBlock());

            if (item != null) {
                e.setCancelled(true);
            }
        }
    }

    private void protectBlocks(@Nonnull Iterator<Block> blocks) {
        while (blocks.hasNext()) {
            Block block = blocks.next();
            SlimefunItem item = BlockStorage.check(block);

            if (item != null) {
                blocks.remove();
            }
        }
    }
}