package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.Validate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Handles addonmanager configuration, cloud-sync settings and cache persistence.
 */
public final class ConfigManager {

    private static final String DEFAULT_REMOTE_URL = "https://raw.githubusercontent.com/rutwok-labs/SF4-Addons/main/Addons/java/core/api/config/addonmanager.yml";
    private static final String DEFAULT_CONFIG_CONTENT = """
        cloud-sync:
          enabled: true
          url: "https://raw.githubusercontent.com/rutwok-labs/SF4-Addons/main/Addons/java/core/api/config/addonmanager.yml"
          interval-minutes: 10
          fallback-to-local-on-empty: true
          fallback-to-local-on-failure: true

        addons: {}
        """;

    private final Slimefun plugin;
    private final Path configPath;
    private final Path remoteConfigPath;
    private final Path cachePath;
    private final Path repositoryDirectory;
    private final Path tempDirectory;
    private final Path backupDirectory;

    public ConfigManager(@Nonnull Slimefun plugin) {
        this.plugin = plugin;
        this.configPath = plugin.getDataFolder().toPath().resolve("addonmanager.yml");
        this.remoteConfigPath = plugin.getDataFolder().toPath().resolve("addonmanager-remote.yml");
        this.cachePath = plugin.getDataFolder().toPath().resolve("addonmanager-cache.yml");
        this.repositoryDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("repository");
        this.tempDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("tmp");
        this.backupDirectory = plugin.getDataFolder().toPath().resolve("addons").resolve("backups");
    }

    public synchronized void initialize() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.createDirectories(repositoryDirectory);
        Files.createDirectories(tempDirectory);
        Files.createDirectories(backupDirectory);

        if (Files.notExists(configPath)) {
            copyBundledConfigOrCreateDefault();
        }

        if (Files.notExists(cachePath)) {
            Files.createFile(cachePath);
        }
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadAddons() {
        YamlConfiguration localConfiguration = YamlConfiguration.loadConfiguration(configPath.toFile());
        RemoteSyncSettings syncSettings = parseSyncSettings(localConfiguration);

        if (syncSettings.enabled() && Files.exists(remoteConfigPath)) {
            try {
                Map<String, AddonDefinition> remoteAddons = loadAddonsFromFile(remoteConfigPath);
                if (!remoteAddons.isEmpty() || !syncSettings.fallbackToLocalOnEmpty()) {
                    return remoteAddons;
                }

                plugin.getLogger().warning("Remote addonmanager.yml is empty or invalid, falling back to the local addonmanager.yml.");
            } catch (RuntimeException x) {
                if (!syncSettings.fallbackToLocalOnFailure()) {
                    throw x;
                }

                plugin.getLogger().warning("Remote addonmanager.yml failed to load, falling back to the local addonmanager.yml: " + x.getMessage());
            }
        }

        return parseAddons(localConfiguration);
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadLocalAddons() {
        return parseAddons(YamlConfiguration.loadConfiguration(configPath.toFile()));
    }

    public synchronized @Nonnull Map<String, AddonDefinition> loadRemoteAddons() {
        if (Files.notExists(remoteConfigPath)) {
            return Map.of();
        }

        return loadAddonsFromFile(remoteConfigPath);
    }

    public synchronized @Nonnull Optional<AddonDefinition> getAddon(@Nonnull String key) {
        Validate.notEmpty(key, "Addon key must not be empty");
        return Optional.ofNullable(loadAddons().get(key.toLowerCase(Locale.ROOT)));
    }

    public synchronized void saveAddon(@Nonnull AddonDefinition definition) throws IOException {
        Validate.notNull(definition, "Addon definition must not be null");
        saveAddonToPath(configPath, definition);

        if (shouldPersistToRemoteConfig()) {
            saveAddonToPath(remoteConfigPath, definition);
        }
    }

    public synchronized @Nonnull RemoteSyncSettings getSyncSettings() {
        return parseSyncSettings(YamlConfiguration.loadConfiguration(configPath.toFile()));
    }

    public synchronized void saveRemoteConfig(@Nonnull String content) throws IOException {
        Validate.notNull(content, "Remote configuration content must not be null");
        Files.writeString(remoteConfigPath, content);
    }

    public synchronized @Nonnull Optional<RemoteSyncState> getRemoteSyncState() {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("remote-sync");

        if (section == null) {
            return Optional.empty();
        }

        String lastSyncedRaw = section.getString("last-synced");
        Instant lastSynced = lastSyncedRaw != null && !lastSyncedRaw.isBlank() ? Instant.parse(lastSyncedRaw) : null;
        return Optional.of(new RemoteSyncState(
            emptyToNull(section.getString("etag")),
            emptyToNull(section.getString("last-modified")),
            emptyToNull(section.getString("source-url")),
            emptyToNull(section.getString("last-status")),
            lastSynced
        ));
    }

    public synchronized void saveRemoteSyncState(@Nonnull RemoteSyncState state) throws IOException {
        Validate.notNull(state, "Remote sync state must not be null");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("remote-sync");

        if (section == null) {
            section = configuration.createSection("remote-sync");
        }

        section.set("etag", state.etag());
        section.set("last-modified", state.lastModified());
        section.set("source-url", state.sourceUrl());
        section.set("last-status", state.lastStatus());
        section.set("last-synced", state.lastSyncedAt() != null ? state.lastSyncedAt().toString() : null);
        configuration.save(cachePath.toFile());
    }

    public synchronized @Nonnull Optional<AddonCacheEntry> getCacheEntry(@Nonnull String key) {
        Validate.notEmpty(key, "Addon key must not be empty");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection section = configuration.getConfigurationSection("addons." + key);

        if (section == null) {
            return Optional.empty();
        }

        String updatedAt = section.getString("updated-at");
        Instant timestamp = updatedAt != null && !updatedAt.isBlank() ? Instant.parse(updatedAt) : null;
        return Optional.of(new AddonCacheEntry(
            emptyToNull(section.getString("version")),
            emptyToNull(section.getString("sha256")),
            emptyToNull(section.getString("etag")),
            emptyToNull(section.getString("last-modified")),
            emptyToNull(section.getString("source-url")),
            timestamp,
            section.getBoolean("verified", false)
        ));
    }

    public synchronized void saveCacheEntry(@Nonnull String key, @Nonnull AddonCacheEntry entry) throws IOException {
        Validate.notEmpty(key, "Addon key must not be empty");
        Validate.notNull(entry, "Addon cache entry must not be null");

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        ConfigurationSection root = configuration.getConfigurationSection("addons");

        if (root == null) {
            root = configuration.createSection("addons");
        }

        ConfigurationSection section = root.getConfigurationSection(key);

        if (section == null) {
            section = root.createSection(key);
        }

        section.set("version", entry.version());
        section.set("sha256", entry.sha256());
        section.set("etag", entry.etag());
        section.set("last-modified", entry.lastModified());
        section.set("source-url", entry.sourceUrl());
        section.set("updated-at", entry.updatedAt() != null ? entry.updatedAt().toString() : null);
        section.set("verified", entry.verified());
        configuration.save(cachePath.toFile());
    }

    public synchronized void removeCacheEntry(@Nonnull String key) throws IOException {
        Validate.notEmpty(key, "Addon key must not be empty");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(cachePath.toFile());
        configuration.set("addons." + key, null);
        configuration.save(cachePath.toFile());
    }

    public @Nonnull Path getRepositoryDirectory() {
        return repositoryDirectory;
    }

    public @Nonnull Path getTempDirectory() {
        return tempDirectory;
    }

    public @Nonnull Path getBackupDirectory() {
        return backupDirectory;
    }

    public @Nonnull Path getRemoteConfigPath() {
        return remoteConfigPath;
    }

    public @Nonnull Path resolveAddonPath(@Nonnull AddonDefinition definition) {
        return repositoryDirectory.resolve(definition.repositoryFileName());
    }

    public synchronized boolean addonJarExists(@Nonnull AddonDefinition definition) {
        return Files.exists(resolveAddonPath(definition));
    }

    public synchronized void deleteAddonJar(@Nonnull AddonDefinition definition) throws IOException {
        Files.deleteIfExists(resolveAddonPath(definition));
    }

    public synchronized @Nonnull Collection<File> getRepositoryJars() throws IOException {
        try (var stream = Files.list(repositoryDirectory)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .map(Path::toFile)
                .toList();
        }
    }

    private @Nonnull Map<String, AddonDefinition> loadAddonsFromFile(@Nonnull Path file) {
        return parseAddons(YamlConfiguration.loadConfiguration(file.toFile()));
    }

    private @Nonnull Map<String, AddonDefinition> parseAddons(@Nonnull YamlConfiguration configuration) {
        ConfigurationSection addonsSection = configuration.getConfigurationSection("addons");
        Map<String, AddonDefinition> addons = new LinkedHashMap<>();

        if (addonsSection == null) {
            return addons;
        }

        collectAddons(addonsSection, addons);

        return addons;
    }

    private void collectAddons(@Nonnull ConfigurationSection parent, @Nonnull Map<String, AddonDefinition> addons) {
        for (String key : parent.getKeys(false)) {
            ConfigurationSection section = parent.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            if (isAddonSection(section)) {
                AddonDefinition definition = toAddonDefinition(key, section);

                if (definition != null) {
                    addons.put(definition.key(), definition);
                }

                collectNestedAddonSections(section, addons);
                continue;
            }

            collectAddons(section, addons);
        }
    }

    private void collectNestedAddonSections(@Nonnull ConfigurationSection parent, @Nonnull Map<String, AddonDefinition> addons) {
        for (String key : parent.getKeys(false)) {
            ConfigurationSection section = parent.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            if (isAddonSection(section) && hasAddonMetadata(section)) {
                AddonDefinition definition = toAddonDefinition(key, section);

                if (definition != null) {
                    addons.put(definition.key(), definition);
                }

                collectNestedAddonSections(section, addons);
            }
        }
    }

    private boolean isAddonSection(@Nonnull ConfigurationSection section) {
        String directUrl = section.getString("download-url");
        String legacyUrl = section.getString("url");
        return section.isConfigurationSection("versions") || (directUrl != null && !directUrl.isBlank()) || (legacyUrl != null && !legacyUrl.isBlank());
    }

    private boolean hasAddonMetadata(@Nonnull ConfigurationSection section) {
        return section.contains("name")
            || section.contains("version")
            || section.contains("enabled")
            || section.contains("download")
            || section.contains("auto-update")
            || section.contains("sha256")
            || section.contains("checksum-url")
            || section.contains("api-url")
            || section.contains("versions");
    }

    private @Nullable AddonDefinition toAddonDefinition(@Nonnull String key, @Nonnull ConfigurationSection section) {
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String name = section.getString("name", key);
        String version = section.getString("version", "latest");
        String url = section.getString("download-url", section.getString("url", ""));
        Map<String, String> versionUrls = loadVersionUrls(section);
        if (versionUrls.isEmpty() && !url.isBlank()) {
            versionUrls = AddonDefinition.legacyVersionUrl(version, url);
        }

        if (versionUrls.isEmpty()) {
>>>>>>> 46e1e0590de08d4fec311edc4566c6e20f426d7b
            plugin.getLogger().warning("Managed addon \"" + normalizedKey + "\" is missing a download url and will be skipped.");
            return null;
        }

        return new AddonDefinition(
            normalizedKey,
            name,
            section.getBoolean("enabled", true),
            section.getBoolean("download", true),
            section.getBoolean("auto-update", false),
            versionUrls,
            emptyToNull(section.getString("sha256")),
            emptyToNull(section.getString("checksum-url")),
            emptyToNull(section.getString("api-url"))
        );
    }

    private @Nonnull Map<String, String> loadVersionUrls(@Nonnull ConfigurationSection section) {
        ConfigurationSection versionsSection = section.getConfigurationSection("versions");
        Map<String, String> urls = new LinkedHashMap<>();

        if (versionsSection == null) {
            return urls;
        }

        collectVersionUrls(versionsSection, "", urls);
        return urls;
    }

    private void collectVersionUrls(@Nonnull ConfigurationSection section, @Nonnull String prefix, @Nonnull Map<String, String> urls) {
        for (String key : section.getKeys(false)) {
            String version = prefix.isBlank() ? key : prefix + '.' + key;

            if (section.isConfigurationSection(key)) {
                ConfigurationSection child = section.getConfigurationSection(key);
                if (child != null) {
                    collectVersionUrls(child, version, urls);
                }
                continue;
            }

            urls.put(version, section.getString(key, ""));
        }
    }

>>>>>>> 46e1e0590de08d4fec311edc4566c6e20f426d7b
    private @Nonnull RemoteSyncSettings parseSyncSettings(@Nonnull YamlConfiguration configuration) {
        ConfigurationSection section = configuration.getConfigurationSection("cloud-sync");

        if (section == null) {
            return new RemoteSyncSettings(false, DEFAULT_REMOTE_URL, 10L, true, true);
        }

        String configuredUrl = section.getString("url", DEFAULT_REMOTE_URL);

        // Warn if old agent-generated 'version-urls' key is still present — it is not used.
        if (section.isConfigurationSection("version-urls")) {
            plugin.getLogger().warning(
                "addonmanager.yml: 'cloud-sync.version-urls' is not a valid key and is ignored. " +
                "Remove it from your addonmanager.yml."
            );
        }

        return new RemoteSyncSettings(
            section.getBoolean("enabled", true),
            normalizeRemoteUrl(configuredUrl),
            Math.max(1L, section.getLong("interval-minutes", 10L)),
            section.getBoolean("fallback-to-local-on-empty", true),
            section.getBoolean("fallback-to-local-on-failure", true)
        );
    }

    private @Nonnull String normalizeRemoteUrl(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_REMOTE_URL;
        }

        String trimmed = url.trim();
        String marker = "github.com/";
        String blobMarker = "/blob/";

        if (trimmed.contains(marker) && trimmed.contains(blobMarker)) {
            String repositoryPath = trimmed.substring(trimmed.indexOf(marker) + marker.length());
            String[] split = repositoryPath.split("/blob/", 2);

            if (split.length == 2) {
                return "https://raw.githubusercontent.com/" + split[0] + "/" + split[1];
            }
        }

        return trimmed;
    }

    private @Nullable String emptyToNull(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private boolean shouldPersistToRemoteConfig() {
        return getSyncSettings().enabled() && Files.exists(remoteConfigPath);
    }

    private void saveAddonToPath(@Nonnull Path path, @Nonnull AddonDefinition definition) throws IOException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(path.toFile());
        ConfigurationSection addonsSection = configuration.getConfigurationSection("addons");

        if (addonsSection == null) {
            addonsSection = configuration.createSection("addons");
        }

        ConfigurationSection section = addonsSection.getConfigurationSection(definition.key());

        if (section == null) {
            section = addonsSection.createSection(definition.key());
        }

        section.set("name", definition.name());
        section.set("enabled", definition.enabled());
        section.set("download", definition.download());
        section.set("auto-update", definition.autoUpdate());
        section.set("version", null);
        section.set("download-url", null);
        section.set("sha256", definition.sha256());
        section.set("checksum-url", definition.checksumUrl());
        section.set("api-url", definition.apiUrl());
        section.set("versions", null);
        ConfigurationSection versionsSection = section.createSection("versions");

        for (Map.Entry<String, String> entry : definition.versionUrls().entrySet()) {
            versionsSection.set(entry.getKey(), entry.getValue());
        }

>>>>>>> 46e1e0590de08d4fec311edc4566c6e20f426d7b
        configuration.save(path.toFile());
    }

    private void copyBundledConfigOrCreateDefault() throws IOException {
        try (InputStream stream = plugin.getResource("addonmanager.yml")) {
            if (stream != null) {
                Files.copy(stream, configPath, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }

        Files.writeString(configPath, DEFAULT_CONFIG_CONTENT);
        plugin.getLogger().warning("addonmanager.yml was not bundled inside the jar, a safe default file has been created instead.");
    }
}
