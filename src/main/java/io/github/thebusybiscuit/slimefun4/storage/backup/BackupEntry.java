package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import java.io.File;

/**
 * Pairs a backup data {@link File} with its {@link BackupMetadata}.
 *
 * <p>Returned by {@link DatabaseBackupService#listBackups()} so callers
 * can display backup information without needing to open the archive.
 *
 * @param dataFile The ZIP or JSON data file on disk
 * @param metadata The parsed metadata for this backup
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record BackupEntry(@Nonnull File dataFile, @Nonnull BackupMetadata metadata) {

    /**
     * Returns the file size of the backup data file in bytes.
     *
     * @return File size in bytes, or {@code -1} if the file no longer exists
     */
    public long sizeBytes() {
        return dataFile.exists() ? dataFile.length() : -1L;
    }

    /**
     * Returns whether the backup data file still exists on disk.
     *
     * @return {@code true} if the file is present
     */
    public boolean exists() {
        return dataFile.exists();
    }
}
