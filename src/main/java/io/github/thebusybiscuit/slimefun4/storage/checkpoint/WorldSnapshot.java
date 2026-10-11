package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.storage.backup.WorldBackup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * One world backup directory on disk, paired with its parsed metadata.
 *
 * <p>World backups are a separate pool from checkpoints. Each lives under
 * {@code slimefun-checkpoints/worlds/world-NNN/} and holds a single {@code worlds.zip} together
 * with a {@code metadata.json} describing the captured world folders. They are never mixed with a
 * checkpoint and have their own retention.
 *
 * @param directory       The {@code world-NNN} directory
 * @param id              The snapshot id, which is also the directory name
 * @param sequence        Numeric part of the id, used for ordering and the next id
 * @param createdAt       When the snapshot was captured, UTC
 * @param slimefunVersion Slimefun version at capture time
 * @param serverVersion   Server version at capture time
 * @param worlds          The captured world folder names
 * @param archive         The archive component, describing {@code worlds.zip}
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record WorldSnapshot(
    @Nonnull File directory,
    @Nonnull String id,
    int sequence,
    @Nonnull Instant createdAt,
    @Nonnull String slimefunVersion,
    @Nonnull String serverVersion,
    @Nonnull List<String> worlds,
    @Nonnull CheckpointComponent archive
) {

    /**
     * Name of the metadata document inside a world snapshot directory.
     */
    public static final String METADATA_FILE = "metadata.json";

    /**
     * Returns the world archive, or {@code null} when this snapshot captured nothing.
     *
     * @return The {@code worlds.zip} file, or {@code null}
     */
    @Nullable
    public File archiveFile() {
        if (!archive.isPresent()) {
            return null;
        }

        return new File(directory, WorldBackup.ARCHIVE_FILE);
    }

    /**
     * Returns whether the archive is present on disk and safe to restore.
     *
     * @return {@code true} when the snapshot can be attempted as a restore source
     */
    public boolean isRestorable() {
        File file = archiveFile();
        return archive.isPresent() && file != null && file.isFile();
    }

    /**
     * Returns the on-disk size recorded for the archive.
     *
     * @return The number of bytes recorded in the metadata
     */
    public long totalBytes() {
        return archive.bytes();
    }
}
