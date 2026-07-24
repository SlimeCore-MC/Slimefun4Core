package io.github.thebusybiscuit.slimefun4.core.addons;

import java.util.List;

import javax.annotation.Nonnull;

import org.apache.commons.lang.Validate;

import io.github.thebusybiscuit.slimefun4.api.MinecraftVersion;

/**
 * Resolves the SF4 addon build tag for the running Minecraft version.
 */
public final class VersionTagResolver {

    /**
     * Addon release tags for the Minecraft 26.x era, newest drop first.
     * The resolver tries each tag in order until one matches an actual release;
     * listing them newest-to-oldest means servers on 26.2 find 26.2 immediately
     * while servers on 26.1 fall through to their matching tag.
     */
    private static final List<String> ADDON_TAGS_26   = List.of("26.2", "26.1.2", "26.1.1", "26.1");
    private static final List<String> ADDON_TAGS_1_21 = List.of("1.21.11", "1.21.1");

    private VersionTagResolver() {}

    public static @Nonnull String resolveTag(@Nonnull MinecraftVersion mc) {
        return resolveTags(mc).get(0);
    }

    public static @Nonnull List<String> resolveTags(@Nonnull MinecraftVersion mc) {
        Validate.notNull(mc, "Minecraft version must not be null");

        // 26.x era — checked first so it takes priority over the 1.21 branch
        // (MINECRAFT_26 has a higher ordinal than MINECRAFT_1_21, so isAtLeast
        // on a 26.x server would also satisfy the 1.21 check below).
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_26)) {
            return ADDON_TAGS_26;
        }
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_21)) {
            return ADDON_TAGS_1_21;
        }
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_20)) {
            return List.of("1.20.4");
        }
        if (mc.isAtLeast(MinecraftVersion.MINECRAFT_1_19)) {
            return List.of("1.19.4");
        }

        return List.of("1.21.11");
    }

    public static @Nonnull String normalizeSlimefunVersion(@Nonnull String version) {
        Validate.notEmpty(version, "Slimefun version must not be empty");

        int suffixStart = version.indexOf('-');
        return suffixStart > 0 ? version.substring(0, suffixStart) : version;
    }
}
