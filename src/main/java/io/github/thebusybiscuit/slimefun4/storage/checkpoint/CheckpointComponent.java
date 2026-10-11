package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.storage.backup.BackupFormat;

import javax.annotation.Nonnull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One half of a checkpoint: either the storage backup or the database backup.
 *
 * <p>The two halves are never mixed. The storage component describes a ZIP of the file-based
 * Slimefun data tree; the database component describes a row dump or a player file archive
 * holding database-owned player data only. Neither ever contains Minecraft world data.
 *
 * @param fileName The file name inside the component directory, empty when skipped
 * @param format   The physical shape of the captured file
 * @param checksum Lowercase hexadecimal SHA-256 of the captured file, empty when skipped
 * @param bytes    Size of the captured file in bytes
 * @param counts   Section or table name to entry count
 * @param status   Whether this component was captured, skipped or failed
 * @param detail   A short human readable explanation, safe to show to an operator
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record CheckpointComponent(
    @Nonnull String fileName,
    @Nonnull BackupFormat format,
    @Nonnull String checksum,
    long bytes,
    @Nonnull Map<String, Integer> counts,
    @Nonnull CheckpointStatus status,
    @Nonnull String detail
) {

    /**
     * Creates a component record, defensively copying the counts.
     *
     * @param fileName The file name inside the component directory
     * @param format   The physical shape of the captured file
     * @param checksum Lowercase hexadecimal SHA-256 of the captured file
     * @param bytes    Size of the captured file in bytes
     * @param counts   Section or table name to entry count
     * @param status   Whether this component was captured, skipped or failed
     * @param detail   A short human readable explanation
     */
    public CheckpointComponent {
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /**
     * Creates a component that captured nothing because there was no source data.
     *
     * @param format The format that would have been used
     * @param detail Why nothing was captured
     * @return A {@link CheckpointStatus#SKIPPED} component
     */
    @Nonnull
    public static CheckpointComponent skipped(@Nonnull BackupFormat format, @Nonnull String detail) {
        return new CheckpointComponent("", format, "", 0L, Map.of(), CheckpointStatus.SKIPPED, detail);
    }

    /**
     * Creates a component that could not be captured.
     *
     * @param format The format that was attempted
     * @param detail Why the capture failed
     * @return A {@link CheckpointStatus#FAILED} component
     */
    @Nonnull
    public static CheckpointComponent failed(@Nonnull BackupFormat format, @Nonnull String detail) {
        return new CheckpointComponent("", format, "", 0L, Map.of(), CheckpointStatus.FAILED, detail);
    }

    /**
     * Returns the total number of entries captured across all sections.
     *
     * @return Sum of all section counts
     */
    public int totalEntries() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Returns whether this component holds a restorable file.
     *
     * @return {@code true} when the status is {@link CheckpointStatus#OK}
     */
    public boolean isPresent() {
        return status == CheckpointStatus.OK && !fileName.isEmpty();
    }
}
