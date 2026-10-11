package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.backup.FakeStorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.backup.SqliteFixture;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression test for the four filters that make {@code loadPlayerData} lossy.
 *
 * <p>An unknown research id, a backpack size outside the standard six, an item that no
 * server could deserialize and a waypoint in an unloaded world are all dropped silently by
 * both providers. Every one of them must survive a records round trip, because a migration
 * that quietly shrinks a player's data is exactly the failure this layer exists to prevent.
 */
class TestPlayerRecords {

    private static final UUID PLAYER = UUID.fromString("3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30");

    @TempDir
    Path workspace;

    @Test
    @DisplayName("An unknown research id survives, so a removed addon cannot erase progress")
    void anUnknownResearchIdSurvives() throws SQLException {
        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addResearch(3);
        source.addResearch(999_999);
        source.addResearch(-42);

        PlayerRecords result = roundTrip("researches.db", source);

        assertTrue(result.getResearches().contains(999_999),
            "A research id no longer in the registry must still be carried across");
        assertTrue(result.getResearches().contains(-42));
        assertEquals(3, result.getResearches().size());
        assertEquals(source.digest(), result.digest());
    }

    @Test
    @DisplayName("A backpack size of 13 survives, because nothing here whitelists sizes")
    void anOddBackpackSizeSurvives() throws SQLException {
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        items.put(0, SqliteFixture.blobPayload());
        items.put(12, new byte[] {1, 2, 3});

        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addBackpack(new PlayerRecords.BackpackRecord(1, 13, items));

        PlayerRecords result = roundTrip("sizes.db", source);

        assertEquals(1, result.getBackpacks().size());
        assertEquals(13, result.getBackpacks().get(0).size(),
            "A size outside the standard six must not be corrected or dropped");
        assertEquals(2, result.getBackpacks().get(0).items().size());
        assertEquals(source.digest(), result.digest());
    }

    @Test
    @DisplayName("Bytes that no server could deserialize still cross intact")
    void undeserializableItemBytesSurvive() throws SQLException {
        byte[] garbage = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, 0x7F, (byte) 0x80};
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        items.put(0, garbage);
        items.put(1, SqliteFixture.blobPayload());

        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addBackpack(new PlayerRecords.BackpackRecord(2, 27, items));

        PlayerRecords result = roundTrip("items.db", source);

        assertArrayEquals(garbage, result.getBackpacks().get(0).items().get(0),
            "The SQL path never deserializes, so unreadable bytes are still copied");
        assertArrayEquals(SqliteFixture.blobPayload(), result.getBackpacks().get(0).items().get(1));
        assertEquals(source.digest(), result.digest());
    }

    @Test
    @DisplayName("A waypoint in an unloaded world survives, world held as a plain name")
    void aWaypointInAnUnloadedWorldSurvives() throws SQLException {
        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addWaypoint(new PlayerRecords.WaypointRecord(
            "wp-1", SqliteFixture.TRICKY_NAME, "not-loaded", -0.5D, 63.0D, 128.25D, -45.0F, 12.5F));
        source.addWaypoint(new PlayerRecords.WaypointRecord(
            "wp-2", "Base", "world_nether", 1.5D, -2.25D, 3.75D, 0.0F, 0.25F));

        PlayerRecords result = roundTrip("waypoints.db", source);

        assertEquals(2, result.getWaypoints().size());

        PlayerRecords.WaypointRecord first = result.getWaypoints().get(0);

        assertEquals("not-loaded", first.world(),
            "An unloaded world must never drop a waypoint, so the world stays a name");
        assertEquals(SqliteFixture.TRICKY_NAME, first.name(),
            "A quote, a brace and a newline in a waypoint name must survive");
        assertEquals(-0.5D, first.x(), 0.0D);
        assertEquals(63.0D, first.y(), 0.0D);
        assertEquals(128.25D, first.z(), 0.0D);
        assertEquals(-45.0F, first.yaw(), 0.0F);
        assertEquals(12.5F, first.pitch(), 0.0F);
        assertEquals(source.digest(), result.digest());
    }

    @Test
    @DisplayName("All four dropped shapes survive together in one player's records")
    void allFourFiltersSurviveTogether() throws SQLException {
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        items.put(0, new byte[] {(byte) 0xFF, 0x01});

        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addResearch(1_000_001);
        source.addBackpack(new PlayerRecords.BackpackRecord(7, 13, items));
        source.addWaypoint(new PlayerRecords.WaypointRecord(
            "far", "Outpost", "not-loaded", 10.0D, 20.0D, 30.0D, 1.0F, 2.0F));

        assertEquals(4, source.countEntries());

        PlayerRecords result = roundTrip("combined.db", source);

        assertEquals(4, result.countEntries());
        assertEquals(source.digest(), result.digest());
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("An empty slot stays empty, and is not confused with a zero length item")
    void anEmptySlotStaysEmpty() throws SQLException {
        Map<Integer, byte[]> items = new LinkedHashMap<>();
        items.put(0, SqliteFixture.blobPayload());
        items.put(1, null);

        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addBackpack(new PlayerRecords.BackpackRecord(1, 27, items));

        PlayerRecords result = roundTrip("empty-slot.db", source);

        assertNull(result.getBackpacks().get(0).items().get(1),
            "A NULL blob must read back as an empty slot");
        assertEquals(source.digest(), result.digest(),
            "The digest skips empty slots, so both layouts agree");
    }

    @Test
    @DisplayName("A missing waypoint name is stored as an empty string, never as a lost row")
    void aMissingWaypointNameBecomesEmpty() throws SQLException {
        PlayerRecords source = new PlayerRecords(PLAYER);
        source.addWaypoint(new PlayerRecords.WaypointRecord(
            "unnamed", null, "world", 0.0D, 0.0D, 0.0D, 0.0F, 0.0F));

        PlayerRecords result = roundTrip("unnamed.db", source);

        assertEquals(1, result.getWaypoints().size());
        assertEquals("", result.getWaypoints().get(0).name(),
            "player_waypoints.name is NOT NULL, so an absent label becomes empty rather than aborting");
        assertEquals(source.digest(), result.digest());
    }

    @Test
    @DisplayName("A digest is order independent but notices a single changed byte")
    void theDigestIsStableAndSensitive() {
        PlayerRecords first = new PlayerRecords(PLAYER);
        first.addResearch(5);
        first.addResearch(1);
        first.addWaypoint(new PlayerRecords.WaypointRecord("b", "B", "world", 1, 2, 3, 0, 0));
        first.addWaypoint(new PlayerRecords.WaypointRecord("a", "A", "world", 4, 5, 6, 0, 0));

        PlayerRecords second = new PlayerRecords(PLAYER);
        second.addResearch(1);
        second.addResearch(5);
        second.addWaypoint(new PlayerRecords.WaypointRecord("a", "A", "world", 4, 5, 6, 0, 0));
        second.addWaypoint(new PlayerRecords.WaypointRecord("b", "B", "world", 1, 2, 3, 0, 0));

        assertEquals(first.digest(), second.digest(), "Row order differs between backends");

        Map<Integer, byte[]> original = new LinkedHashMap<>();
        original.put(0, new byte[] {1, 2, 3});
        Map<Integer, byte[]> mutated = new LinkedHashMap<>();
        mutated.put(0, new byte[] {1, 2, 4});

        PlayerRecords intact = new PlayerRecords(PLAYER);
        intact.addBackpack(new PlayerRecords.BackpackRecord(1, 27, original));
        PlayerRecords damaged = new PlayerRecords(PLAYER);
        damaged.addBackpack(new PlayerRecords.BackpackRecord(1, 27, mutated));

        assertNotEquals(intact.digest(), damaged.digest(),
            "Verification must catch a truncated or altered item blob");
    }

    @Test
    @DisplayName("A player who owns nothing is empty and counts nothing")
    void anEmptyPlayerIsEmpty() {
        PlayerRecords records = new PlayerRecords(PLAYER);

        assertTrue(records.isEmpty());
        assertEquals(0, records.countEntries());
        assertEquals(PLAYER.toString(), records.digest());
        assertEquals(PLAYER, records.getUuid());
    }

    private PlayerRecords roundTrip(String database, PlayerRecords source) throws SQLException {
        try (Connection connection = FakeStorageProvider.sqlite(workspace.resolve(database).toFile()).connect()) {
            SqliteFixture.createSchema(connection);

            connection.setAutoCommit(false);
            int written = SqlRecordStore.write(connection, StorageProviderType.SQLITE, source);
            connection.commit();

            assertEquals(source.countEntries(), written,
                "Every entry must produce a row, none may be skipped");

            return SqlRecordStore.read(connection, StorageProviderType.SQLITE, source.getUuid());
        }
    }
}
