package io.github.thebusybiscuit.slimefun4.core.guide.options;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.bukkit.inventory.ItemStack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;

/**
 * Supplies a stable decorative player-head icon for each managed addon shown in the
 * {@link AddonGuideMenu}.
 *
 * <p>The head texture pool is fetched <strong>once</strong> from the minecraft-heads.com decoration
 * category and then cached to {@code addon-head-cache.json} in the plugin data folder. Once the cache
 * holds textures no further request is made. The whole feature is gated behind
 * {@code addon-manager.decorative-heads} in {@code config.yml}; when disabled (or when the pool is
 * empty for any reason) {@link #iconFor(String)} returns {@link Optional#empty()} and the caller falls
 * back to its plain pane icon.
 *
 * <p>Each addon is mapped to a texture deterministically by hashing its key, so an addon always shows
 * the same head across restarts as long as the cached pool is unchanged.
 *
 * @author SlimefunCore Contributors
 */
final class AddonHeadIcons {

    private static final String API_URL = "https://minecraft-heads.com/scripts/api.php?cat=decoration&tags=false";
    private static final String CACHE_FILE = "addon-head-cache.json";
    private static final int MAX_POOL = 512;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final AtomicReference<List<String>> POOL = new AtomicReference<>(List.of());
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    private AddonHeadIcons() {}

    /**
     * Kicks off a one-time load of the head pool if decorative heads are enabled: reads the on-disk
     * cache first, and only if it is empty fetches from minecraft-heads.com on a background thread and
     * persists the result. Safe to call repeatedly; the work runs at most once.
     */
    static void preload() {
        if (!isEnabled() || LOADED.get() || !FETCHING.compareAndSet(false, true)) {
            return;
        }

        Slimefun.getThreadService().newThread(Slimefun.instance(), "AddonHeadIcons#load", () -> {
            try {
                List<String> cached = readCache();
                if (!cached.isEmpty()) {
                    POOL.set(cached);
                    LOADED.set(true);
                    return;
                }

                List<String> fetched = fetchFromApi();
                if (!fetched.isEmpty()) {
                    POOL.set(fetched);
                    writeCache(fetched);
                    LOADED.set(true);
                }
            } catch (Exception x) {
                Slimefun.instance().getLogger().log(Level.FINE, "Could not load decorative addon heads", x);
            } finally {
                FETCHING.set(false);
            }
        });
    }

    /**
     * Returns a decorative head {@link ItemStack} for the given addon key, or empty when decorative
     * heads are disabled or the pool has not loaded yet. The mapping is deterministic per key.
     *
     * @param key
     *            The addon key
     *
     * @return An {@link Optional} head item, or empty to fall back to the pane icon
     */
    @Nonnull
    static Optional<ItemStack> iconFor(@Nonnull String key) {
        if (!isEnabled()) {
            return Optional.empty();
        }

        // Best-effort trigger so the pool warms up the first time the menu is opened.
        if (!LOADED.get()) {
            preload();
            return Optional.empty();
        }

        List<String> pool = POOL.get();
        if (pool.isEmpty()) {
            return Optional.empty();
        }

        String texture = pool.get(Math.floorMod(key.hashCode(), pool.size()));
        try {
            return Optional.of(SlimefunUtils.getCustomHead(texture));
        } catch (Exception x) {
            return Optional.empty();
        }
    }

    private static boolean isEnabled() {
        Slimefun instance = Slimefun.instance();
        return instance != null && instance.getConfig().getBoolean("addon-manager.decorative-heads", true);
    }

    @Nonnull
    private static List<String> fetchFromApi() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
            .header("User-Agent", "SlimefunCore-AddonManager")
            .header("Accept", "application/json, */*")
            .GET()
            .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IOException("minecraft-heads API returned HTTP " + response.statusCode());
        }

        return parseValues(response.body());
    }

    @Nonnull
    private static List<String> parseValues(String body) {
        List<String> values = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return values;
        }

        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonArray()) {
            return values;
        }

        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonElement value = element.getAsJsonObject().get("value");
            if (value != null && value.isJsonPrimitive()) {
                String texture = value.getAsString();
                if (texture != null && !texture.isBlank()) {
                    values.add(texture);
                    if (values.size() >= MAX_POOL) {
                        break;
                    }
                }
            }
        }

        return values;
    }

    @Nonnull
    private static List<String> readCache() {
        Path path = cachePath();
        if (path == null || !Files.exists(path)) {
            return List.of();
        }

        try {
            String body = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonArray()) {
                return List.of();
            }

            List<String> values = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    values.add(element.getAsString());
                }
            }
            return values;
        } catch (Exception x) {
            return List.of();
        }
    }

    private static void writeCache(@Nonnull List<String> values) {
        Path path = cachePath();
        if (path == null) {
            return;
        }

        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, array.toString(), StandardCharsets.UTF_8);
        } catch (IOException x) {
            Slimefun.instance().getLogger().log(Level.FINE, "Could not write addon head cache", x);
        }
    }

    private static Path cachePath() {
        Slimefun instance = Slimefun.instance();
        if (instance == null) {
            return null;
        }
        return instance.getDataFolder().toPath().resolve(CACHE_FILE);
    }
}
