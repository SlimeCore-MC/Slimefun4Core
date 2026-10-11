package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import javax.annotation.Nonnull;

/**
 * Selects which half of a checkpoint is being restored.
 *
 * <p>A checkpoint holds two independent halves: a storage archive of the file-based Slimefun data
 * tree and a database export of database-owned player data. Both can be restored together or
 * independently.
 *
 * @author SlimefunCore Contributors
 */
public enum RestoreScope {

    /**
     * Restore only the file-based storage half (block data, inventories, chunks).
     */
    STORAGE_ONLY("storage"),

    /**
     * Restore only the database half (player researches, backpacks, waypoints).
     */
    DATABASE_ONLY("database"),

    /**
     * Restore both halves together (legacy behaviour).
     */
    BOTH("both"),

    /**
     * Restore only Minecraft world folders (overworld, nether, end).
     *
     * <p>World data is not part of a checkpoint. This scope is used only by the separate world
     * backup pool, whose restore stages the files and applies them on the next restart.
     */
    WORLD_ONLY("world");

    private final String displayName;

    RestoreScope(@Nonnull String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a lowercase name safe for log messages and command output.
     *
     * @return The display name
     */
    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whether this scope includes the storage half.
     *
     * @return {@code true} for {@link #STORAGE_ONLY} and {@link #BOTH}
     */
    public boolean includesStorage() {
        return this == STORAGE_ONLY || this == BOTH;
    }

    /**
     * Whether this scope includes the database half.
     *
     * @return {@code true} for {@link #DATABASE_ONLY} and {@link #BOTH}
     */
    public boolean includesDatabase() {
        return this == DATABASE_ONLY || this == BOTH;
    }

    /**
     * Whether this scope restores Minecraft world folders.
     *
     * @return {@code true} for {@link #WORLD_ONLY}
     */
    public boolean includesWorld() {
        return this == WORLD_ONLY;
    }
}
