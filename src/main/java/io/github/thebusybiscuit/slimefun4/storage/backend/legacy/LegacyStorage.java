package io.github.thebusybiscuit.slimefun4.storage.backend.legacy;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.api.gps.Waypoint;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.researches.Research;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.Storage;
import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;
import io.github.thebusybiscuit.slimefun4.storage.util.AtomicFiles;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

@Beta
public class LegacyStorage implements Storage {

    private static final Set<Integer> VALID_BACKPACK_SIZES = Set.of(9, 18, 27, 36, 45, 54);

    @Override
    public PlayerData loadPlayerData(@Nonnull UUID uuid) {
        long start = System.nanoTime();

        Config playerFile = new Config("data-storage/Slimefun/Players/" + uuid + ".yml");
        // Not too sure why this is its own file
        Config waypointsFile = new Config("data-storage/Slimefun/waypoints/" + uuid + ".yml");

        // Load research
        Set<Research> researches = new HashSet<>();
        Set<Integer> unknownResearchIds = new HashSet<>();

        Map<Integer, List<Research>> researchesById = new HashMap<>();
        for (Research research : Slimefun.getRegistry().getResearches()) {
            researchesById.computeIfAbsent(research.getID(), ignored -> new ArrayList<>()).add(research);
        }

        if (playerFile.contains("researches")) {
            for (String key : playerFile.getKeys("researches")) {
                try {
                    int researchId = Integer.parseInt(key);
                    List<Research> matchedResearches = researchesById.get(researchId);

                    if (matchedResearches == null || matchedResearches.isEmpty()) {
                        /*
                         * The owning addon may not have registered its researches yet (late/async
                         * addon load). Preserve the raw ID so it is re-written on save instead of
                         * being silently discarded, and so it resolves on a later restart.
                         */
                        unknownResearchIds.add(researchId);
                        Slimefun.logger().log(Level.WARNING, "Preserving unresolved Research ID {0} for Player \"{1}\" (owning addon may not be loaded yet)", new Object[] { researchId, uuid });
                        continue;
                    }

                    researches.addAll(matchedResearches);
                } catch (NumberFormatException x) {
                    Slimefun.logger().log(Level.WARNING, x, () -> "Skipping malformed Research key \"" + key + "\" for Player \"" + uuid + '"');
                }
            }
        }

        // Load backpacks
        HashMap<Integer, PlayerBackpack> backpacks = new HashMap<>();
        if (playerFile.contains("backpacks")) {
            for (String key : playerFile.getKeys("backpacks")) {
                try {
                    int id = Integer.parseInt(key);
                    int size = playerFile.getInt("backpacks." + key + ".size");

                    if (!VALID_BACKPACK_SIZES.contains(size)) {
                        Slimefun.logger().log(Level.WARNING, "Skipping Backpack \"{0}\" for Player \"{1}\" because size {2} is invalid", new Object[] { key, uuid, size });
                        continue;
                    }

                    HashMap<Integer, ItemStack> items = new HashMap<>();
                    for (int i = 0; i < size; i++) {
                        final int slot = i;
                        try {
                            ItemStack item = playerFile.getItem("backpacks." + key + ".contents." + slot);
                            if (item != null) {
                                items.put(slot, item);
                            }
                        } catch (Exception itemException) {
                            Slimefun.logger().log(Level.WARNING, itemException, () -> "Skipping Backpack slot " + slot + " for Backpack \"" + key + "\" of Player \"" + uuid + '"');
                        }
                    }

                    PlayerBackpack backpack = PlayerBackpack.load(uuid, id, size, items);

                    backpacks.put(id, backpack);
                } catch (Exception x) {
                    Slimefun.logger().log(Level.WARNING, x, () -> "Could not load Backpack \"" + key + "\" for Player \"" + uuid + '"');
                }
            }
        }

        // Load waypoints
        Set<Waypoint> waypoints = new HashSet<>();
        for (String key : waypointsFile.getKeys()) {
            try {
                if (waypointsFile.contains(key + ".world") && Bukkit.getWorld(waypointsFile.getString(key + ".world")) != null) {
                    String waypointName = waypointsFile.getString(key + ".name");
                    Location loc = waypointsFile.getLocation(key);
                    waypoints.add(new Waypoint(uuid, key, loc, waypointName));
                }
            } catch (Exception x) {
                Slimefun.logger().log(Level.WARNING, x, () -> "Could not load Waypoint \"" + key + "\" for Player \"" + uuid + '"');
            }
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime("legacy", true, end - start);

        return new PlayerData(researches, unknownResearchIds, backpacks, waypoints);
    }

    // The current design of saving all at once isn't great, this will be refined.
    @Override
    public void savePlayerData(@Nonnull UUID uuid, @Nonnull PlayerData data) {
        long start = System.nanoTime();

        if (!data.isDirty()) {
            return;
        }

        boolean playerSectionsDirty = data.isResearchesDirty() || data.isBackpacksDirty();
        boolean waypointsDirty = data.isWaypointsDirty();
        Config playerFile = playerSectionsDirty ? new Config("data-storage/Slimefun/Players/" + uuid + ".yml") : null;
        // Not too sure why this is its own file
        Config waypointsFile = waypointsDirty ? new Config("data-storage/Slimefun/waypoints/" + uuid + ".yml") : null;

        if (data.isResearchesDirty()) {
            if (playerFile == null) {
                throw new IllegalStateException("Player file must be available when researches are dirty");
            }

            playerFile.setValue("researches", null);

            Set<Integer> savedResearchIds = new HashSet<>();
            for (Research research : data.getResearches()) {
                if (savedResearchIds.add(research.getID())) {
                    playerFile.setValue("researches." + research.getID(), true);
                }
            }

            // Re-write research IDs that could not be resolved at load time so an addon
            // that was temporarily missing does not cause permanent research loss.
            for (int unknownId : data.getUnknownResearchIds()) {
                if (savedResearchIds.add(unknownId)) {
                    playerFile.setValue("researches." + unknownId, true);
                }
            }
        }

        if (data.isBackpacksDirty()) {
            if (playerFile == null) {
                throw new IllegalStateException("Player file must be available when backpacks are dirty");
            }

            playerFile.setValue("backpacks", null);

            for (PlayerBackpack backpack : data.getBackpacks().values()) {
                playerFile.setValue("backpacks." + backpack.getId() + ".size", backpack.getSize());

                for (int i = 0; i < backpack.getSize(); i++) {
                    ItemStack item = backpack.getInventory().getItem(i);
                    if (item != null) {
                        playerFile.setValue("backpacks." + backpack.getId() + ".contents." + i, item);
                    }
                }
            }
        }

        if (waypointsDirty) {
            if (waypointsFile == null) {
                throw new IllegalStateException("Waypoints file must be available when waypoints are dirty");
            }

            waypointsFile.clear();
            for (Waypoint waypoint : data.getWaypoints()) {
                // Legacy data uses IDs
                waypointsFile.setValue(waypoint.getId(), waypoint.getLocation());
                waypointsFile.setValue(waypoint.getId() + ".name", waypoint.getName());
            }
        }

        boolean playerSaved = !playerSectionsDirty || atomicSave(playerFile);
        boolean waypointsSaved = !waypointsDirty || atomicSave(waypointsFile);

        if (playerSaved && waypointsSaved) {
            data.markClean();
        }

        long end = System.nanoTime();
        Slimefun.getAnalyticsService().recordPlayerProfileDataTime("legacy", false, end - start);
    }

    private boolean atomicSave(@Nonnull Config config) {
        File targetFile = config.getFile();

        try {
            AtomicFiles.saveConfig(config);
            return true;
        } catch (Exception x) {
            Slimefun.logger().log(Level.SEVERE, x, () -> "Could not atomically save player storage file \"" + targetFile.getName() + '"');
            return false;
        }
    }
}
