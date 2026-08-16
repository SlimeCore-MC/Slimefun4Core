package io.github.thebusybiscuit.slimefun4.core.services;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.plugin.Plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.bakedlibs.dough.updater.BlobBuildUpdater;
import io.github.bakedlibs.dough.updater.PluginUpdater;
import io.github.bakedlibs.dough.versions.PrefixedVersion;
import io.github.thebusybiscuit.slimefun4.api.SlimefunBranch;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.JsonUtils;

/**
 * This Class represents our {@link PluginUpdater} Service.
 * Official builds use blob.build for auto-download updates.
 * Unofficial builds use a configurable Modrinth project check with optional auto-download.
 *
 * @author TheBusyBiscuit
 */
public class UpdaterService {

    private static final String DEFAULT_MODRINTH_PROJECT = "slimefuncore";
    private static final String USER_AGENT = "SlimefunCoreV4.0-Updater";
    private static final String MODRINTH_VERSION_ENDPOINT = "https://api.modrinth.com/v2/project/%s/version?game_versions=%s&loaders=%s";
    private static final String MODRINTH_VERSION_ENDPOINT_NO_GAME_FILTER = "https://api.modrinth.com/v2/project/%s/version?loaders=%s";
    private static final Pattern BUILD_NUMBER_PATTERN = Pattern.compile("(\\d{4})(?=\\.jar$)");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final Slimefun plugin;
    private final File pluginFile;
    private final PluginUpdater<PrefixedVersion> updater;
    private final SlimefunBranch branch;
    private volatile @Nullable ReleaseInfo latestRelease;
    private volatile @Nullable String lastCheckError;
    private volatile boolean lastCheckCompleted;

    public UpdaterService(@Nonnull Slimefun plugin, @Nonnull String version, @Nonnull File file) {
        this.plugin = plugin;
        this.pluginFile = file;

        BlobBuildUpdater autoUpdater = null;

        if (version.contains("UNOFFICIAL")) {
            branch = SlimefunBranch.UNOFFICIAL;
        } else if (version.startsWith("Dev - ")) {
            try {
                autoUpdater = new BlobBuildUpdater(plugin, file, "Slimefun4", "Dev");
            } catch (Exception x) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create AutoUpdater", x);
            }

            branch = SlimefunBranch.DEVELOPMENT;
        } else if (version.startsWith("RC - ")) {
            try {
                autoUpdater = new BlobBuildUpdater(plugin, file, "Slimefun4", "RC");
            } catch (Exception x) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create AutoUpdater", x);
            }

            branch = SlimefunBranch.STABLE;
        } else {
            branch = SlimefunBranch.UNKNOWN;
        }

        this.updater = autoUpdater;
    }

    public @Nonnull SlimefunBranch getBranch() {
        return branch;
    }

    public int getBuildNumber() {
        if (updater != null) {
            PrefixedVersion version = updater.getCurrentVersion();
            return version.getVersionNumber();
        }

        return -1;
    }

    public int getLatestVersion() {
        if (updater != null && updater.getLatestVersion().isDone()) {
            try {
                PrefixedVersion version = updater.getLatestVersion().get();
                return version.getVersionNumber();
            } catch (InterruptedException | ExecutionException e) {
                return -1;
            }
        }

        return -1;
    }

    public boolean isLatestVersion() {
        if (branch == SlimefunBranch.UNOFFICIAL) {
            Integer currentBuild = parseBuildNumber(pluginFile.getName());
            Integer latestBuild = latestRelease != null ? latestRelease.buildNumber() : null;
            return currentBuild == null || latestBuild == null || currentBuild >= latestBuild;
        }

        if (getBuildNumber() == -1 || getLatestVersion() == -1) {
            return true;
        }

        return getBuildNumber() == getLatestVersion();
    }

    /**
     * Start updater logic.
     * Official builds use blob.build.
     * Unofficial builds query Modrinth and optionally stage the new jar into /plugins/update.
     */
    public void start() {
        if (updater != null) {
            updater.start();
            return;
        }

        printBorder();
        plugin.getLogger().log(Level.WARNING, "It looks like you are using an unofficially modified build of Slimefun!");
        plugin.getLogger().log(Level.WARNING, "Modrinth release checks are enabled for this unofficial build.");
        plugin.getLogger().log(Level.WARNING, "Project: {0}", getModrinthProject());
        plugin.getLogger().log(Level.WARNING, "Detected game version: {0}", getCurrentGameVersion());
        plugin.getLogger().log(Level.WARNING, "Auto-download updates: {0}", isAutoDownloadEnabled() ? "enabled" : "disabled");
        plugin.getLogger().log(Level.WARNING, "Do not report bugs encountered in this Version of Slimefun to any official sources.");
        printBorder();

        checkUnofficialReleaseAsync();
    }

    public boolean isEnabled() {
        return Slimefun.getCfg().getBoolean("options.auto-update");
    }

    public void disable() {
        printBorder();
        plugin.getLogger().log(Level.WARNING, "It looks like you have disabled update checks for Slimefun!");
        plugin.getLogger().log(Level.WARNING, "Update checks help you stay current with fixes and releases.");
        plugin.getLogger().log(Level.WARNING, "We respect your decision.");

        if (branch != SlimefunBranch.STABLE) {
            plugin.getLogger().log(Level.WARNING, "If you are just scared of Slimefun breaking, then please consider using a \"stable\" build instead of disabling update checks.");
        }

        printBorder();
    }

    private void printBorder() {
        plugin.getLogger().log(Level.WARNING, "#######################################################");
    }

    private void checkUnofficialReleaseAsync() {
        Slimefun.getThreadService().newThread(plugin, "UpdaterService#startupCheck", () -> checkUnofficialRelease(true));
    }

    public void checkNow(@Nullable Consumer<UpdateStatus> callback) {
        if (updater != null) {
            UpdateStatus status = getStatus();

            if (callback != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(status));
            }

            return;
        }

        Slimefun.getThreadService().newThread(plugin, "UpdaterService#checkNow", () -> {
            checkUnofficialRelease(false);
            UpdateStatus status = getStatus();

            if (callback != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(status));
            }
        });
    }

    public @Nonnull UpdateStatus getStatus() {
        Integer currentBuild = parseBuildNumber(pluginFile.getName());
        Integer latestBuild = latestRelease != null ? latestRelease.buildNumber() : null;
        boolean unofficialUpdateAvailable = currentBuild != null && latestBuild != null && latestBuild > currentBuild;

        String currentVersion = branch == SlimefunBranch.UNOFFICIAL ? pluginFile.getName() : String.valueOf(getBuildNumber());
        String latestVersion = branch == SlimefunBranch.UNOFFICIAL
            ? latestRelease != null ? latestRelease.versionNumber() : null
            : getLatestVersion() == -1 ? null : String.valueOf(getLatestVersion());

        boolean updateAvailable = branch == SlimefunBranch.UNOFFICIAL
            ? unofficialUpdateAvailable
            : (getBuildNumber() != -1 && getLatestVersion() != -1 && getLatestVersion() > getBuildNumber());

        return new UpdateStatus(
            branch,
            isEnabled(),
            isAutoDownloadEnabled(),
            lastCheckCompleted || updater != null,
            updateAvailable,
            currentVersion,
            latestVersion,
            latestRelease != null ? latestRelease.assetName() : null,
            latestRelease != null ? latestRelease.projectUrl() : null,
            lastCheckError
        );
    }

    private void checkUnofficialRelease(boolean announceToConsole) {
        try {
            ReleaseInfo latestRelease = fetchLatestModrinthRelease();
            this.latestRelease = latestRelease;
            this.lastCheckCompleted = true;
            this.lastCheckError = null;
            Integer currentBuild = parseBuildNumber(pluginFile.getName());

            if (latestRelease == null) {
                if (announceToConsole) {
                    plugin.getLogger().log(Level.INFO, "Could not determine the latest Modrinth release for project \"{0}\".", getModrinthProject());
                }
                return;
            }

            if (currentBuild == null || latestRelease.buildNumber() == null) {
                if (announceToConsole) {
                    plugin.getLogger().log(Level.INFO, "Could not compare build numbers for automatic updates.");
                    plugin.getLogger().log(Level.INFO, "Current jar: {0}", pluginFile.getName());
                    plugin.getLogger().log(Level.INFO, "Latest Modrinth version: {0}", latestRelease.versionNumber());
                    plugin.getLogger().log(Level.INFO, "Project page: {0}", latestRelease.projectUrl());
                }
                return;
            }

            if (latestRelease.buildNumber() > currentBuild) {
                if (announceToConsole) {
                    printBorder();
                    plugin.getLogger().log(Level.WARNING, "A newer SlimefunCore release is available on Modrinth.");
                    plugin.getLogger().log(Level.WARNING, "Current build: {0}", pluginFile.getName());
                    plugin.getLogger().log(Level.WARNING, "Latest asset: {0}", latestRelease.assetName());
                    plugin.getLogger().log(Level.WARNING, "Latest version: {0}", latestRelease.versionNumber());
                    plugin.getLogger().log(Level.WARNING, "Published: {0}", latestRelease.publishedAt());
                    plugin.getLogger().log(Level.WARNING, "Project page: {0}", latestRelease.projectUrl());
                }

                if (isAutoDownloadEnabled()) {
                    if (downloadLatestRelease(latestRelease) && announceToConsole) {
                        plugin.getLogger().log(Level.WARNING, "The new jar was downloaded into the update folder and will apply on the next restart.");
                    }
                } else if (announceToConsole) {
                    plugin.getLogger().log(Level.WARNING, "Auto-download is disabled. Enable options.auto-download-update to stage updates automatically.");
                }

                if (announceToConsole) {
                    printBorder();
                }
                return;
            }

            if (announceToConsole) {
                plugin.getLogger().log(Level.INFO, "SlimefunCore is already on the latest Modrinth release.");
                plugin.getLogger().log(Level.INFO, "Current build: {0}", pluginFile.getName());
                plugin.getLogger().log(Level.INFO, "Latest asset: {0}", latestRelease.assetName());
                plugin.getLogger().log(Level.INFO, "Latest version: {0}", latestRelease.versionNumber());
            }
        } catch (IOException | InterruptedException x) {
            this.lastCheckCompleted = true;
            this.lastCheckError = x.getMessage();
            if (announceToConsole) {
                plugin.getLogger().log(Level.WARNING, "Failed to check unofficial Modrinth releases: " + x.getMessage());
            }

            if (x instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private @Nullable ReleaseInfo fetchLatestModrinthRelease() throws IOException, InterruptedException {
        // First try with game_versions filter
        ReleaseInfo result = fetchFromModrinth(createModrinthVersionUri());

        if (result == null) {
            // Fallback: retry without game_versions filter in case the version string
            // from Bukkit.getBukkitVersion() doesn't exactly match what's on Modrinth.
            plugin.getLogger().log(Level.INFO, "\u2726 No Modrinth releases matched game version \"{0}\", retrying without version filter...", getCurrentGameVersion());
            result = fetchFromModrinth(createModrinthVersionUriNoGameFilter());
        }

        return result;
    }

    private @Nullable ReleaseInfo fetchFromModrinth(@Nonnull URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 400) {
            return null;
        }

        JsonArray versions = JsonUtils.parseString(response.body()).getAsJsonArray();
        if (versions.isEmpty()) {
            return null;
        }

        // Scan ALL versions and pick the one with the highest build number.
        ReleaseInfo best = null;

        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject version = element.getAsJsonObject();
            String versionNumber = getAsString(version, "version_number");
            String versionId = getAsString(version, "id");
            String publishedAtRaw = getAsString(version, "date_published");
            JsonArray files = version.has("files") && version.get("files").isJsonArray() ? version.getAsJsonArray("files") : null;

            if (versionNumber == null || versionId == null || publishedAtRaw == null || files == null) {
                continue;
            }

            JsonObject selectedFile = selectModrinthJar(files);
            if (selectedFile == null) {
                continue;
            }

            String assetName = getAsString(selectedFile, "filename");
            String downloadUrl = getAsString(selectedFile, "url");

            if (assetName == null || downloadUrl == null) {
                continue;
            }

            Integer buildNumber = parseBuildNumber(assetName);
            if (buildNumber == null) {
                continue;
            }

            // Keep only the release with the highest build number.
            if (best == null || best.buildNumber() == null || buildNumber > best.buildNumber()) {
                String projectUrl = "https://modrinth.com/plugin/" + getModrinthProject() + "/version/" + versionId;
                best = new ReleaseInfo(versionNumber, projectUrl, Instant.parse(publishedAtRaw), assetName, downloadUrl, buildNumber);
            }
        }

        return best;
    }

    private @Nullable JsonObject selectModrinthJar(@Nonnull JsonArray files) {
        JsonObject fallback = null;

        for (JsonElement fileElement : files) {
            if (!fileElement.isJsonObject()) {
                continue;
            }

            JsonObject file = fileElement.getAsJsonObject();
            String fileName = getAsString(file, "filename");

            if (fileName == null || !fileName.endsWith(".jar") || fileName.endsWith("-sources.jar")) {
                continue;
            }

            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                return file;
            }

            if (fallback == null) {
                fallback = file;
            }
        }

        return fallback;
    }

    private @Nullable Integer parseBuildNumber(@Nonnull String fileName) {
        Matcher matcher = BUILD_NUMBER_PATTERN.matcher(fileName);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException x) {
            return null;
        }
    }

    private @Nullable String getAsString(@Nonnull JsonObject object, @Nonnull String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }

        return object.get(key).getAsString();
    }

    private @Nonnull URI createModrinthVersionUri() {
        String project = getModrinthProject();
        String gameVersions = encodeJsonArray(getCurrentGameVersion());
        String loaders = encodeJsonArray("paper", "purpur", "spigot", "bukkit");
        return URI.create(String.format(MODRINTH_VERSION_ENDPOINT, project, gameVersions, loaders));
    }

    private @Nonnull URI createModrinthVersionUriNoGameFilter() {
        String project = getModrinthProject();
        String loaders = encodeJsonArray("paper", "purpur", "spigot", "bukkit");
        return URI.create(String.format(MODRINTH_VERSION_ENDPOINT_NO_GAME_FILTER, project, loaders));
    }

    private @Nonnull String encodeJsonArray(@Nonnull String... values) {
        StringBuilder builder = new StringBuilder("[");

        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }

            builder.append('"').append(values[i]).append('"');
        }

        builder.append(']');
        return URLEncoder.encode(builder.toString(), StandardCharsets.UTF_8);
    }

    private @Nonnull String getCurrentGameVersion() {
        String bukkitVersion = plugin.getServer().getBukkitVersion();
        int separator = bukkitVersion.indexOf('-');
        return separator >= 0 ? bukkitVersion.substring(0, separator) : bukkitVersion;
    }

    private @Nonnull String getModrinthProject() {
        String project = Slimefun.getCfg().getString("options.modrinth-project");
        return project == null || project.isBlank() ? DEFAULT_MODRINTH_PROJECT : project.trim();
    }

    private boolean isAutoDownloadEnabled() {
        return Slimefun.getCfg().getBoolean("options.auto-download-update");
    }

    private boolean downloadLatestRelease(@Nonnull ReleaseInfo latestRelease) {
        try {
            File updateFolder = plugin.getServer().getUpdateFolderFile();

            if (!updateFolder.exists() && !updateFolder.mkdirs()) {
                plugin.getLogger().log(Level.WARNING, "Could not create the server update folder: {0}", updateFolder.getAbsolutePath());
                return false;
            }

            Path target = updateFolder.toPath().resolve(latestRelease.assetName());
            Path temporary = Files.createTempFile(updateFolder.toPath(), "slimefun-update-", ".jar");

            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(latestRelease.downloadUrl()))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

                HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(temporary));

                if (response.statusCode() >= 400) {
                    plugin.getLogger().log(Level.WARNING, "Failed to download the latest Modrinth release asset. Response code: {0}", response.statusCode());
                    Files.deleteIfExists(temporary);
                    return false;
                }

                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | InterruptedException x) {
            plugin.getLogger().log(Level.WARNING, "Failed to download the latest unofficial Modrinth release: " + x.getMessage());

            if (x instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            return false;
        }
    }

    private record ReleaseInfo(@Nonnull String versionNumber, @Nonnull String projectUrl, @Nonnull Instant publishedAt,
                               @Nonnull String assetName, @Nonnull String downloadUrl, @Nullable Integer buildNumber) {}

    public record UpdateStatus(
        @Nonnull SlimefunBranch branch,
        boolean checksEnabled,
        boolean autoDownloadEnabled,
        boolean checked,
        boolean updateAvailable,
        @Nonnull String currentVersion,
        @Nullable String latestVersion,
        @Nullable String latestAsset,
        @Nullable String projectUrl,
        @Nullable String lastError
    ) {}
}
