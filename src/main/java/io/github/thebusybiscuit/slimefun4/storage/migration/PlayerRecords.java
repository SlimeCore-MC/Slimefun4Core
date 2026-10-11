package io.github.thebusybiscuit.slimefun4.storage.migration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything one player owns, held in the most primitive form each backend can express.
 *
 * <p>This model exists because {@code loadPlayerData} is lossy on both backends: an
 * unknown research id, a backpack whose size is not one of the six standard values, an
 * item that will not deserialize and a waypoint in an unloaded world are all dropped
 * silently. Migrating through {@code PlayerData} would inherit every one of those
 * filters, so nothing here is validated, resolved or looked up:
 *
 * <ul>
 *   <li>research ids stay raw integers, never {@code Research} instances</li>
 *   <li>backpack sizes stay raw integers, with no whitelist</li>
 *   <li>items stay raw bytes, never {@code ItemStack} instances</li>
 *   <li>waypoint worlds stay names, never {@code World} or {@code Location} instances</li>
 * </ul>
 *
 * @author SlimefunCore Contributors
 */
public final class PlayerRecords {

    private final UUID uuid;
    private final Set<Integer> researches = new LinkedHashSet<>();
    private final List<BackpackRecord> backpacks = new ArrayList<>();
    private final List<WaypointRecord> waypoints = new ArrayList<>();

    /**
     * @param uuid The player these records belong to
     */
    public PlayerRecords(@Nonnull UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * One backpack and its contents.
     *
     * @param id    The backpack id as stored
     * @param size  The slot count as stored, not validated
     * @param items Slot index to serialized item bytes; a {@code null} value is an empty slot
     */
    public record BackpackRecord(int id, int size, @Nonnull Map<Integer, byte[]> items) {}

    /**
     * One waypoint, with its world held as a name so an unloaded world cannot drop it.
     *
     * @param id    The waypoint id as stored
     * @param name  The display name as stored
     * @param world The world name as stored
     * @param x     Block or exact x coordinate
     * @param y     Block or exact y coordinate
     * @param z     Block or exact z coordinate
     * @param yaw   Yaw in degrees
     * @param pitch Pitch in degrees
     */
    public record WaypointRecord(
        @Nonnull String id,
        @Nullable String name,
        @Nonnull String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {}

    /**
     * @return The owning player
     */
    @Nonnull
    public UUID getUuid() {
        return uuid;
    }

    /**
     * @return The raw research ids, in insertion order
     */
    @Nonnull
    public Set<Integer> getResearches() {
        return researches;
    }

    /**
     * @return The backpacks, in insertion order
     */
    @Nonnull
    public List<BackpackRecord> getBackpacks() {
        return backpacks;
    }

    /**
     * @return The waypoints, in insertion order
     */
    @Nonnull
    public List<WaypointRecord> getWaypoints() {
        return waypoints;
    }

    /**
     * @param researchId A raw research id
     */
    public void addResearch(int researchId) {
        researches.add(researchId);
    }

    /**
     * @param backpack A backpack and its contents
     */
    public void addBackpack(@Nonnull BackpackRecord backpack) {
        backpacks.add(backpack);
    }

    /**
     * @param waypoint A waypoint
     */
    public void addWaypoint(@Nonnull WaypointRecord waypoint) {
        waypoints.add(waypoint);
    }

    /**
     * @return {@code true} if this player owns nothing at all
     */
    public boolean isEmpty() {
        return researches.isEmpty() && backpacks.isEmpty() && waypoints.isEmpty();
    }

    /**
     * @return How many individual rows or entries these records represent
     */
    public int countEntries() {
        int items = 0;

        for (BackpackRecord backpack : backpacks) {
            items += backpack.items().size();
        }

        return researches.size() + backpacks.size() + items + waypoints.size();
    }

    /**
     * Builds a content digest used to verify that a migration moved everything.
     *
     * <p>The digest covers every value that is written to a backend, including item bytes,
     * so a comparison detects a lost slot, a truncated blob and a changed coordinate alike.
     * Ordering is normalized so two backends that return rows in a different order still
     * agree.
     *
     * <p>Two values are normalized because the two backends cannot represent them
     * identically, and a digest that disagreed on them would fail every legacy facing
     * migration. An empty slot is skipped, since an absent key and a stored {@code NULL}
     * blob both mean the same empty slot and the legacy layout can only express the first.
     * A missing waypoint name reads as an empty string, since {@code player_waypoints.name}
     * is declared {@code NOT NULL}. Neither normalization touches the records themselves.
     *
     * @return A stable digest of these records
     */
    @Nonnull
    public String digest() {
        StringBuilder sb = new StringBuilder(uuid.toString());

        researches.stream().sorted().forEach(id -> sb.append("|r:").append(id));

        backpacks.stream()
            .sorted((a, b) -> Integer.compare(a.id(), b.id()))
            .forEach(backpack -> {
                sb.append("|b:").append(backpack.id()).append(':').append(backpack.size());

                Map<Integer, byte[]> sorted = new LinkedHashMap<>();
                backpack.items().keySet().stream().sorted().forEach(slot -> sorted.put(slot, backpack.items().get(slot)));

                sorted.forEach((slot, bytes) -> {
                    if (bytes == null) {
                        return;
                    }

                    sb.append(':').append(slot).append('=');
                    sb.append(bytes.length).append('#').append(checksum(bytes));
                });
            });

        waypoints.stream()
            .sorted((a, b) -> a.id().compareTo(b.id()))
            .forEach(waypoint -> sb.append("|w:").append(waypoint.id())
                .append(':').append(waypoint.name() == null ? "" : waypoint.name())
                .append(':').append(waypoint.world())
                .append(':').append(waypoint.x())
                .append(':').append(waypoint.y())
                .append(':').append(waypoint.z())
                .append(':').append(waypoint.yaw())
                .append(':').append(waypoint.pitch()));

        return sb.toString();
    }

    private static long checksum(@Nonnull byte[] bytes) {
        long hash = 1125899906842597L;

        for (byte b : bytes) {
            hash = 31 * hash + b;
        }

        return hash;
    }
}
