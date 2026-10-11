package io.github.thebusybiscuit.slimefun4.core.report;

import javax.annotation.Nullable;

public enum ReportCategory {
    BUG("Bug Report", "§c"),
    ADDON_CONFLICT("Addon Conflict", "§6"),
    GRIEFING("Griefing Report", "§4"),
    PERFORMANCE("Performance Issue", "§e"),
    AUTO_ERROR("Automatic Error Report", "§4"),
    OTHER("Other", "§7");

    private final String displayName;
    private final String color;

    ReportCategory(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }

    @Nullable
    public static ReportCategory fromString(String name) {
        if (name == null) {
            return null;
        }
        for (ReportCategory category : values()) {
            if (category.name().equalsIgnoreCase(name)) {
                return category;
            }
        }
        return null;
    }
}
