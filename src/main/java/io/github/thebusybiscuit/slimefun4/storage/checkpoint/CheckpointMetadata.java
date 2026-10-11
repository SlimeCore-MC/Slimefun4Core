package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code metadata.json} written into every checkpoint directory.
 *
 * <p>Metadata is the only authority on whether a checkpoint may be restored. It records both
 * component statuses and both checksums, so a half-written checkpoint is recognisable without
 * reading the archives themselves.
 *
 * @param formatVersion   Layout version of this metadata document
 * @param checkpointId    Directory name, for example {@literal "checkpoint-001"}
 * @param sequence        Numeric part of the id, used for ordering and for the next id
 * @param createdAt       When the checkpoint was created, UTC
 * @param slimefunVersion Slimefun version at creation time
 * @param serverVersion   Server version at creation time
 * @param provider        Storage provider name at creation time
 * @param providerType    Storage provider enum name at creation time
 * @param automatic       Whether the scheduler created this checkpoint
 * @param storage         The storage half, a file archive of the Slimefun data tree
 * @param database        The database half, database-owned player data only
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record CheckpointMetadata(
    int formatVersion,
    @Nonnull String checkpointId,
    int sequence,
    @Nonnull Instant createdAt,
    @Nonnull String slimefunVersion,
    @Nonnull String serverVersion,
    @Nonnull String provider,
    @Nonnull String providerType,
    boolean automatic,
    @Nonnull CheckpointComponent storage,
    @Nonnull CheckpointComponent database
) {

    /**
     * The metadata layout version written by this build.
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /**
     * Returns whether this checkpoint may be restored.
     *
     * <p>A checkpoint is valid when neither component failed and at least one of them actually
     * captured data. A checkpoint whose two halves were both skipped describes an empty server
     * and is never a useful restore source.
     *
     * @return {@code true} when the checkpoint is complete and restorable
     */
    public boolean isValid() {
        return storage.status().isAcceptable()
            && database.status().isAcceptable()
            && (storage.isPresent() || database.isPresent());
    }

    /**
     * Returns the total number of entries captured across both halves.
     *
     * @return Sum of storage files and database rows
     */
    public int totalEntries() {
        return storage.totalEntries() + database.totalEntries();
    }

    /**
     * Serializes this metadata as pretty printed JSON.
     *
     * @return The {@code metadata.json} content
     */
    @Nonnull
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", formatVersion);
        root.addProperty("checkpointId", checkpointId);
        root.addProperty("sequence", sequence);
        root.addProperty("createdAt", createdAt.toString());
        root.addProperty("slimefunVersion", slimefunVersion);
        root.addProperty("serverVersion", serverVersion);
        root.addProperty("provider", provider);
        root.addProperty("providerType", providerType);
        root.addProperty("automatic", automatic);
        root.addProperty("valid", isValid());
        root.add("storage", writeComponent(storage));
        root.add("database", writeComponent(database));

        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    /**
     * Parses a {@code metadata.json} document.
     *
     * <p>A missing or unreadable component is reported as {@link CheckpointStatus#FAILED} rather
     * than assumed healthy, so an incomplete document can never present itself as restorable.
     *
     * @param json The document content
     * @return The parsed metadata
     * @throws CheckpointException If the document is not a JSON object or names no checkpoint
     */
    @Nonnull
    public static CheckpointMetadata fromJson(@Nonnull String json) throws CheckpointException {
        JsonObject root;

        try {
            JsonElement parsed = JsonParser.parseString(json);

            if (!parsed.isJsonObject()) {
                throw new CheckpointException("Checkpoint metadata is not a JSON object");
            }

            root = parsed.getAsJsonObject();
        } catch (CheckpointException x) {
            throw x;
        } catch (RuntimeException x) {
            throw new CheckpointException("Checkpoint metadata is not valid JSON", x);
        }

        String id = string(root, "checkpointId", "");

        if (id.isBlank()) {
            throw new CheckpointException("Checkpoint metadata names no checkpoint id");
        }

        return new CheckpointMetadata(
            number(root, "formatVersion", CURRENT_FORMAT_VERSION),
            id,
            number(root, "sequence", 0),
            instant(root, "createdAt"),
            string(root, "slimefunVersion", "?"),
            string(root, "serverVersion", "?"),
            string(root, "provider", "Unknown"),
            string(root, "providerType", "UNKNOWN"),
            bool(root, "automatic"),
            readComponent(root, "storage"),
            readComponent(root, "database"));
    }

    @Nonnull
    private static JsonObject writeComponent(@Nonnull CheckpointComponent component) {
        JsonObject object = new JsonObject();
        object.addProperty("status", component.status().name());
        object.addProperty("fileName", component.fileName());
        object.addProperty("format", component.format().name());
        object.addProperty("checksum", component.checksum());
        object.addProperty("bytes", component.bytes());
        object.addProperty("entries", component.totalEntries());
        object.addProperty("detail", component.detail());

        JsonObject counts = new JsonObject();

        for (Map.Entry<String, Integer> entry : component.counts().entrySet()) {
            counts.addProperty(entry.getKey(), entry.getValue());
        }

        object.add("counts", counts);
        return object;
    }

    @Nonnull
    private static CheckpointComponent readComponent(@Nonnull JsonObject root, @Nonnull String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) {
            return CheckpointComponent.failed(BackupFormat.SQL_JSON, "Metadata holds no " + key + " component");
        }

        JsonObject object = root.getAsJsonObject(key);
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (object.has("counts") && object.get("counts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("counts").entrySet()) {
                try {
                    counts.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (RuntimeException ignored) {
                    counts.put(entry.getKey(), 0);
                }
            }
        }

        return new CheckpointComponent(
            string(object, "fileName", ""),
            BackupFormat.fromName(string(object, "format", null)),
            string(object, "checksum", ""),
            longNumber(object, "bytes"),
            counts,
            CheckpointStatus.fromName(string(object, "status", null)),
            string(object, "detail", ""));
    }

    @Nullable
    private static String string(@Nonnull JsonObject object, @Nonnull String key, @Nullable String fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }

        try {
            return object.get(key).getAsString();
        } catch (RuntimeException x) {
            return fallback;
        }
    }

    private static int number(@Nonnull JsonObject object, @Nonnull String key, int fallback) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }

        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException x) {
            return fallback;
        }
    }

    private static long longNumber(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return 0L;
        }

        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException x) {
            return 0L;
        }
    }

    private static boolean bool(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return false;
        }

        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException x) {
            return false;
        }
    }

    @Nonnull
    private static Instant instant(@Nonnull JsonObject object, @Nonnull String key) {
        String raw = string(object, key, null);

        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH;
        }

        try {
            return Instant.parse(raw);
        } catch (RuntimeException x) {
            return Instant.EPOCH;
        }
    }
}
