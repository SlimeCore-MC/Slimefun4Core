package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.bukkit.scheduler.BukkitTask;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Periodically syncs addonmanager.yml from a remote GitHub-hosted source.
 */
public final class GitHubSyncService {

    private static final String USER_AGENT = "Slimefun-AddonManager-CloudSync";

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final AddonService addonService;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final AtomicReference<BukkitTask> scheduledTask = new AtomicReference<>();

    public GitHubSyncService(@Nonnull Slimefun plugin, @Nonnull ConfigManager configManager, @Nonnull AddonService addonService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.addonService = addonService;
    }

    public void start() {
        RemoteSyncSettings settings = configManager.getSyncSettings();

        if (!settings.enabled()) {
            plugin.getLogger().info("AddonManager cloud sync is disabled.");
            return;
        }

        syncNow().whenComplete((result, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Initial AddonManager cloud sync failed", error);
            } else if (result.outcome() == RemoteSyncOutcome.FAILED) {
                plugin.getLogger().warning(result.message());
            }
        });
        long intervalTicks = Math.max(1L, settings.intervalMinutes()) * 60L * 20L;
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::runScheduledSync, intervalTicks, intervalTicks);
        scheduledTask.set(task);
    }

    public void shutdown() {
        BukkitTask task = scheduledTask.getAndSet(null);
        if (task != null) {
            task.cancel();
        }
    }

    public @Nonnull CompletableFuture<RemoteSyncResult> syncNow() {
        return Slimefun.getThreadService().supplyFuture(plugin, "AddonManager#GitHubSync", this::runSyncInternal);
    }

    public @Nonnull RemoteSyncState getState() {
        return configManager.getRemoteSyncState().orElse(RemoteSyncState.EMPTY);
    }

    public @Nonnull RemoteSyncSettings getSettings() {
        return configManager.getSyncSettings();
    }

    private void runScheduledSync() {
        try {
            RemoteSyncResult result = runSyncInternal();
            if (result.outcome() == RemoteSyncOutcome.UPDATED) {
                plugin.getLogger().info(result.message());
            } else if (result.outcome() == RemoteSyncOutcome.FAILED) {
                plugin.getLogger().warning(result.message());
            }
        } catch (Exception x) {
            plugin.getLogger().log(Level.WARNING, "Scheduled AddonManager GitHub sync failed", x);
        }
    }

    private @Nonnull RemoteSyncResult runSyncInternal() {
        try {
            RemoteSyncSettings settings = configManager.getSyncSettings();

            if (!settings.enabled()) {
                return new RemoteSyncResult(RemoteSyncOutcome.DISABLED, "Cloud sync is disabled.", null);
            }

            RemoteSyncState state = configManager.getRemoteSyncState().orElse(RemoteSyncState.EMPTY);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(settings.url()))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain, text/yaml, application/x-yaml, */*")
                .GET();

            if (state.etag() != null) {
                builder.header("If-None-Match", state.etag());
            }

            if (state.lastModified() != null) {
                builder.header("If-Modified-Since", state.lastModified());
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 304) {
                configManager.saveRemoteSyncState(new RemoteSyncState(
                    state.etag(),
                    state.lastModified(),
                    settings.url(),
                    "Not modified",
                    Instant.now()
                ));
                return new RemoteSyncResult(RemoteSyncOutcome.UNCHANGED, "Remote addonmanager.yml has not changed.", null);
            }

            if (response.statusCode() >= 400) {
                throw new IOException("Remote sync returned HTTP " + response.statusCode() + " from " + settings.url());
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                configManager.saveRemoteSyncState(new RemoteSyncState(
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null),
                    settings.url(),
                    "Remote file was empty",
                    Instant.now()
                ));
                return new RemoteSyncResult(RemoteSyncOutcome.EMPTY_REMOTE, "Remote addonmanager.yml is empty. Local configuration was left unchanged.", null);
            }

            configManager.saveRemoteConfig(body);
            Map<String, AddonDefinition> remoteAddons = configManager.loadRemoteAddons();

            if (remoteAddons.isEmpty()) {
                configManager.saveRemoteSyncState(new RemoteSyncState(
                    response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null),
                    settings.url(),
                    "Remote file parsed with no addons",
                    Instant.now()
                ));
                return new RemoteSyncResult(RemoteSyncOutcome.EMPTY_REMOTE, "Remote addonmanager.yml contains no valid addons. Local configuration was left unchanged.", null);
            }

            configManager.saveRemoteSyncState(new RemoteSyncState(
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null),
                settings.url(),
                "Remote config updated",
                Instant.now()
            ));

            addonService.refreshDefinitions();
            addonService.syncConfiguredAddons();
            return new RemoteSyncResult(RemoteSyncOutcome.UPDATED, "Remote addonmanager.yml synced successfully from GitHub.", null);
        } catch (Exception x) {
            String reason = describeFailure(x);

            try {
                configManager.saveRemoteSyncState(new RemoteSyncState(
                    RemoteSyncState.EMPTY.etag(),
                    RemoteSyncState.EMPTY.lastModified(),
                    configManager.getSyncSettings().url(),
                    "Sync failed: " + reason,
                    Instant.now()
                ));
            } catch (Exception ignored) {
                // Best effort state update only.
            }

            return new RemoteSyncResult(RemoteSyncOutcome.FAILED, "Remote addonmanager.yml sync failed: " + reason, x);
        }
    }

    /**
     * Describes a sync failure in one line.
     *
     * <p>Several of the exceptions thrown by {@link HttpClient} carry no message at all, so the
     * exception type and the root cause are named as well. An HTTP status, when the server
     * returned one, is already part of the message this service throws.
     *
     * @param throwable The failure to describe
     * @return A non-empty description, even when every message is {@code null}
     */
    @Nonnull
    private static String describeFailure(@Nonnull Throwable throwable) {
        StringBuilder builder = new StringBuilder(throwable.getClass().getSimpleName());
        String message = throwable.getMessage();

        if (message != null && !message.isBlank()) {
            builder.append(": ").append(message);
        }

        Throwable root = throwable;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        if (root != throwable) {
            builder.append(" (caused by ").append(root.getClass().getSimpleName());
            String rootMessage = root.getMessage();

            if (rootMessage != null && !rootMessage.isBlank()) {
                builder.append(": ").append(rootMessage);
            }

            builder.append(')');
        }

        return builder.toString();
    }
}
