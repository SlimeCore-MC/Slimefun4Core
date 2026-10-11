package io.github.thebusybiscuit.slimefun4.core.monitor;

import io.github.thebusybiscuit.slimefun4.core.monitor.sinks.NotificationSink;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public class MonitorEventBus {
    private final Slimefun plugin;
    private final List<NotificationSink> sinks = new CopyOnWriteArrayList<>();

    public MonitorEventBus(Slimefun plugin) {
        this.plugin = plugin;
    }

    public void subscribe(NotificationSink sink) {
        sinks.add(sink);
    }

    public void unsubscribe(NotificationSink sink) {
        sinks.remove(sink);
    }

    public void publish(MonitorEvent event) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            for (NotificationSink sink : sinks) {
                try {
                    sink.handle(event);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "A NotificationSink failed to handle an event", e);
                }
            }
        });
    }

    public void shutdown() {
        sinks.clear();
    }
}
