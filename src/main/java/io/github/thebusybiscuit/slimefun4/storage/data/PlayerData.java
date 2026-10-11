package io.github.thebusybiscuit.slimefun4.storage.data;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.api.gps.Waypoint;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.researches.Research;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * The data which backs {@link io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile}
 *
 * <b>This API is still experimental, it may change without notice.</b>
 */
@Beta
public class PlayerData {

    private final Set<Research> researches = new HashSet<>();
    private final Set<Integer> unknownResearchIds = new HashSet<>();
    private final Map<Integer, PlayerBackpack> backpacks = new HashMap<>();
    private final Set<Waypoint> waypoints = new HashSet<>();
    private boolean dirty = false;
    private boolean researchesDirty = false;
    private boolean backpacksDirty = false;
    private boolean waypointsDirty = false;

    public PlayerData(Set<Research> researches, Map<Integer, PlayerBackpack> backpacks, Set<Waypoint> waypoints) {
        this(researches, Collections.emptySet(), backpacks, waypoints);
    }

    public PlayerData(Set<Research> researches, Set<Integer> unknownResearchIds, Map<Integer, PlayerBackpack> backpacks, Set<Waypoint> waypoints) {
        this.researches.addAll(researches);
        this.unknownResearchIds.addAll(unknownResearchIds);
        this.backpacks.putAll(backpacks);
        this.waypoints.addAll(waypoints);
    }

    /**
     * Returns the researches unlocked by this player.
     *
     * @return An immutable copy of the player's researches
     */
    public synchronized Set<Research> getResearches() {
        return Collections.unmodifiableSet(new HashSet<>(researches));
    }

    /**
     * Returns the numeric research IDs that were present in persistent storage but could not be
     * resolved to a registered {@link Research} at load time (for example because the owning addon
     * had not registered yet). These are preserved verbatim and re-written on save so that a
     * temporarily-unresolved research is never silently discarded.
     *
     * @return An immutable copy of the unresolved research IDs
     */
    public synchronized Set<Integer> getUnknownResearchIds() {
        return Collections.unmodifiableSet(new HashSet<>(unknownResearchIds));
    }

    /**
     * Adds a research to this player.
     *
     * @param research
     *            The research to add
     */
    public synchronized void addResearch(@Nonnull Research research) {
        Validate.notNull(research, "Cannot add a 'null' research!");

        if (researches.add(research)) {
            markResearchesDirty();
        }
    }

    /**
     * Removes a research from this player.
     *
     * @param research
     *            The research to remove
     */
    public synchronized void removeResearch(@Nonnull Research research) {
        Validate.notNull(research, "Cannot remove a 'null' research!");

        if (researches.remove(research)) {
            markResearchesDirty();
        }
    }

    /**
     * Returns all backpacks owned by this player.
     *
     * @return An immutable copy of the player's backpacks
     */
    @Nonnull
    public synchronized Map<Integer, PlayerBackpack> getBackpacks() {
        return Collections.unmodifiableMap(new HashMap<>(backpacks));
    }

    /**
     * Returns the backpack with the given ID.
     *
     * @param id
     *            The backpack ID
     *
     * @return The backpack if present
     */
    @Nonnull
    public synchronized Optional<PlayerBackpack> getBackpack(int id) {
        return Optional.ofNullable(backpacks.get(id));
    }

    /**
     * Adds or replaces a backpack for this player.
     *
     * @param backpack
     *            The backpack to store
     */
    public synchronized void addBackpack(@Nonnull PlayerBackpack backpack) {
        Validate.notNull(backpack, "Cannot add a 'null' backpack!");

        PlayerBackpack previous = backpacks.put(backpack.getId(), backpack);
        if (previous != backpack) {
            markBackpacksDirty();
        }
    }

    /**
     * Removes a backpack from this player.
     *
     * @param backpack
     *            The backpack to remove
     */
    public synchronized void removeBackpack(@Nonnull PlayerBackpack backpack) {
        Validate.notNull(backpack, "Cannot remove a 'null' backpack!");

        if (backpacks.remove(backpack.getId()) != null) {
            markBackpacksDirty();
        }
    }

    /**
     * Returns the saved waypoints for this player.
     *
     * @return An immutable copy of the player's waypoints
     */
    public synchronized Set<Waypoint> getWaypoints() {
        return Collections.unmodifiableSet(new HashSet<>(waypoints));
    }

    /**
     * Adds a waypoint for this player.
     *
     * @param waypoint
     *            The waypoint to add
     */
    public synchronized void addWaypoint(@Nonnull Waypoint waypoint) {
        Validate.notNull(waypoint, "Cannot add a 'null' waypoint!");

        for (Waypoint wp : waypoints) {
            if (wp.getId().equals(waypoint.getId())) {
                throw new IllegalArgumentException("A Waypoint with that id already exists for this Player");
            }
        }

        // Limited to 21 due to limited UI space and no pagination
        if (waypoints.size() >= 21) {
            return; // not sure why this doesn't throw but the one above does...
        }

        if (waypoints.add(waypoint)) {
            markWaypointsDirty();
        }
    }

    /**
     * Removes a waypoint from this player.
     *
     * @param waypoint
     *            The waypoint to remove
     */
    public synchronized void removeWaypoint(@Nonnull Waypoint waypoint) {
        Validate.notNull(waypoint, "Cannot remove a 'null' waypoint!");

        if (waypoints.remove(waypoint)) {
            markWaypointsDirty();
        }
    }

    /**
     * Returns whether any section of this player data has unsaved changes.
     *
     * @return {@code true} if unsaved changes exist
     */
    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * Returns whether the researches section has unsaved changes.
     *
     * @return {@code true} if researches changed
     */
    public synchronized boolean isResearchesDirty() {
        return researchesDirty;
    }

    /**
     * Returns whether the backpacks section has unsaved changes.
     *
     * @return {@code true} if backpacks changed
     */
    public synchronized boolean isBackpacksDirty() {
        return backpacksDirty;
    }

    /**
     * Returns whether the waypoints section has unsaved changes.
     *
     * @return {@code true} if waypoints changed
     */
    public synchronized boolean isWaypointsDirty() {
        return waypointsDirty;
    }

    /**
     * Marks all sections of this player data as clean.
     */
    public synchronized void markClean() {
        dirty = false;
        researchesDirty = false;
        backpacksDirty = false;
        waypointsDirty = false;
    }

    /**
     * Creates a deep copy of this player data while preserving the current dirty state.
     *
     * @return A deep copy of this player data
     */
    @Nonnull
    public synchronized PlayerData snapshot() {
        return snapshot(true);
    }

    /**
     * Creates a deep copy of this player data.
     *
     * @param preserveDirtyState
     *            Whether the returned snapshot should preserve dirty flags
     *
     * @return A deep copy of this player data
     */
    @Nonnull
    public synchronized PlayerData snapshot(boolean preserveDirtyState) {
        Set<Research> copiedResearches = new HashSet<>(researches);
        Set<Integer> copiedUnknownResearchIds = new HashSet<>(unknownResearchIds);
        Map<Integer, PlayerBackpack> copiedBackpacks = new HashMap<>();
        Set<Waypoint> copiedWaypoints = new HashSet<>();

        for (PlayerBackpack backpack : backpacks.values()) {
            copiedBackpacks.put(backpack.getId(), copyBackpack(backpack));
        }

        for (Waypoint waypoint : waypoints) {
            Location location = waypoint.getLocation().clone();
            copiedWaypoints.add(new Waypoint(waypoint.getOwnerId(), waypoint.getId(), location, waypoint.getName()));
        }

        PlayerData copy = new PlayerData(copiedResearches, copiedUnknownResearchIds, copiedBackpacks, copiedWaypoints);
        if (preserveDirtyState) {
            copy.dirty = dirty;
            copy.researchesDirty = researchesDirty;
            copy.backpacksDirty = backpacksDirty;
            copy.waypointsDirty = waypointsDirty;
        } else {
            copy.markClean();
        }
        return copy;
    }

    synchronized void markAllDirty() {
        dirty = true;
        researchesDirty = true;
        backpacksDirty = true;
        waypointsDirty = true;
    }

    private void markResearchesDirty() {
        dirty = true;
        researchesDirty = true;
    }

    public synchronized void markBackpacksDirty() {
        dirty = true;
        backpacksDirty = true;
    }

    private void markWaypointsDirty() {
        dirty = true;
        waypointsDirty = true;
    }

    @Nonnull
    private static PlayerBackpack copyBackpack(@Nonnull PlayerBackpack backpack) {
        HashMap<Integer, ItemStack> contents = new HashMap<>();

        for (int slot = 0; slot < backpack.getSize(); slot++) {
            ItemStack item = backpack.getInventory().getItem(slot);
            contents.put(slot, item == null ? null : item.clone());
        }

        return PlayerBackpack.load(backpack.getOwnerId(), backpack.getId(), backpack.getSize(), contents);
    }
}
