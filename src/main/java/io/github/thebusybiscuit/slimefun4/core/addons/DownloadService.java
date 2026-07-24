package io.github.thebusybiscuit.slimefun4.core.addons;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.JsonUtils;

/**
 * Handles safe asynchronous addon downloads.
 */
public final class DownloadService {

    private static final int MAX_RETRIES = 3;
    private static final String USER_AGENT = "Slimefun-AddonManager";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final Slimefun plugin;
    private final ConfigManager configManager;
    private final AddonLoader addonLoader;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public DownloadService(@Nonnull Slimefun plugin, @Nonnull ConfigManager configManager, @Nonnull AddonLoader addonLoader) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.addonLoader = addonLoader;
    }

    public @Nonnull CompletableFuture<AddonOperationResult> downloadAsync(@Nonnull AddonDefinition definition, @Nullable RemoteAddonInfo remoteInfo) {
        return Slimefun.getThreadService().supplyFuture(plugin, "AddonManager#download(" + definition.key() + ")", () -> download(definition, remoteInfo));
    }

    public @Nonnull AddonOperationResult download(@Nonnull AddonDefinition definition, @Nullable RemoteAddonInfo remoteInfo) {
        Throwable lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String downloadUrl = remoteInfo != null ? remoteInfo.downloadUrl() : definition.url();
                downloadUrl = resolveDownloadUrl(definition, downloadUrl);
                if (!isJarUrl(downloadUrl)) {
                    throw new IOException("Resolved download URL is not a jar for " + definition.name() + ": " + downloadUrl);
                }
                String expectedChecksum = remoteInfo != null ? remoteInfo.expectedSha256() : normalizeSha(definition.sha256());
                Path target = configManager.resolveAddonPath(definition);
                Path temporary = Files.createTempFile(configManager.getTempDirectory(), definition.key() + "-", ".jar");

                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
                    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                    if (response.statusCode() >= 400) {
                        throw new IOException("Download returned HTTP " + response.statusCode() + " for " + downloadUrl);
                    }

                    try (InputStream inputStream = response.body(); OutputStream outputStream = Files.newOutputStream(temporary)) {
                        inputStream.transferTo(outputStream);
                    }

                    if (!looksLikeJar(temporary)) {
                        throw new IOException("Downloaded file was not a jar archive for " + definition.name() + " from " + downloadUrl);
                    }

                    String actualChecksum = sha256(temporary);
                    boolean verified = false;

                    if (expectedChecksum != null) {
                        if (!expectedChecksum.equals(actualChecksum)) {
                            throw new IOException("Checksum mismatch for " + definition.name() + " (expected " + expectedChecksum + ", got " + actualChecksum + ')');
                        }

                        verified = true;
                    } else {
                        plugin.getLogger().warning("No SHA256 checksum was available for managed addon \"" + definition.key() + "\". The jar was downloaded but could not be externally verified.");
                    }

                    if (!addonLoader.validateAddonJar(temporary, definition)) {
                        throw new IOException("Downloaded jar for " + definition.name() + " failed plugin validation");
                    }

                    backupExistingJar(target, definition, actualChecksum);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    AddonCacheEntry cacheEntry = new AddonCacheEntry(
                        remoteInfo != null ? remoteInfo.resolvedVersion() : definition.version(),
                        actualChecksum,
                        remoteInfo != null ? remoteInfo.etag() : null,
                        remoteInfo != null ? remoteInfo.lastModified() : null,
                        downloadUrl,
                        Instant.now(),
                        verified
                    );
                    configManager.saveCacheEntry(definition.key(), cacheEntry);

                    return AddonOperationResult.success("Downloaded " + definition.name() + " and staged it for the next restart.", true);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (Throwable x) {
                lastError = x;
                plugin.getLogger().log(Level.WARNING, "Managed addon download attempt " + attempt + " failed for " + definition.name(), x);
            }
        }

        return AddonOperationResult.failure("Failed to download " + definition.name() + " after " + MAX_RETRIES + " attempts.", lastError);
    }

    private @Nonnull String resolveDownloadUrl(@Nonnull AddonDefinition definition, @Nonnull String configuredUrl) throws IOException, InterruptedException {
        String trimmed = configuredUrl.trim();

        if (isJarUrl(trimmed)) {
            return trimmed;
        }

        Optional<String> apiUrl = toGitHubContentsApiUrl(trimmed);
        if (apiUrl.isEmpty()) {
            return trimmed;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl.get().replace(" ", "%20")))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() >= 400) {
            throw new IOException("GitHub directory lookup returned HTTP " + response.statusCode() + " for " + trimmed);
        }

        JsonElement payload = JsonUtils.parseString(response.body());
        if (!payload.isJsonArray()) {
            throw new IOException("GitHub directory lookup did not return a file list for " + trimmed);
        }

        JsonObject selectedJar = selectLatestJar(payload.getAsJsonArray(), definition);
        String downloadUrl = getString(selectedJar, "download_url");

        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IOException("GitHub directory lookup found a jar without a download_url for " + definition.name());
        }

        plugin.getLogger().info("Resolved managed addon " + definition.name() + " to " + getString(selectedJar, "name"));
        return downloadUrl;
    }

    private boolean isJarUrl(@Nonnull String url) {
        String path;

        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException x) {
            return false;
        }

        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private boolean looksLikeJar(@Nonnull Path file) throws IOException {
        if (Files.size(file) < 4) {
            return false;
        }

        byte[] header = new byte[4];
        try (InputStream inputStream = Files.newInputStream(file)) {
            return inputStream.read(header) == header.length
                && header[0] == 0x50
                && header[1] == 0x4B
                && header[2] == 0x03
                && header[3] == 0x04;
        }
    }

    private @Nonnull Optional<String> toGitHubContentsApiUrl(@Nonnull String url) {
        URI uri;

        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException x) {
            return Optional.empty();
        }

        String host = uri.getHost();
        if (host == null) {
            return Optional.empty();
        }

        String path = uri.getPath();
        if ("github.com".equalsIgnoreCase(host)) {
            String[] parts = path.split("/", 6);
            if (parts.length < 6 || !"tree".equals(parts[3])) {
                return Optional.empty();
            }

            return Optional.of("https://api.github.com/repos/" + parts[1] + '/' + parts[2] + "/contents/" + parts[5] + "?ref=" + parts[4]);
        }

        if ("raw.githubusercontent.com".equalsIgnoreCase(host)) {
            String[] parts = path.split("/", 5);
            if (parts.length < 5) {
                return Optional.empty();
            }

            return Optional.of("https://api.github.com/repos/" + parts[1] + '/' + parts[2] + "/contents/" + parts[4] + "?ref=" + parts[3]);
        }

        return Optional.empty();
    }

    private @Nonnull JsonObject selectLatestJar(@Nonnull JsonArray files, @Nonnull AddonDefinition definition) throws IOException {
        return files.asList().stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(file -> "file".equalsIgnoreCase(String.valueOf(getString(file, "type"))))
            .filter(file -> {
                String name = getString(file, "name");
                return name != null && name.toLowerCase(Locale.ROOT).endsWith(".jar");
            })
            .max(Comparator
                .comparingInt((JsonObject file) -> addonNameScore(file, definition))
                .thenComparingInt(file -> jarVersionScore(getString(file, "name")))
                .thenComparing(file -> String.valueOf(getString(file, "name"))))
            .orElseThrow(() -> new IOException("No jar files were found in the configured release folder for " + definition.name()));
    }

    private int addonNameScore(@Nonnull JsonObject file, @Nonnull AddonDefinition definition) {
        String name = String.valueOf(getString(file, "name")).toLowerCase(Locale.ROOT);
        String key = definition.key().toLowerCase(Locale.ROOT);
        String addonName = definition.name().toLowerCase(Locale.ROOT);

        return name.contains(key) || name.contains(addonName) ? 1 : 0;
    }

    private int jarVersionScore(@Nullable String fileName) {
        if (fileName == null) {
            return 0;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(fileName);
        int score = 0;

        while (matcher.find()) {
            try {
                score = (score * 1000) + Integer.parseInt(matcher.group());
            } catch (NumberFormatException ignored) {
                // Ignore oversized numeric parts.
            }
        }

        return score;
    }

    private @Nullable String getString(@Nonnull JsonObject object, @Nonnull String key) {
        return !object.has(key) || object.get(key).isJsonNull() ? null : object.get(key).getAsString();
    }

    private @Nonnull String sha256(@Nonnull Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream inputStream = Files.newInputStream(file); DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
            byte[] buffer = new byte[8192];
            while (digestInputStream.read(buffer) != -1) {
                // DigestInputStream updates automatically.
            }
        }

        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private @Nullable String normalizeSha(@Nullable String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private void backupExistingJar(@Nonnull Path target, @Nonnull AddonDefinition definition, @Nonnull String checksum) throws IOException {
        if (Files.notExists(target)) {
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        Path backup = configManager.getBackupDirectory().resolve(definition.key() + "-" + timestamp + ".jar");
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        Path checksumFile = configManager.getBackupDirectory().resolve(definition.key() + "-" + timestamp + ".sha256");
        Files.writeString(checksumFile, checksum, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
