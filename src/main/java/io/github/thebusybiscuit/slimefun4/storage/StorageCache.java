package io.github.thebusybiscuit.slimefun4.storage;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.storage.data.PlayerData;

import org.apache.commons.lang.Validate;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A caching {@link Storage} decorator that keeps player data in memory and
 * forwards writes through a save queue.
 *
 * <p>Supports configurable maximum cache size (LRU eviction) and TTL-based
 * expiry per entry. Use {@link #getCacheStats()} to monitor hit rate and
 * eviction counts.
 */
@Beta
@ThreadSafe
public class StorageCache implements Storage {

    private final Storage backend;
    private final StorageSaveQueue saveQueue = new StorageSaveQueue();

    // Bounded LRU map — synchronized externally via Collections.synchronizedMap
    private final Map<UUID, PlayerData> cache;
    // Last-access timestamps for TTL eviction (ms since epoch)
    private final ConcurrentHashMap<UUID, Long> lastAccess = new ConcurrentHashMap<>();

    private final int   maxSize;
    private final long  expireAfterMs; // Long.MAX_VALUE = no TTL

    // Stats counters
    private final AtomicLong hits      = new AtomicLong(0);
    private final AtomicLong misses    = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    /**
     * Creates an unbounded {@link StorageCache} with no TTL.
     * Equivalent to the original behaviour before size/TTL were introduced.
     *
     * @param backend The wrapped storage backend
     */
    public StorageCache(@Nonnull Storage backend) {
        this(backend, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Creates a bounded {@link StorageCache} with LRU eviction and optional TTL.
     *
     * @param backend      The wrapped storage backend
     * @param maxSize      Maximum number of entries before LRU eviction kicks in
     * @param expireAfterMs Milliseconds after last access before an entry is treated as
     *                     expired (use {@link Long#MAX_VALUE} to disable TTL)
     */
    public StorageCache(@Nonnull Storage backend, int maxSize, long expireAfterMs) {
        Validate.notNull(backend, "Storage backend must not be null");
        Validate.isTrue(maxSize > 0, "maxSize must be > 0");
        this.backend      = backend;
        this.maxSize      = maxSize;
        this.expireAfterMs = expireAfterMs > 0 ? expireAfterMs : Long.MAX_VALUE;

        // Thread-safe LRU: access-order LinkedHashMap wrapped in synchronizedMap
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(
            Math.min(maxSize, 1024), 0.75f, true /* access-order */) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, PlayerData> eldest) {
                if (size() > StorageCache.this.maxSize) {
                    lastAccess.remove(eldest.getKey());
                    evictions.incrementAndGet();
                    return true;
                }
                return false;
            }
        });
    }
    // Storage — data operations
    @Override
    public PlayerData loadPlayerData(UUID uuid) {
        synchronized (cache) {
            PlayerData cached = cache.get(uuid);
            if (cached != null) {
                long lastAccessTime = lastAccess.getOrDefault(uuid, 0L);
                boolean expired = expireAfterMs != Long.MAX_VALUE
                    && (System.currentTimeMillis() - lastAccessTime) > expireAfterMs;

                if (!expired) {
                    lastAccess.put(uuid, System.currentTimeMillis());
                    hits.incrementAndGet();
                    return cached;
                }

                // TTL expired — evict and reload
                cache.remove(uuid);
                lastAccess.remove(uuid);
                evictions.incrementAndGet();
            }
        }

        misses.incrementAndGet();
        PlayerData loaded = backend.loadPlayerData(uuid);
        synchronized (cache) {
            cache.put(uuid, loaded);
            lastAccess.put(uuid, System.currentTimeMillis());
        }
        return loaded;
    }

    @Override
    public void savePlayerData(UUID uuid, PlayerData data) {
        synchronized (cache) {
            cache.put(uuid, data);
            lastAccess.put(uuid, System.currentTimeMillis());
        }
        saveQueue.enqueue(uuid, data, backend);
    }

    @Override
    public void initialize() throws StorageException {
        backend.initialize();
    }

    @Override
    public void shutdown() {
        saveQueue.shutdown();
        backend.shutdown();
    }

    @Override
    public boolean isAvailable() {
        return backend.isAvailable();
    }

    @Override
    public String getBackendName() {
        return "Cache -> " + backend.getBackendName();
    }
    // Cache management
    /**
     * Invalidates the cached entry for a player.
     * Must be called after the final save on player quit to prevent unbounded memory growth.
     *
     * @param uuid The player UUID to invalidate
     */
    public void invalidate(@Nonnull UUID uuid) {
        Validate.notNull(uuid, "UUID must not be null");
        synchronized (cache) {
            cache.remove(uuid);
        }
        lastAccess.remove(uuid);
    }

    /**
     * Invalidates every cached entry.
     *
     * <p>Required after a restore or any other out-of-band change to the backend,
     * where cached data no longer reflects what is stored.
     *
     * @return The number of entries that were dropped
     */
    public int invalidateAll() {
        int dropped;

        synchronized (cache) {
            dropped = cache.size();
            cache.clear();
        }

        lastAccess.clear();
        return dropped;
    }

    /**
     * Returns the cached entry for a player if present, without affecting stats.
     *
     * @param uuid The player UUID to look up
     * @return An optional containing the cached data, or empty
     */
    Optional<PlayerData> getCached(@Nonnull UUID uuid) {
        synchronized (cache) {
            return Optional.ofNullable(cache.get(uuid));
        }
    }

    /**
     * Returns the wrapped backend.
     *
     * @return The wrapped backend
     */
    @Nonnull
    public Storage getBackend() {
        return backend;
    }

    /**
     * Returns a snapshot of current cache statistics.
     *
     * @return An immutable {@link CacheStats} snapshot
     */
    @Nonnull
    public CacheStats getCacheStats() {
        int currentSize;
        synchronized (cache) {
            currentSize = cache.size();
        }
        return new CacheStats(currentSize, maxSize, hits.get(), misses.get(), evictions.get());
    }
    // Inner record — CacheStats
    /**
     * Immutable snapshot of cache performance statistics.
     *
     * @param size      Current number of entries in the cache
     * @param maxSize   Configured maximum cache size
     * @param hits      Total cache hits since startup
     * @param misses    Total cache misses since startup
     * @param evictions Total entries evicted (LRU + TTL) since startup
     */
    @Beta
    public record CacheStats(int size, int maxSize, long hits, long misses, long evictions) {

        /**
         * Returns the cache hit rate as a value between {@code 0.0} and {@code 1.0}.
         *
         * @return Hit rate, or {@code 0.0} if no requests have been recorded yet
         */
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }

        /**
         * 
         *
         * @return e.g. {@literal "450 / 5000 entries, 92.5% hit rate"}
         */
        public String summary() {
            return String.format("%d / %d entries, %.1f%% hit rate", size, maxSize, hitRate() * 100);
        }
    }
}
