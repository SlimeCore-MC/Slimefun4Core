package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.List;

/**
 * Outcome of a checkpoint restore.
 *
 * <p>A restore is never silently partial. When the targeted half fails, {@link #rolledBack()}
 * reports whether the safety backup was put back, and {@link #safetyBackupId()} names the copy
 * an operator can fall back on by hand.
 *
 * @param success        Whether the requested scope was restored and validated
 * @param message        A human readable summary, safe to show to an operator
 * @param steps          The ordered step log, one line per stage of the restore
 * @param safetyBackupId The id of the pre-restore safety copy, or {@code null} if none was taken
 * @param storageFiles   Number of storage files written
 * @param databaseRows   Number of database rows or player files written
 * @param rolledBack     Whether a failed restore was rolled back from the safety copy
 * @param scope          Which half of the checkpoint was targeted by this restore
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record CheckpointRestoreResult(
    boolean success,
    @Nonnull String message,
    @Nonnull List<String> steps,
    @Nullable String safetyBackupId,
    int storageFiles,
    int databaseRows,
    boolean rolledBack,
    @Nonnull RestoreScope scope
) {

    /**
     * Creates a restore result, defensively copying the step log.
     *
     * @param success        Whether the requested scope was restored and validated
     * @param message        A human readable summary
     * @param steps          The ordered step log
     * @param safetyBackupId The id of the pre-restore safety copy, or {@code null}
     * @param storageFiles   Number of storage files written
     * @param databaseRows   Number of database rows or player files written
     * @param rolledBack     Whether a failed restore was rolled back
     * @param scope          Which half of the checkpoint was targeted
     */
    public CheckpointRestoreResult {
        steps = List.copyOf(steps);
    }
}
