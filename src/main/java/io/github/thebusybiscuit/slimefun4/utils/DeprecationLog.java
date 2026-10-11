package io.github.thebusybiscuit.slimefun4.utils;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

/**
 * Routes item/addon deprecation warnings to a dedicated {@code warning.log} file inside the Slimefun
 * data folder instead of flooding the server console. The console would otherwise print one (or two)
 * warning lines per registered item that still implements a deprecated API, which is pure noise for
 * server operators. The information is not lost - it is appended to {@code warning.log} so developers
 * can still act on it.
 *
 * @author SlimefunCore
 */
public final class DeprecationLog {

    private static final Object LOCK = new Object();
    private static Logger fileLogger;

    private DeprecationLog() {}

    /**
     * Records a single deprecation warning to {@code warning.log}. This never writes to the console.
     *
     * @param message
     *            The full warning message to record
     */
    public static void record(@Nonnull String message) {
        Logger logger = getLogger();

        if (logger != null) {
            logger.log(Level.WARNING, message);
        }
    }

    private static Logger getLogger() {
        synchronized (LOCK) {
            if (fileLogger != null) {
                return fileLogger;
            }

            try {
                File dataFolder = Slimefun.instance().getDataFolder();

                if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                    return null;
                }

                File logFile = new File(dataFolder, "warning.log");

                // append = true so warnings accumulate across restarts
                FileHandler handler = new FileHandler(logFile.getAbsolutePath(), true);
                handler.setFormatter(new Formatter() {
                    @Override
                    public String format(LogRecord record) {
                        return new Date(record.getMillis()) + " [" + record.getLevel() + "] " + formatMessage(record) + System.lineSeparator();
                    }
                });

                Logger logger = Logger.getLogger("SlimefunCore-Deprecations");
                logger.setUseParentHandlers(false); // Do NOT propagate to the console
                logger.addHandler(handler);

                fileLogger = logger;
                return fileLogger;
            } catch (IOException | RuntimeException x) {
                // If the file logger cannot be created we silently give up rather than spam the console.
                return null;
            }
        }
    }
}
