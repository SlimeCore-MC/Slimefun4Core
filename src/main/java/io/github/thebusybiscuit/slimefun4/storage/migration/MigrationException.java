package io.github.thebusybiscuit.slimefun4.storage.migration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.UUID;

/**
 * Signals that one element could not be converted and that the migration must stop.
 *
 * <p>A migration never skips. Both backends already drop an unreadable element silently
 * when loading player data, which is exactly what makes a silent migration dangerous, so
 * every failure here carries enough context for an owner to find and fix the offending
 * value: the player it belongs to, the section it was found in, and the column or YAML
 * path it sits at.
 *
 * @author SlimefunCore Contributors
 */
public class MigrationException extends Exception {

    private static final long serialVersionUID = 4630019928736154217L;

    private final UUID uuid;
    private final String section;
    private final String path;
    private final String reason;

    /**
     * @param message What went wrong
     */
    public MigrationException(@Nonnull String message) {
        this(null, null, null, message, null);
    }

    /**
     * @param message What went wrong
     * @param cause   The original failure
     */
    public MigrationException(@Nonnull String message, @Nullable Throwable cause) {
        this(null, null, null, message, cause);
    }

    /**
     * @param uuid    The player the element belongs to
     * @param section The section it was found in, such as {@code backpacks}
     * @param path    The column or YAML path of the element
     * @param message What went wrong
     */
    public MigrationException(
        @Nullable UUID uuid,
        @Nullable String section,
        @Nullable String path,
        @Nonnull String message
    ) {
        this(uuid, section, path, message, null);
    }

    /**
     * @param uuid    The player the element belongs to
     * @param section The section it was found in, such as {@code backpacks}
     * @param path    The column or YAML path of the element
     * @param message What went wrong
     * @param cause   The original failure
     */
    public MigrationException(
        @Nullable UUID uuid,
        @Nullable String section,
        @Nullable String path,
        @Nonnull String message,
        @Nullable Throwable cause
    ) {
        super(compose(uuid, section, path, message), cause);

        this.uuid = uuid;
        this.section = section;
        this.path = path;
        this.reason = message;
    }

    /**
     * @return The player the element belongs to, or {@code null} if the failure is not player specific
     */
    @Nullable
    public UUID getUuid() {
        return uuid;
    }

    /**
     * @return The section the element was found in, or {@code null}
     */
    @Nullable
    public String getSection() {
        return section;
    }

    /**
     * @return The column or YAML path of the element, or {@code null}
     */
    @Nullable
    public String getPath() {
        return path;
    }

    /**
     * @return The failure text without the location prefix
     */
    @Nonnull
    public String getReason() {
        return reason;
    }

    @Nonnull
    private static String compose(
        @Nullable UUID uuid,
        @Nullable String section,
        @Nullable String path,
        @Nonnull String message
    ) {
        StringBuilder sb = new StringBuilder();

        if (uuid != null) {
            sb.append("player ").append(uuid);
        }

        if (section != null) {
            sb.append(sb.length() == 0 ? "" : ", ").append("section ").append(section);
        }

        if (path != null) {
            sb.append(sb.length() == 0 ? "" : ", ").append("at ").append(path);
        }

        return sb.length() == 0 ? message : sb.append(": ").append(message).toString();
    }
}
