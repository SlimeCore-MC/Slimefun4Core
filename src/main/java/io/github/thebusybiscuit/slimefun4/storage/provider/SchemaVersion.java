package io.github.thebusybiscuit.slimefun4.storage.provider;

import com.google.common.annotations.Beta;

/**
 * Represents the schema version currently applied to a database.
 *
 * @param version   The integer schema version number (monotonically increasing)
 * @param appliedAt ISO-8601 timestamp string of when this version was applied
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record SchemaVersion(int version, String appliedAt) {

    /** Sentinel value returned when the schema version table does not exist yet. */
    public static final SchemaVersion NONE = new SchemaVersion(0, "never");

    /**
     * Returns whether this schema is at or above the required version.
     *
     * @param required The minimum required version
     * @return {@code true} if this version is at least {@code required}
     */
    public boolean isAtLeast(int required) {
        return version >= required;
    }
}
