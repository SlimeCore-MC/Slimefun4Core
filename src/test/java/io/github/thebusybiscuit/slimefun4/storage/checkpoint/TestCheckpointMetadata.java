package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the checkpoint metadata document, which is the only authority on whether a checkpoint
 * may be restored.
 *
 * <p>A document that cannot be read completely must never present itself as healthy, and a
 * checkpoint with one failed half must never count as valid. Both halves are recorded separately
 * and neither is allowed to speak for the other.
 */
class TestCheckpointMetadata {

    private static final Instant CREATED = Instant.parse("2026-09-05T16:40:00Z");

    @Test
    @DisplayName("Both halves survive a JSON round trip unchanged")
    void roundTripPreservesBothHalves() throws CheckpointException {
        CheckpointMetadata original = metadata(storageHalf(), databaseHalf());
        String json = original.toJson();

        assertTrue(json.contains("\"checkpointId\": \"checkpoint-007\""), json);
        assertTrue(json.contains("\"valid\": true"), json);
        assertTrue(json.contains("\"storage\""), json);
        assertTrue(json.contains("\"database\""), json);

        CheckpointMetadata parsed = CheckpointMetadata.fromJson(json);

        assertEquals(original, parsed, "Metadata must survive a write and a read back unchanged");
        assertEquals(CREATED, parsed.createdAt());
        assertEquals(CheckpointMetadata.CURRENT_FORMAT_VERSION, parsed.formatVersion());
        assertEquals(Map.of("Players", 3, "stored-blocks", 12, "stored-chunks", 1), parsed.storage().counts());
        assertEquals(BackupFormat.LEGACY_FILES, parsed.storage().format());
        assertEquals(BackupFormat.SQL_JSON, parsed.database().format());
        assertTrue(parsed.isValid());
    }

    @Test
    @DisplayName("A document missing a half reads that half back as FAILED")
    void missingComponentReadsBackAsFailed() throws CheckpointException {
        CheckpointMetadata parsed =
            CheckpointMetadata.fromJson("{\"checkpointId\": \"checkpoint-004\", \"sequence\": 4}");

        assertEquals("checkpoint-004", parsed.checkpointId());
        assertEquals(4, parsed.sequence());
        assertEquals(CheckpointStatus.FAILED, parsed.storage().status());
        assertEquals(CheckpointStatus.FAILED, parsed.database().status());
        assertTrue(parsed.storage().detail().contains("storage"), parsed.storage().detail());
        assertTrue(parsed.database().detail().contains("database"), parsed.database().detail());
        assertFalse(parsed.isValid(), "An incomplete document can never be restorable");
    }

    @Test
    @DisplayName("One failed half invalidates the whole checkpoint")
    void oneFailedHalfInvalidatesTheCheckpoint() {
        CheckpointComponent failure = CheckpointComponent.failed(BackupFormat.SQL_JSON, "Connection refused");

        assertFalse(metadata(storageHalf(), failure).isValid(), "A failed database half must invalidate it");
        assertFalse(metadata(failure, databaseHalf()).isValid(), "A failed storage half must invalidate it");
    }

    @Test
    @DisplayName("A checkpoint that captured nothing at all is not a restore source")
    void twoSkippedHalvesAreNotValid() {
        CheckpointMetadata empty = metadata(
            CheckpointComponent.skipped(BackupFormat.LEGACY_FILES, "No data files"),
            CheckpointComponent.skipped(BackupFormat.SQL_JSON, "No rows"));

        assertFalse(empty.isValid());
        assertEquals(0, empty.totalEntries());
    }

    @Test
    @DisplayName("One captured half is enough, because a provider may legitimately hold nothing")
    void oneCapturedHalfIsValid() {
        assertTrue(metadata(storageHalf(), CheckpointComponent.skipped(BackupFormat.SQL_JSON, "No rows")).isValid());
        assertTrue(metadata(CheckpointComponent.skipped(BackupFormat.LEGACY_FILES, "None"), databaseHalf()).isValid());
    }

    @Test
    @DisplayName("A document that is not a JSON object is rejected")
    void nonObjectDocumentIsRejected() {
        CheckpointException failure = assertThrows(CheckpointException.class, () -> CheckpointMetadata.fromJson("[]"));

        assertTrue(failure.getMessage().contains("not a JSON object"), failure.getMessage());
        assertThrows(CheckpointException.class, () -> CheckpointMetadata.fromJson("\"checkpoint-001\""));
        assertThrows(CheckpointException.class, () -> CheckpointMetadata.fromJson("7"));
    }

    @Test
    @DisplayName("Malformed JSON is reported as such instead of crashing a restore")
    void malformedDocumentIsRejected() {
        CheckpointException failure = assertThrows(CheckpointException.class,
            () -> CheckpointMetadata.fromJson("{\"checkpointId\": "));

        assertTrue(failure.getMessage().contains("not valid JSON"), failure.getMessage());
    }

    @Test
    @DisplayName("A document naming no checkpoint is rejected")
    void blankIdIsRejected() {
        assertThrows(CheckpointException.class, () -> CheckpointMetadata.fromJson("{}"));

        CheckpointException failure = assertThrows(CheckpointException.class,
            () -> CheckpointMetadata.fromJson("{\"checkpointId\": \"   \"}"));

        assertTrue(failure.getMessage().contains("names no checkpoint id"), failure.getMessage());
    }

    @Test
    @DisplayName("An unreadable timestamp does not stop the document from parsing")
    void unreadableTimestampFallsBackToEpoch() throws CheckpointException {
        CheckpointMetadata parsed = CheckpointMetadata.fromJson(
            "{\"checkpointId\": \"checkpoint-002\", \"createdAt\": \"yesterday\"}");

        assertEquals(Instant.EPOCH, parsed.createdAt());
        assertEquals("?", parsed.slimefunVersion());
        assertEquals("Unknown", parsed.provider());
        assertEquals("UNKNOWN", parsed.providerType());
        assertFalse(parsed.automatic());
    }

    @Test
    @DisplayName("An unknown status is read back as FAILED, never as healthy")
    void unknownStatusIsTreatedAsFailed() throws CheckpointException {
        String json = "{\"checkpointId\": \"checkpoint-005\","
            + "\"storage\": {\"status\": \"PROBABLY\", \"fileName\": \"storage.zip\", \"format\": \"WHATEVER\"},"
            + "\"database\": {\"status\": \"ok\", \"fileName\": \"database-backup.json\"}}";

        CheckpointMetadata parsed = CheckpointMetadata.fromJson(json);

        assertEquals(CheckpointStatus.FAILED, parsed.storage().status());
        assertEquals(BackupFormat.SQL_JSON, parsed.storage().format(), "An unknown format falls back to the row dump");
        assertEquals(CheckpointStatus.OK, parsed.database().status(), "A lowercase status still resolves");
        assertFalse(parsed.isValid());
    }

    @Test
    @DisplayName("Entry counts add up across both halves")
    void totalEntriesSumsBothHalves() {
        CheckpointMetadata full = metadata(storageHalf(), databaseHalf());

        assertEquals(16, full.storage().totalEntries());
        assertEquals(11, full.database().totalEntries());
        assertEquals(27, full.totalEntries());
    }

    @Test
    @DisplayName("A component copies its counts and reports presence from its status")
    void componentIsImmutableAndReportsPresence() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Players", 2);

        CheckpointComponent component = new CheckpointComponent(Checkpoint.STORAGE_FILE,
            BackupFormat.LEGACY_FILES, "abc", 10L, counts, CheckpointStatus.OK, "2 files");

        counts.put("Players", 99);

        assertEquals(2, component.totalEntries(), "A component must not see later edits to the source map");
        assertThrows(UnsupportedOperationException.class, () -> component.counts().put("waypoints", 1));
        assertTrue(component.isPresent());
        assertFalse(CheckpointComponent.skipped(BackupFormat.SQL_JSON, "No rows").isPresent());
        assertFalse(CheckpointComponent.failed(BackupFormat.SQL_JSON, "Broken").isPresent());
    }

    @Test
    @DisplayName("An absent or unknown status name resolves to FAILED")
    void statusFallsBackToFailed() {
        assertEquals(CheckpointStatus.OK, CheckpointStatus.fromName(" ok "));
        assertEquals(CheckpointStatus.SKIPPED, CheckpointStatus.fromName("SKIPPED"));
        assertEquals(CheckpointStatus.FAILED, CheckpointStatus.fromName(null));
        assertEquals(CheckpointStatus.FAILED, CheckpointStatus.fromName(""));
        assertEquals(CheckpointStatus.FAILED, CheckpointStatus.fromName("healthy"));
        assertTrue(CheckpointStatus.OK.isAcceptable());
        assertTrue(CheckpointStatus.SKIPPED.isAcceptable(), "Nothing to capture is not a failure");
        assertFalse(CheckpointStatus.FAILED.isAcceptable());
    }

    @Test
    @DisplayName("Ids are sequential and zero padded to three digits")
    void idsArePaddedAndSequential() {
        assertEquals("checkpoint-001", CheckpointService.formatId(1));
        assertEquals("checkpoint-007", CheckpointService.formatId(7));
        assertEquals("checkpoint-010", CheckpointService.formatId(CheckpointService.DEFAULT_MAX_CHECKPOINTS));
        assertEquals("checkpoint-1000", CheckpointService.formatId(1000), "Padding widens, it never truncates");
        assertNotEquals(CheckpointService.formatId(1), CheckpointService.formatId(2));
        assertTrue(CheckpointService.formatId(3).startsWith(CheckpointService.ID_PREFIX));
    }

    private static CheckpointMetadata metadata(CheckpointComponent storage, CheckpointComponent database) {
        return new CheckpointMetadata(
            CheckpointMetadata.CURRENT_FORMAT_VERSION,
            CheckpointService.formatId(7),
            7,
            CREATED,
            "4.0-UNOFFICIAL",
            "Paper 26.2",
            "SQLite",
            "SQLITE",
            false,
            storage,
            database);
    }

    private static CheckpointComponent storageHalf() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Players", 3);
        counts.put("stored-blocks", 12);
        counts.put("stored-chunks", 1);

        return new CheckpointComponent(Checkpoint.STORAGE_FILE, BackupFormat.LEGACY_FILES,
            "1f0c9a7b5e3d2c1b0a9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a32",
            4096L, counts, CheckpointStatus.OK, "16 files");
    }

    private static CheckpointComponent databaseHalf() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("player_researches", 9);
        counts.put("player_backpacks", 2);

        return new CheckpointComponent(Checkpoint.DATABASE_FILE_BASE + ".json", BackupFormat.SQL_JSON,
            "a09f8e7d6c5b4a32281706f5e4d3c2b11f0c9a7b5e3d2c1b0a9f8e7d6c5b4a39",
            2048L, counts, CheckpointStatus.OK, "11 rows");
    }
}
