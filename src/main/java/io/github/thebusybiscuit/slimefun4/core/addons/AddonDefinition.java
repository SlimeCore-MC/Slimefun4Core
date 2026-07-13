package io.github.thebusybiscuit.slimefun4.core.addons;

<<<<<<< Updated upstream
import java.util.Locale;
=======
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
>>>>>>> Stashed changes

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;

/**
 * Immutable configuration for a managed addon.
 */
public record AddonDefinition(
    @Nonnull String key,
    @Nonnull String name,
    boolean enabled,
    boolean download,
    boolean autoUpdate,
    @Nonnull String version,
    @Nonnull String url,
    @Nullable String sha256,
    @Nullable String checksumUrl,
    @Nullable String apiUrl
) {

    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\\d+");

    public AddonDefinition {
        Validate.notEmpty(key, "Addon key must not be empty");
        Validate.notEmpty(name, "Addon name must not be empty");
<<<<<<< Updated upstream
        Validate.notEmpty(version, "Addon version must not be empty");
        Validate.notEmpty(url, "Addon url must not be empty");
    }

    public boolean tracksLatestVersion() {
        return "latest".equalsIgnoreCase(version);
=======
        Validate.notNull(versionUrls, "Version URLs must not be null");
        versionUrls = Collections.unmodifiableMap(new LinkedHashMap<>(versionUrls));
    }

    public boolean tracksLatestVersion() {
        return "latest".equalsIgnoreCase(version());
    }

    /**
     * Returns the URL for the given SF4 version tag.
     *
     * @param versionTag
     *            The SF4 version tag
     *
     * @return The configured URL, if present and non-empty
     */
    public @Nonnull Optional<String> urlForVersion(@Nonnull String versionTag) {
        Validate.notEmpty(versionTag, "Version tag must not be empty");

        String url = versionUrls.get(versionTag);
        return url == null || url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    /**
     * Returns the best URL for the given Minecraft version.
     *
     * @param mcVersion
     *            The Minecraft version
     *
     * @return The configured URL for this Minecraft version, if present
     */
    public @Nonnull Optional<String> resolveUrlForMinecraftVersion(@Nonnull MinecraftVersion mcVersion) {
        Validate.notNull(mcVersion, "Minecraft version must not be null");

        for (String tag : candidateTags(mcVersion)) {
            Optional<String> url = urlForVersion(tag);
            if (url.isPresent()) {
                return url;
            }
        }

        Optional<String> closestUrl = closestVersionUrl(VersionTagResolver.resolveTag(mcVersion));
        if (closestUrl.isPresent()) {
            return closestUrl;
        }

        return versionUrls.values().stream()
            .filter(url -> url != null && !url.isBlank())
            .findFirst();
    }

    public @Nonnull String version() {
        for (String tag : candidateTags(Slimefun.getMinecraftVersion())) {
            if (versionUrls.containsKey(tag) && !versionUrls.getOrDefault(tag, "").isBlank()) {
                return tag;
            }
        }

        String tag = VersionTagResolver.resolveTag(Slimefun.getMinecraftVersion());
        return closestVersionKey(tag).orElseGet(() -> versionUrls.keySet().stream().findFirst().orElse("latest"));
    }

    public @Nonnull String url() {
        return resolveUrlForMinecraftVersion(Slimefun.getMinecraftVersion())
            .or(() -> versionUrls.values().stream().filter(value -> value != null && !value.isBlank()).findFirst())
            .orElse("");
>>>>>>> Stashed changes
    }

    public @Nonnull String repositoryFileName() {
        String sanitized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        return sanitized.endsWith(".jar") ? sanitized : sanitized + ".jar";
    }

    public @Nonnull AddonDefinition withEnabled(boolean value) {
        return new AddonDefinition(key, name, value, download, autoUpdate, version, url, sha256, checksumUrl, apiUrl);
    }

    public @Nonnull AddonDefinition withDownload(boolean value) {
        return new AddonDefinition(key, name, enabled, value, autoUpdate, version, url, sha256, checksumUrl, apiUrl);
    }

    public @Nonnull AddonDefinition withAutoUpdate(boolean value) {
        return new AddonDefinition(key, name, enabled, download, value, version, url, sha256, checksumUrl, apiUrl);
    }

    private @Nonnull Optional<String> closestVersionUrl(@Nonnull String targetVersion) {
        return closestVersionKey(targetVersion).map(versionUrls::get);
    }

    private @Nonnull List<String> candidateTags(@Nonnull MinecraftVersion mcVersion) {
        List<String> tags = new ArrayList<>();
        addCandidateTag(tags, Slimefun.getVersion());
        addCandidateTag(tags, VersionTagResolver.normalizeSlimefunVersion(Slimefun.getVersion()));
        VersionTagResolver.resolveTags(mcVersion).forEach(tag -> addCandidateTag(tags, tag));
        return tags;
    }

    private void addCandidateTag(@Nonnull List<String> tags, @Nonnull String tag) {
        if (!tag.isBlank() && !tags.contains(tag)) {
            tags.add(tag);
        }
    }

    private @Nonnull Optional<String> closestVersionKey(@Nonnull String targetVersion) {
        int[] target = parseVersionNumbers(targetVersion);
        if (target.length == 0) {
            return Optional.empty();
        }

        return versionUrls.entrySet().stream()
            .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .filter(entry -> hasSameMajorVersion(target, parseVersionNumbers(entry.getKey())))
            .max(Comparator.comparing(entry -> compareVersionDistance(target, parseVersionNumbers(entry.getKey()))))
            .map(Map.Entry::getKey);
    }

    private boolean hasSameMajorVersion(@Nonnull int[] target, @Nonnull int[] candidate) {
        return candidate.length > 0 && candidate[0] == target[0];
    }

    private int compareVersionDistance(@Nonnull int[] target, @Nonnull int[] candidate) {
        int score = 0;
        int max = Math.max(target.length, candidate.length);

        for (int i = 0; i < max; i++) {
            int targetPart = i < target.length ? target[i] : 0;
            int candidatePart = i < candidate.length ? candidate[i] : 0;
            if (candidatePart > targetPart) {
                return Integer.MIN_VALUE + candidatePart;
            }

            score = (score * 1000) + candidatePart;
        }

        return score;
    }

    private @Nonnull int[] parseVersionNumbers(@Nonnull String value) {
        Matcher matcher = VERSION_NUMBER_PATTERN.matcher(value);
        int[] numbers = new int[4];
        int count = 0;

        while (matcher.find() && count < numbers.length) {
            try {
                numbers[count++] = Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
                // Ignore oversized version parts.
            }
        }

        int[] parsed = new int[count];
        System.arraycopy(numbers, 0, parsed, 0, count);
        return parsed;
    }
}
