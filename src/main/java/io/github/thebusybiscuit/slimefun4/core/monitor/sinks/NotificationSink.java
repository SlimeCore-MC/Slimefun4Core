package io.github.thebusybiscuit.slimefun4.core.monitor.sinks;

import io.github.thebusybiscuit.slimefun4.core.monitor.MonitorEvent;
import javax.annotation.Nonnull;

@FunctionalInterface
public interface NotificationSink {
    void handle(@Nonnull MonitorEvent event);
}
