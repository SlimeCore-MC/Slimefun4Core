package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identifies the physical shape of a backup archive.
 *
 * <p>A backup taken from a SQL-backed provider is a provider-agnostic JSON row
 * dump. A backup taken from the legacy YAML file storage is a byte-exact ZIP of
 * the live file tree, because there is no SQL connection to dump from and because
 * re-serializing {@code ItemStack} data would risk losing information.
 *
 * <p>A block archive is a byte-exact ZIP of the block sections alone. Block data is
 * never stored in a database, so it needs its own archive on every provider.
 *
 * <p>A player archive is a byte-exact ZIP of the player-owned sections alone. It is the
 * legacy backend's equivalent of a SQL row dump and carries the same data a SQL dump
 * carries: researches, backpacks, backpack items and waypoints.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public enum BackupFormat {

    /**
     * A JSON row dump produced from a SQL provider.
     */
    SQL_JSON("SQL row dump"),

    /**
     * A byte-exact ZIP archive of the legacy YAML file tree.
     */
    LEGACY_FILES("File archive"),

    /**
     * A byte-exact ZIP archive of the block data tree only, taken on any provider.
     */
    BLOCK_FILES("Block archive"),

    /**
     * A byte-exact ZIP archive of the player data tree only, taken on the legacy backend.
     */
    PLAYER_FILES("Player archive"),

    /**
     * A byte-exact ZIP archive of the Minecraft world folders (overworld, nether, end).
     *
     * <p>This is never part of a checkpoint. World data is captured by the separate world
     * backup pool, which has its own retention and is applied on restart before the worlds load.
     */
    WORLD_FILES("World archive");

    private final String displayName;

    BackupFormat(@Nonnull String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a short human readable name for display in menus and the console.
     *
     * @return The display name of this format
     */
    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Resolves a format from its serialized name, falling back to
     * {@link #SQL_JSON} for absent or unknown values so that metadata written by
     * older builds keeps parsing.
     *
     * @param name The serialized enum name, may be {@code null}
     * @return The matching format, or {@link #SQL_JSON}
     */
    @Nonnull
    public static BackupFormat fromName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return SQL_JSON;
        }

        for (BackupFormat format : values()) {
            if (format.name().equalsIgnoreCase(name.trim())) {
                return format;
            }
        }

        return SQL_JSON;
    }
}
