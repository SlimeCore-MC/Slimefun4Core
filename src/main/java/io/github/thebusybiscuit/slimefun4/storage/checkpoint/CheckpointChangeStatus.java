package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;

/**
 * Whether Slimefun data has changed since the newest checkpoint.
 *
 * <p>Change detection never creates a checkpoint on its own. It only reports the two dirty
 * markers so that the configured process — an operator or the scheduler — can decide when the
 * next checkpoint is worth taking.
 *
 * @param lastCheckpointId The newest checkpoint id, or {@code null} when none exist
 * @param lastCreatedAt    When the newest checkpoint was created, or {@code null}
 * @param storageChanged   Whether the file-based Slimefun data changed since then
 * @param databaseChanged  Whether database-owned player data changed since then
 * @param detail           A short explanation of how the answer was determined
 * @param total            How many valid checkpoints exist
 * @param max              The configured retention limit
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record CheckpointChangeStatus(
    @Nullable String lastCheckpointId,
    @Nullable Instant lastCreatedAt,
    boolean storageChanged,
    boolean databaseChanged,
    @Nonnull String detail,
    int total,
    int max
) {

    /**
     * Returns whether a new checkpoint would capture anything new.
     *
     * <p>Always {@code true} when no checkpoint exists yet.
     *
     * @return {@code true} when a checkpoint is worth taking
     */
    public boolean checkpointRequired() {
        return lastCheckpointId == null || storageChanged || databaseChanged;
    }
}
