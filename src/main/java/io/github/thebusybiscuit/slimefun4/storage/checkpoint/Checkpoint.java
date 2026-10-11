package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;

/**
 * One checkpoint directory on disk, paired with its parsed metadata.
 *
 * <p>The layout is fixed and is the same for every provider:
 *
 * <pre>
 * slimefun-checkpoints/
 *     checkpoint-001/
 *         storage/storage.zip
 *         database/database-backup.*
 *         metadata.json
 * </pre>
 *
 * <p>The {@code storage} half holds the file-based Slimefun data tree. The {@code database}
 * half holds database-owned player data only. Neither ever holds Minecraft world data.
 *
 * @param directory The {@code checkpoint-NNN} directory
 * @param metadata  The parsed {@code metadata.json}
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record Checkpoint(@Nonnull File directory, @Nonnull CheckpointMetadata metadata) {

    /**
     * Name of the subdirectory holding the storage archive.
     */
    public static final String STORAGE_DIRECTORY = "storage";

    /**
     * Name of the subdirectory holding the database backup.
     */
    public static final String DATABASE_DIRECTORY = "database";

    /**
     * Name of the metadata document inside a checkpoint directory.
     */
    public static final String METADATA_FILE = "metadata.json";

    /**
     * Fixed name of the storage archive inside {@link #STORAGE_DIRECTORY}.
     */
    public static final String STORAGE_FILE = "storage.zip";

    /**
     * Base name of the database backup inside {@link #DATABASE_DIRECTORY}, without extension.
     */
    public static final String DATABASE_FILE_BASE = "database-backup";

    /**
     * Returns the checkpoint id, which is also the directory name.
     *
     * @return The id, for example {@literal "checkpoint-001"}
     */
    @Nonnull
    public String id() {
        return metadata.checkpointId();
    }

    /**
     * Returns the numeric part of the id.
     *
     * @return The sequence number
     */
    public int sequence() {
        return metadata.sequence();
    }

    /**
     * Returns the metadata document location.
     *
     * @return The {@code metadata.json} file
     */
    @Nonnull
    public File metadataFile() {
        return new File(directory, METADATA_FILE);
    }

    /**
     * Returns the storage archive, or {@code null} when this checkpoint captured no storage.
     *
     * @return The storage archive file, or {@code null}
     */
    @Nullable
    public File storageFile() {
        return componentFile(STORAGE_DIRECTORY, metadata.storage());
    }

    /**
     * Returns the database backup, or {@code null} when this checkpoint captured no database.
     *
     * @return The database backup file, or {@code null}
     */
    @Nullable
    public File databaseFile() {
        return componentFile(DATABASE_DIRECTORY, metadata.database());
    }

    /**
     * Returns whether every captured half is still present on disk and the metadata is valid.
     *
     * @return {@code true} when this checkpoint can be attempted as a restore source
     */
    public boolean isRestorable() {
        return isRestorable(RestoreScope.BOTH);
    }

    /**
     * Returns whether the requested half or halves are present and safe to restore.
     *
     * @param scope The restore scope to validate
     * @return {@code true} when every requested half can be restored
     */
    public boolean isRestorable(@Nonnull RestoreScope scope) {
        if (metadata.formatVersion() > CheckpointMetadata.CURRENT_FORMAT_VERSION) {
            return false;
        }

        if (scope == RestoreScope.STORAGE_ONLY) {
            return isComponentRestorable(metadata.storage(), storageFile());
        }

        if (scope == RestoreScope.DATABASE_ONLY) {
            return isComponentRestorable(metadata.database(), databaseFile());
        }

        return metadata.isValid()
            && isAcceptableComponent(metadata.storage(), storageFile())
            && isAcceptableComponent(metadata.database(), databaseFile());
    }

    private static boolean isComponentRestorable(
            @Nonnull CheckpointComponent component, @Nullable File file) {
        return component.isPresent() && file != null && file.isFile();
    }

    private static boolean isAcceptableComponent(
            @Nonnull CheckpointComponent component, @Nullable File file) {
        return !component.isPresent() || file != null && file.isFile();
    }

    /**
     * Returns the combined on-disk size of both halves.
     *
     * @return The total number of bytes recorded in the metadata
     */
    public long totalBytes() {
        return metadata.storage().bytes() + metadata.database().bytes();
    }

    @Nullable
    private File componentFile(@Nonnull String subdirectory, @Nonnull CheckpointComponent component) {
        if (component.fileName().isEmpty()) {
            return null;
        }

        return new File(new File(directory, subdirectory), component.fileName());
    }
}
