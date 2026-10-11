package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a checkpoint creation attempt.
 *
 * <p>A failed attempt leaves nothing behind: the partially written directory is removed, so a
 * half-valid checkpoint can never appear in {@link CheckpointService#list()}.
 *
 * @param success        Whether the checkpoint was created and verified
 * @param message        A human readable summary, safe to show to an operator
 * @param checkpoint     The created checkpoint, or {@code null} on failure
 * @param steps          The ordered progress log for this attempt
 * @param timestamp      When the attempt finished
 * @param automatic      Whether the scheduler made this attempt
 * @param durationMillis How long the attempt took
 * @param purged         Ids of checkpoints removed by retention afterwards
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record CheckpointResult(
    boolean success,
    @Nonnull String message,
    @Nullable Checkpoint checkpoint,
    @Nonnull List<String> steps,
    @Nonnull Instant timestamp,
    boolean automatic,
    long durationMillis,
    @Nonnull List<String> purged
) {

    /**
     * Creates a result record, defensively copying the mutable lists.
     *
     * @param success        Whether the checkpoint was created and verified
     * @param message        A human readable summary
     * @param checkpoint     The created checkpoint, or {@code null}
     * @param steps          The ordered progress log
     * @param timestamp      When the attempt finished
     * @param automatic      Whether the scheduler made this attempt
     * @param durationMillis How long the attempt took
     * @param purged         Ids removed by retention
     */
    public CheckpointResult {
        steps = List.copyOf(steps);
        purged = List.copyOf(purged);
    }

    /**
     * Returns the id of the created checkpoint, or a placeholder when the attempt failed.
     *
     * @return The checkpoint id, or {@literal "none"}
     */
    @Nonnull
    public String checkpointId() {
        return checkpoint == null ? "none" : checkpoint.id();
    }
}
