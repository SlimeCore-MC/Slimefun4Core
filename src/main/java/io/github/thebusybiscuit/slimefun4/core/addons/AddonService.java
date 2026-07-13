package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * High-level addon management workflow.
 */
public final class AddonService {

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final UpdateChecker updateChecker;
    private final DownloadService downloadService;
    private final AddonLoader addonLoader;
    private final AtomicReference<Map<String, AddonDefinition>> definitions = new AtomicReference<>(Map.of());

    public AddonService(
        @Nonnull Slimefun plugin,
        @Nonnull ConfigManager configManager,
        @Nonnull UpdateChecker updateChecker,
        @Nonnull DownloadService downloadService,
        @Nonnull AddonLoader addonLoader
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.updateChecker = updateChecker;
        this.downloadService = downloadService;
        this.addonLoader = addonLoader;
    }

    public void start() {
        refreshDefinitions();
        syncConfiguredAddons();
    }

    public void shutdown() {
        plugin.getLogger().info("AddonService shutting down.");
    }

    public void refreshDefinitions() {
        definitions.set(Map.copyOf(configManager.loadAddons()));
    }

    public @Nonnull List<ManagedAddonStatus> listAddons() {
        refreshDefinitions();
        List<ManagedAddonStatus> statuses = new ArrayList<>();

        for (AddonDefinition definition : definitions.get().values().stream().sorted(Comparator.comparing(AddonDefinition::key)).toList()) {
            Optional<AddonCacheEntry> cache = configManager.getCacheEntry(definition.key());
            statuses.add(new ManagedAddonStatus(
                definition,
                configManager.addonJarExists(definition),
                addonLoader.isLoaded(definition),
                cache.map(AddonCacheEntry::version).orElse(null),
                configManager.resolveAddonPath(definition)
            ));
        }

        return statuses;
    }

    public @Nonnull CompletableFuture<AddonOperationResult> download(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withDownload(true);
            configManager.saveAddon(updated);
            refreshDefinitions();
            RemoteAddonInfo remoteInfo = updateChecker.inspect(updated, configManager.getCacheEntry(updated.key()).orElse(null));
            return downloadService.download(updated, remoteInfo);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> update(@Nonnull String key) {
        return withDefinition(key, definition -> {
            if (!definition.download()) {
                return AddonOperationResult.failure("Addon " + definition.name() + " is configured with download=false.", null);
            }

            AddonCacheEntry cache = configManager.getCacheEntry(definition.key()).orElse(null);
            RemoteAddonInfo remoteInfo = updateChecker.inspect(definition, cache);

            if (!remoteInfo.updateAvailable() && configManager.addonJarExists(definition)) {
                return AddonOperationResult.success("No newer version was detected for " + definition.name() + '.', false);
            }

            return downloadService.download(definition, remoteInfo);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> remove(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withEnabled(false).withDownload(false);
            configManager.saveAddon(updated);
            if (configManager.addonJarExists(updated) && !addonLoader.isLoaded(updated)) {
                configManager.deleteAddonJar(updated);
            } else if (addonLoader.isLoaded(updated)) {
                plugin.getLogger().info("Managed addon " + updated.name() + " is currently loaded. Its jar will be removed safely during the next startup pass.");
            }
            refreshDefinitions();
            return AddonOperationResult.success("Scheduled removal for " + updated.name() + ". The addon will be absent after the next restart.", true);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> enable(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withEnabled(true).withDownload(true);
            configManager.saveAddon(updated);
            refreshDefinitions();

            RemoteAddonInfo remoteInfo = updateChecker.inspect(updated, configManager.getCacheEntry(updated.key()).orElse(null));
            AddonOperationResult result = downloadService.download(updated, remoteInfo);

            if (!result.success()) {
                return result;
            }

            return AddonOperationResult.success("Downloaded and enabled " + updated.name() + " for the next restart.", true);
        });
    }

    public @Nonnull CompletableFuture<AddonOperationResult> disable(@Nonnull String key) {
        return withDefinition(key, definition -> {
            AddonDefinition updated = definition.withEnabled(false).withDownload(false);
            configManager.saveAddon(updated);

            if (configManager.addonJarExists(updated) && !addonLoader.isLoaded(updated)) {
                configManager.deleteAddonJar(updated);
                configManager.removeCacheEntry(updated.key());
                refreshDefinitions();
                return AddonOperationResult.success("Disabled and deleted " + updated.name() + ". No restart needed.", false);
            }

            if (addonLoader.isLoaded(updated)) {
                plugin.getLogger().info("Managed addon " + updated.name() + " is currently loaded. Its jar will be deleted safely during the next startup pass.");
            } else {
                configManager.removeCacheEntry(updated.key());
            }

            refreshDefinitions();
            return AddonOperationResult.success("Disabled and scheduled deletion for " + updated.name() + " on the next restart.", true);
        });
    }

    public void syncConfiguredAddons() {
        Slimefun.getThreadService().newThread(plugin, "AddonManager#startupReconcile", () -> {
            refreshDefinitions();
            List<AddonDefinition> toLoad = new ArrayList<>();

            for (AddonDefinition definition : definitions.get().values().stream().sorted(Comparator.comparing(AddonDefinition::key)).toList()) {
                try {
                    if (!definition.download()) {
                        if (configManager.addonJarExists(definition)) {
                            configManager.deleteAddonJar(definition);
                            configManager.removeCacheEntry(definition.key());
                            plugin.getLogger().info("Deleted managed addon jar because download=false: " + definition.name());
                        }
                        continue;
                    }

                    boolean present = configManager.addonJarExists(definition);
                    Optional<AddonCacheEntry> cacheOpt = configManager.getCacheEntry(definition.key());

                    // Discard stale cache entries whose version tag no longer matches the current server's tags.
                    if (cacheOpt.isPresent()) {
                        String cachedVersion = cacheOpt.get().version();
                        List<String> validTags = VersionTagResolver.resolveTags(Slimefun.getMinecraftVersion());
                        if (cachedVersion != null && !validTags.contains(cachedVersion)) {
                            plugin.getLogger().warning(
                                "AddonManager: discarding stale cache entry for '" + definition.key() +
                                "' (cached tag '" + cachedVersion + "' not valid for this server). " +
                                "It will be re-evaluated on next download."
                            );
                            cacheOpt = Optional.empty();
                        }
                    }

                    AddonCacheEntry cache = cacheOpt.orElse(null);

                    if (!present) {
                        AddonOperationResult result = downloadService.download(definition, updateChecker.inspect(definition, cache));
                        if (!result.success()) {
                            plugin.getLogger().warning(result.message());
                            continue;
                        }
                    } else if (definition.autoUpdate()) {
                        RemoteAddonInfo remoteInfo = updateChecker.inspect(definition, cache);
                        if (remoteInfo.updateAvailable()) {
                            AddonOperationResult result = downloadService.download(definition, remoteInfo);
                            if (!result.success()) {
                                plugin.getLogger().warning(result.message());
                            }
                        }
                    }

                    if (definition.enabled() && Files.exists(configManager.resolveAddonPath(definition))) {
                        toLoad.add(definition);
                    }
                } catch (Exception x) {
                    plugin.getLogger().log(Level.WARNING, "Failed to reconcile managed addon \"" + definition.name() + "\"", x);
                }
            }

            Slimefun.runSync(() -> addonLoader.loadManagedAddons(toLoad), 1L);
        });
    }

    private @Nonnull CompletableFuture<AddonOperationResult> withDefinition(@Nonnull String key, @Nonnull AddonWork work) {
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        return Slimefun.getThreadService().supplyFuture(plugin, "AddonManager#" + normalizedKey, () -> {
            refreshDefinitions();
            AddonDefinition definition = definitions.get().get(normalizedKey);

            if (definition == null) {
                return AddonOperationResult.failure("Unknown managed addon: " + normalizedKey, null);
            }

            try {
                return work.apply(definition);
            } catch (Exception x) {
                plugin.getLogger().log(Level.WARNING, "Addon operation failed for " + definition.name(), x);
                return AddonOperationResult.failure("Addon operation failed for " + definition.name() + ": " + x.getMessage(), x);
            }
        });
    }

    @FunctionalInterface
    private interface AddonWork {
        AddonOperationResult apply(AddonDefinition definition) throws IOException, InterruptedException;
    }
}
