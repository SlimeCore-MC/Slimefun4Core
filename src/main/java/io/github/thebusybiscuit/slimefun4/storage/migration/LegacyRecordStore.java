package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.bakedlibs.dough.config.Config;
import io.github.thebusybiscuit.slimefun4.storage.util.AtomicFiles;

import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reads and writes {@link PlayerRecords} over the legacy YAML tree.
 *
 * <p>Backpack contents are read by their stored keys rather than by counting from slot zero
 * up to the recorded size, so a slot sitting outside a wrong size is still carried over
 * instead of being quietly left behind.
 *
 * <p>This is the one place a migration must touch Bukkit: legacy files hold serialized
 * {@code ItemStack} sections, while the SQL schema holds the bytes of
 * {@code ItemStack.serializeAsBytes()}. Every failure on that boundary aborts and names the
 * player, the backpack and the slot. Nothing is skipped, and an item that will not convert
 * is never replaced by an empty slot.
 *
 * <p>Waypoint worlds are read and written as plain names. A {@code Location} is never
 * constructed, because that needs a loaded {@code World} and is exactly how the provider
 * read path loses waypoints of worlds that are not loaded.
 *
 * @author SlimefunCore Contributors
 */
public final class LegacyRecordStore {

    private static final String PLAYERS_DIRECTORY = "Players";
    private static final String WAYPOINTS_DIRECTORY = "waypoints";
    private static final String RESEARCHES = "researches";
    private static final String BACKPACKS = "backpacks";
    private static final String CONTENTS = "contents";
    private static final String SIZE = "size";
    private static final String NAME = "name";
    private static final String WORLD = "world";

    private LegacyRecordStore() {}

    /**
     * @param root The data root, normally {@code data-storage/Slimefun}
     * @param uuid The player
     * @return The file holding researches and backpacks
     */
    @Nonnull
    public static File playerFile(@Nonnull File root, @Nonnull UUID uuid) {
        return new File(new File(root, PLAYERS_DIRECTORY), uuid + ".yml");
    }

    /**
     * @param root The data root, normally {@code data-storage/Slimefun}
     * @param uuid The player
     * @return The file holding waypoints
     */
    @Nonnull
    public static File waypointsFile(@Nonnull File root, @Nonnull UUID uuid) {
        return new File(new File(root, WAYPOINTS_DIRECTORY), uuid + ".yml");
    }

    /**
     * Reads everything one player owns from the legacy tree.
     *
     * @param root The data root to read from
     * @param uuid The player to read
     * @return The player's records, empty if neither file exists
     * @throws MigrationException If any stored element cannot be converted
     */
    @Nonnull
    public static PlayerRecords read(@Nonnull File root, @Nonnull UUID uuid) throws MigrationException {
        PlayerRecords records = new PlayerRecords(uuid);
        File players = playerFile(root, uuid);

        if (players.isFile()) {
            Config config = new Config(players);

            readResearches(config, uuid, records);
            readBackpacks(config, uuid, records);
        }

        File waypoints = waypointsFile(root, uuid);

        if (waypoints.isFile()) {
            readWaypoints(new Config(waypoints), uuid, records);
        }

        return records;
    }

    private static void readResearches(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws MigrationException {
        if (!config.contains(RESEARCHES)) {
            return;
        }

        for (String key : new TreeSet<>(config.getKeys(RESEARCHES))) {
            records.addResearch(requireInt(uuid, RESEARCHES, RESEARCHES + '.' + key, key, "research id"));
        }
    }

    private static void readBackpacks(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws MigrationException {
        if (!config.contains(BACKPACKS)) {
            return;
        }

        for (String key : new TreeSet<>(config.getKeys(BACKPACKS))) {
            String base = BACKPACKS + '.' + key;
            int id = requireInt(uuid, BACKPACKS, base, key, "backpack id");

            if (!config.contains(base + '.' + SIZE)) {
                throw new MigrationException(uuid, BACKPACKS, base + '.' + SIZE,
                    "Backpack has no stored size, migrating it would have to invent one");
            }

            records.addBackpack(new PlayerRecords.BackpackRecord(
                id, config.getInt(base + '.' + SIZE), readItems(config, uuid, base)));
        }
    }

    @Nonnull
    private static Map<Integer, byte[]> readItems(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull String base
    ) throws MigrationException {
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        String contents = base + '.' + CONTENTS;

        if (!config.contains(contents)) {
            return items;
        }

        Map<Integer, String> slots = new TreeMap<>();

        for (String key : config.getKeys(contents)) {
            slots.put(requireInt(uuid, BACKPACKS, contents + '.' + key, key, "backpack slot"), key);
        }

        for (Map.Entry<Integer, String> slot : slots.entrySet()) {
            String path = contents + '.' + slot.getValue();
            items.put(slot.getKey(), serialize(uuid, path, readItem(config, uuid, path)));
        }

        return items;
    }

    @Nonnull
    private static ItemStack readItem(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull String path
    ) throws MigrationException {
        ItemStack item;

        try {
            item = config.getItem(path);
        } catch (Exception x) {
            throw new MigrationException(uuid, BACKPACKS, path, "Stored item could not be read", x);
        }

        if (item == null) {
            throw new MigrationException(uuid, BACKPACKS, path,
                "Stored item is present but did not load, migrating would drop it");
        }

        return item;
    }

    @Nonnull
    private static byte[] serialize(
        @Nonnull UUID uuid,
        @Nonnull String path,
        @Nonnull ItemStack item
    ) throws MigrationException {
        try {
            return item.serializeAsBytes();
        } catch (Exception x) {
            throw new MigrationException(uuid, BACKPACKS, path, "Stored item could not be serialized", x);
        }
    }

    private static void readWaypoints(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull PlayerRecords records
    ) throws MigrationException {
        for (String key : new TreeSet<>(config.getKeys())) {
            String world = config.getString(key + '.' + WORLD);

            if (world == null || world.isBlank()) {
                throw new MigrationException(uuid, WAYPOINTS_DIRECTORY, key + '.' + WORLD,
                    "Waypoint has no stored world and cannot be migrated");
            }

            records.addWaypoint(new PlayerRecords.WaypointRecord(
                key,
                config.getString(key + '.' + NAME),
                world,
                requireDouble(config, uuid, key + ".x"),
                requireDouble(config, uuid, key + ".y"),
                requireDouble(config, uuid, key + ".z"),
                (float) requireDouble(config, uuid, key + ".yaw"),
                (float) requireDouble(config, uuid, key + ".pitch")
            ));
        }
    }

    /**
     * Writes everything one player owns into the legacy tree.
     *
     * <p>A file is only touched when the records hold something for it, and each write goes
     * through {@link AtomicFiles} so a failure cannot leave a half-written player file.
     *
     * @param root    The data root to write into
     * @param records The records to write
     * @return How many individual entries were written
     * @throws MigrationException If an item cannot be converted or a file cannot be written
     */
    public static int write(@Nonnull File root, @Nonnull PlayerRecords records) throws MigrationException {
        return writePlayerFile(root, records) + writeWaypointsFile(root, records);
    }

    private static int writePlayerFile(
        @Nonnull File root,
        @Nonnull PlayerRecords records
    ) throws MigrationException {
        if (records.getResearches().isEmpty() && records.getBackpacks().isEmpty()) {
            return 0;
        }

        UUID uuid = records.getUuid();
        Config config = new Config(playerFile(root, uuid));

        config.setValue(RESEARCHES, null);
        config.setValue(BACKPACKS, null);

        int written = 0;

        for (int researchId : records.getResearches()) {
            config.setValue(RESEARCHES + '.' + researchId, true);
            written++;
        }

        for (PlayerRecords.BackpackRecord backpack : records.getBackpacks()) {
            String base = BACKPACKS + '.' + backpack.id();

            config.setValue(base + '.' + SIZE, backpack.size());
            written++;

            for (Map.Entry<Integer, byte[]> item : backpack.items().entrySet()) {
                if (item.getValue() == null) {
                    continue;
                }

                String path = base + '.' + CONTENTS + '.' + item.getKey();

                config.setValue(path, deserialize(uuid, path, item.getValue()));
                written++;
            }
        }

        save(config, uuid, PLAYERS_DIRECTORY);

        return written;
    }

    private static int writeWaypointsFile(
        @Nonnull File root,
        @Nonnull PlayerRecords records
    ) throws MigrationException {
        if (records.getWaypoints().isEmpty()) {
            return 0;
        }

        UUID uuid = records.getUuid();
        Config config = new Config(waypointsFile(root, uuid));

        config.clear();

        int written = 0;

        for (PlayerRecords.WaypointRecord waypoint : records.getWaypoints()) {
            config.setValue(waypoint.id() + '.' + WORLD, waypoint.world());
            config.setValue(waypoint.id() + ".x", waypoint.x());
            config.setValue(waypoint.id() + ".y", waypoint.y());
            config.setValue(waypoint.id() + ".z", waypoint.z());
            config.setValue(waypoint.id() + ".yaw", waypoint.yaw());
            config.setValue(waypoint.id() + ".pitch", waypoint.pitch());

            if (waypoint.name() != null) {
                config.setValue(waypoint.id() + '.' + NAME, waypoint.name());
            }

            written++;
        }

        save(config, uuid, WAYPOINTS_DIRECTORY);

        return written;
    }

    /**
     * Removes both legacy files of one player, used when a migration is allowed to overwrite.
     *
     * @param root The data root to clear in
     * @param uuid The player to clear
     * @return How many files were removed
     * @throws MigrationException If a file exists but could not be removed
     */
    public static int delete(@Nonnull File root, @Nonnull UUID uuid) throws MigrationException {
        return deleteFile(playerFile(root, uuid), uuid, PLAYERS_DIRECTORY)
            + deleteFile(waypointsFile(root, uuid), uuid, WAYPOINTS_DIRECTORY);
    }

    private static int deleteFile(
        @Nonnull File file,
        @Nonnull UUID uuid,
        @Nonnull String section
    ) throws MigrationException {
        if (!file.isFile()) {
            return 0;
        }

        try {
            Files.delete(file.toPath());
            return 1;
        } catch (IOException x) {
            throw new MigrationException(uuid, section, file.getPath(),
                "Could not remove the existing legacy file", x);
        }
    }

    @Nonnull
    private static ItemStack deserialize(
        @Nonnull UUID uuid,
        @Nonnull String path,
        @Nonnull byte[] bytes
    ) throws MigrationException {
        ItemStack item;

        try {
            item = ItemStack.deserializeBytes(bytes);
        } catch (Exception x) {
            throw new MigrationException(uuid, BACKPACKS, path,
                "Stored item bytes could not be deserialized", x);
        }

        if (item == null) {
            throw new MigrationException(uuid, BACKPACKS, path,
                "Stored item bytes did not yield an item, migrating would drop it");
        }

        return item;
    }

    private static void save(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull String section
    ) throws MigrationException {
        try {
            AtomicFiles.saveConfig(config);
        } catch (IOException x) {
            throw new MigrationException(uuid, section, config.getFile().getPath(),
                "Could not write the legacy file", x);
        }
    }

    private static int requireInt(
        @Nonnull UUID uuid,
        @Nonnull String section,
        @Nonnull String path,
        @Nonnull String raw,
        @Nonnull String what
    ) throws MigrationException {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException x) {
            throw new MigrationException(uuid, section, path,
                "Stored " + what + " \"" + raw + "\" is not a number", x);
        }
    }

    private static double requireDouble(
        @Nonnull Config config,
        @Nonnull UUID uuid,
        @Nonnull String path
    ) throws MigrationException {
        Object value = config.getValue(path);

        if (value == null) {
            throw new MigrationException(uuid, WAYPOINTS_DIRECTORY, path,
                "Waypoint coordinate is missing, migrating it would have to invent one");
        }

        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException x) {
            throw new MigrationException(uuid, WAYPOINTS_DIRECTORY, path,
                "Waypoint coordinate \"" + value + "\" is not a number", x);
        }
    }
}
