package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable metadata record written alongside every database backup file.
 *
 * <p>Stored as {@code backup-meta.json} inside the backup ZIP, or as a
 * sibling {@code .meta.json} file for uncompressed backups.
 *
 * @param backupId        Unique identifier for this backup (UUID string)
 * @param provider        Provider name at backup time (e.g. {@literal "SQLite"})
 * @param createdAt       When the backup was created (UTC)
 * @param rowCounts       Map of table name → row count at backup time
 * @param checksum        SHA-256 hex checksum of the backup data file
 * @param slimefunVersion Slimefun version string at backup time
 * @param automatic       Whether this backup was triggered automatically
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
    boolean automatic
) {

    /**
     * Returns the total number of rows across all tables in this backup.
     *
     * @return Sum of all table row counts
     */
    public int totalRows() {
        return rowCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
