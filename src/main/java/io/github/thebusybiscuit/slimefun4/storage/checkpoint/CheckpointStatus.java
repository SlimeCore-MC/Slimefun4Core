package io.github.thebusybiscuit.slimefun4.storage.checkpoint;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

/**
 * Outcome of one half of a checkpoint.
 *
 * <p>A checkpoint holds two independent components — a storage backup and a database backup —
 * and each one records its own status. A checkpoint is only valid when neither component
 * failed, and only useful when at least one of them captured data.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public enum CheckpointStatus {

    /**
     * The component was captured and passed checksum verification.
     */
    OK("OK"),

    /**
     * There was nothing to capture, so no file was written.
     *
     * <p>This is not a failure. A server that has never used the legacy backend has no player
     * files, and a freshly created database has no rows.
     */
    SKIPPED("SKIPPED"),

    /**
     * The component could not be captured, or failed verification.
     */
    FAILED("FAILED");

    private final String displayName;

    CheckpointStatus(@Nonnull String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns a short human readable name for display in menus and the console.
     *
     * @return The display name of this status
     */
    @Nonnull
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns whether this status permits a checkpoint to be considered valid.
     *
     * @return {@code true} for {@link #OK} and {@link #SKIPPED}
     */
    public boolean isAcceptable() {
        return this != FAILED;
    }

    /**
     * Resolves a status from its serialized name.
     *
     * <p>An absent or unknown value is reported as {@link #FAILED} rather than assumed healthy,
     * because metadata that cannot be read is never a safe restore source.
     *
     * @param name The serialized enum name, may be {@code null}
     * @return The matching status, or {@link #FAILED}
     */
    @Nonnull
    public static CheckpointStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return FAILED;
        }

        for (CheckpointStatus status : values()) {
            if (status.name().equalsIgnoreCase(name.trim())) {
                return status;
            }
        }

        return FAILED;
    }
}
