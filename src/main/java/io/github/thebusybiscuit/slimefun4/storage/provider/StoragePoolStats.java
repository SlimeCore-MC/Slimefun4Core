package io.github.thebusybiscuit.slimefun4.storage.provider;

import com.google.common.annotations.Beta;

/**
 * A snapshot of HikariCP connection-pool statistics for a
 * {@link io.github.thebusybiscuit.slimefun4.storage.StorageProvider}.
 *
 * @param active  Number of connections currently in use
 * @param idle    Number of idle connections ready to serve requests
 * @param pending Number of threads waiting to acquire a connection
 * @param max     Configured maximum pool size
 *
 * @author SlimefunCore Contributors
 */
@Beta
public record StoragePoolStats(int active, int idle, int pending, int max) {

    /**
     * 
     *
     * @return e.g. {@literal "2 active / 3 idle / 0 pending (max 10)"}
     */
    public String summary() {
        return active + " active / " + idle + " idle / " + pending + " pending (max " + max + ')';
    }
}
