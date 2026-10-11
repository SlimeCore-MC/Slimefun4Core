package io.github.thebusybiscuit.slimefun4.core.monitor.watchers;

import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEventBus;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class AddonFolderWatcher {
    private final Path watchedDir;
    private final MonitorEventBus eventBus;
    private final Slimefun plugin;
    private WatchService watchService;
    private BukkitTask pollTask;
    private final Map<String, Long> recentlyDropped = new ConcurrentHashMap<>();

    public AddonFolderWatcher(Slimefun plugin, MonitorEventBus eventBus, Path watchedDir) {
        this.plugin = plugin;
        this.eventBus = eventBus;
        this.watchedDir = watchedDir;
    }

    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchedDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            
            pollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                WatchKey key = watchService.poll();
                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            Path context = (Path) event.context();
                            String fileName = context.toString();
                            
                            if (fileName.endsWith(".jar")) {
                                long now = System.currentTimeMillis();
                                Long lastSeen = recentlyDropped.get(fileName);
                                
                                if (lastSeen == null || now - lastSeen > 2000) {
                                    recentlyDropped.put(fileName, now);
                                    
                                    Path fullPath = watchedDir.resolve(context);
                                    boolean recognized = isRecognizedPlugin(fullPath);
                                    
                                    eventBus.publish(new MonitorEvent.AddonManuallyDropped(fileName, recognized));
                                }
                            }
                        }
                    }
                    key.reset();
                }
            }, 100L, 100L);
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to start AddonFolderWatcher", e);
        }
    }

    public void shutdown() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to close watchService", e);
            }
            watchService = null;
        }
    }

    private boolean isRecognizedPlugin(Path jarPath) {
        try (var zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            return zip.getEntry("plugin.yml") != null || zip.getEntry("paper-plugin.yml") != null;
        } catch (Exception x) {
            return false;
        }
    }
}
