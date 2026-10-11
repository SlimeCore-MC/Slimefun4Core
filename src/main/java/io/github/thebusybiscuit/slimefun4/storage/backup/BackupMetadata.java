package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable metadata record written alongside every database backup file.
 *
 * <p>Stored as a sibling {@code .meta.json} file next to the backup data file.
 *
 * @param backupId        Unique identifier for this backup (UUID string)
 * @param provider        Provider name at backup time (e.g. {@literal "SQLite"})
 * @param createdAt       When the backup was created (UTC)
 * @param rowCounts       Map of table name (or file section) to entry count
 * @param checksum        SHA-256 hex checksum of the backup data file
 * @param slimefunVersion Slimefun version string at backup time
 * @param automatic       Whether this backup was triggered automatically
 * @param format          The physical shape of the archive
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record BackupMetadata(
    @Nonnull String backupId,
    @Nonnull String provider,
    @Nonnull Instant createdAt,
    @Nonnull Map<String, Integer> rowCounts,
    @Nonnull String checksum,
    @Nonnull String slimefunVersion,
    boolean automatic,
    @Nonnull BackupFormat format
) {

    /**
     * Creates metadata for a {@link BackupFormat#SQL_JSON} backup.
     *
     * <p>Retained so that existing call sites and metadata written by older builds
     * keep working unchanged.
     *
     * @param backupId        Unique identifier for this backup
     * @param provider        Provider name at backup time
     * @param createdAt       When the backup was created
     * @param rowCounts       Map of table name to row count
     * @param checksum        SHA-256 hex checksum of the data file
     * @param slimefunVersion Slimefun version string at backup time
     * @param automatic       Whether this backup was triggered automatically
     */
    public BackupMetadata(
        @Nonnull String backupId,
        @Nonnull String provider,
        @Nonnull Instant createdAt,
        @Nonnull Map<String, Integer> rowCounts,
        @Nonnull String checksum,
        @Nonnull String slimefunVersion,
        boolean automatic
    ) {
        this(backupId, provider, createdAt, rowCounts, checksum, slimefunVersion, automatic, BackupFormat.SQL_JSON);
    }

    /**
     * Returns the total number of entries across all sections in this backup.
     *
     * @return Sum of all section counts
     */
    public int totalRows() {
        return rowCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Returns whether this backup recorded no content at all.
     *
     * <p>An empty backup is never a valid restore source and must never be counted
     * as a healthy copy when applying retention.
     *
     * @return {@code true} if no sections or zero total entries were recorded
     */
    public boolean isEmpty() {
        return rowCounts.isEmpty() || totalRows() <= 0;
    }
}
