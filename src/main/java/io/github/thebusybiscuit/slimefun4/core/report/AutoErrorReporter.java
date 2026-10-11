package io.github.thebusybiscuit.slimefun4.core.report;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.bukkit.plugin.Plugin;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * A {@link Handler} attached to the root logger that automatically detects and reports {@code SEVERE}
 * errors originating from Slimefun or one of its installed addons.
 *
 * <p>When a matching error is seen, an automatic report is posted to the Discord report webhook through
 * {@link ReportSessionManager#submitAutomaticReport(String, Throwable, String)} — no player action is
 * required. Identical errors are <strong>cached</strong> by a stable signature so the same stack trace is
 * not re-posted within the dedup window, and a global cap prevents a crash loop from flooding Discord.
 *
 * <p>The manual {@code /report} flow is unaffected by this handler.
 *
 * @author SlimefunCore
 */
public final class AutoErrorReporter extends Handler {

    // Only these package roots are considered "ours" for Slimefun core itself.
    private static final String[] CORE_PREFIXES = {
        "io.github.thebusybiscuit.slimefun4",
        "me.mrCookieSlime"
    };

    // A signature is re-reportable again only after this many milliseconds.
    private static final long DEDUP_WINDOW_MS = 60 * 60 * 1000L; // 1 hour
    // Safety net: never post more than this many auto-reports per burst window.
    private static final int GLOBAL_CAP = 20;
    private static final long GLOBAL_WINDOW_MS = 10 * 60 * 1000L; // 10 minutes
    // Hard daily quota: this server posts at most this many auto-reports per rolling day.
    private static final int DAILY_CAP = 2;
    private static final long DAILY_WINDOW_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private final Slimefun plugin;
    private final ReportSessionManager reportManager;

    // signature -> timestamp of last report
    private final Map<String, Long> seenSignatures = new ConcurrentHashMap<>();
    private final AtomicInteger windowCount = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();
    private final AtomicInteger dailyCount = new AtomicInteger(0);
    private volatile long dailyStart = System.currentTimeMillis();

    // Re-entrancy guard: the reporting path itself may log; never report our own failures.
    private final ThreadLocal<Boolean> reporting = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public AutoErrorReporter(Slimefun plugin, ReportSessionManager reportManager) {
        this.plugin = plugin;
        this.reportManager = reportManager;
        setLevel(Level.SEVERE);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || record.getLevel().intValue() < Level.SEVERE.intValue()) {
            return;
        }

        Throwable thrown = record.getThrown();
        if (thrown == null) {
            return;
        }

        // Skip anything logged while we are already building a report (avoid recursion).
        if (Boolean.TRUE.equals(reporting.get())) {
            return;
        }

        // Skip non-actionable addon-compatibility noise: plugin enable/disable lifecycle failures and
        // linkage errors (NoClassDefFoundError / NoSuchMethodError / ...). These are version-mismatch
        // between an addon and this fork, are already printed to the console by the plugin manager, and
        // recur on every restart — reporting them just floods the webhook without giving anything to
        // fix in the core. Genuine runtime SEVERE errors (ticking, storage, listeners) still report.
        if (isNonActionable(thrown)) {
            return;
        }

        try {
            OriginFrame origin = findOrigin(thrown);
            if (origin == null) {
                return; // Not a Slimefun/addon error - ignore.
            }

            String signature = buildSignature(thrown, origin.frame());
            long now = System.currentTimeMillis();

            if (isDuplicate(signature, now) || !acquireDailySlot(now) || !acquireGlobalSlot(now)) {
                return;
            }

            reporting.set(Boolean.TRUE);
            try {
                reportManager.submitAutomaticReport(origin.pluginName(), thrown, record.getMessage());
            } finally {
                reporting.set(Boolean.FALSE);
            }
        } catch (Exception ignored) {
            // The reporter must never throw out of a logging call, and must never report its own errors.
            reporting.set(Boolean.FALSE);
        }
    }

    private boolean isDuplicate(String signature, long now) {
        Long last = seenSignatures.get(signature);
        if (last != null && (now - last) < DEDUP_WINDOW_MS) {
            return true;
        }
        seenSignatures.put(signature, now);
        pruneExpired(now);
        return false;
    }

    private boolean acquireGlobalSlot(long now) {
        if (now - windowStart > GLOBAL_WINDOW_MS) {
            windowStart = now;
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= GLOBAL_CAP;
    }

    /**
     * Enforces the hard daily quota: at most {@link #DAILY_CAP} auto-reports per rolling
     * {@link #DAILY_WINDOW_MS} for this server. Once the quota is hit, further reports are dropped
     * until the day rolls over. The counter is reset in-memory, so a restart also opens a fresh day.
     */
    private synchronized boolean acquireDailySlot(long now) {
        if (now - dailyStart > DAILY_WINDOW_MS) {
            dailyStart = now;
            dailyCount.set(0);
        }
        return dailyCount.incrementAndGet() <= DAILY_CAP;
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Long>> it = seenSignatures.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > DEDUP_WINDOW_MS) {
                it.remove();
            }
        }
    }

    /**
     * Walks the throwable and its causes looking for the first stack frame that belongs to Slimefun or an
     * installed addon. Returns {@code null} if the error did not originate in our code.
     */
    private OriginFrame findOrigin(Throwable thrown) {
        Throwable current = thrown;
        int depth = 0;

        while (current != null && depth < 5) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String className = frame.getClassName();

                for (String prefix : CORE_PREFIXES) {
                    if (className.startsWith(prefix)) {
                        return new OriginFrame(frame, "Slimefun");
                    }
                }

                String addonName = matchAddon(className);
                if (addonName != null) {
                    return new OriginFrame(frame, addonName);
                }
            }
            current = current.getCause();
            depth++;
        }

        return null;
    }

    /**
     * Returns the addon name if the given class belongs to an installed Slimefun addon's package, else
     * {@code null}. Resolved live so newly loaded addons are covered.
     */
    private String matchAddon(String className) {
        for (Plugin addon : Slimefun.getInstalledAddons()) {
            // Skip Slimefun itself - it is covered by CORE_PREFIXES.
            if (addon == plugin) {
                continue;
            }

            String pkg = addon.getClass().getPackageName();
            if (!pkg.isEmpty() && className.startsWith(pkg)) {
                return addon.getName();
            }
        }
        return null;
    }

    // Bukkit/Paper frames that mark a plugin enable/disable lifecycle failure.
    private static final String[] LIFECYCLE_FRAMES = {
        "org.bukkit.plugin.java.JavaPlugin.setEnabled",
        "io.papermc.paper.plugin.manager.PaperPluginInstanceManager.enablePlugin",
        "io.papermc.paper.plugin.manager.PaperPluginInstanceManager.disablePlugin",
        "org.bukkit.plugin.SimplePluginManager.enablePlugin",
        "org.bukkit.plugin.SimplePluginManager.disablePlugin"
    };

    /**
     * Returns whether this error is addon-compatibility noise that should not be auto-reported: a
     * {@link LinkageError} / {@link ClassNotFoundException} (an addon compiled against a different
     * Slimefun API), or a failure raised from a plugin enable/disable lifecycle call. Both recur on
     * every restart and are not fixable in the core, so reporting them only spams the webhook.
     */
    private static boolean isNonActionable(Throwable thrown) {
        Throwable current = thrown;
        int depth = 0;

        while (current != null && depth < 5) {
            if (current instanceof LinkageError || current instanceof ClassNotFoundException) {
                return true;
            }

            for (StackTraceElement frame : current.getStackTrace()) {
                String signature = frame.getClassName() + '.' + frame.getMethodName();
                for (String lifecycle : LIFECYCLE_FRAMES) {
                    if (signature.equals(lifecycle)) {
                        return true;
                    }
                }
            }

            current = current.getCause();
            depth++;
        }

        return false;
    }

    private static String buildSignature(Throwable thrown, StackTraceElement frame) {
        return thrown.getClass().getName() + '|' + frame.getClassName() + '#' + frame.getMethodName() + ':' + frame.getLineNumber();
    }

    @Override
    public void flush() {
        // no-op
    }

    @Override
    public void close() {
        seenSignatures.clear();
    }

    private record OriginFrame(StackTraceElement frame, String pluginName) {}
}
