package io.github.thebusybiscuit.slimefun4.core.sfcloader.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.annotation.Nonnull;

public class SFCLogger {

    private final Path logFile;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public SFCLogger(@Nonnull Path logFile) {
        this.logFile = logFile;
        try {
            if (Files.notExists(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void log(@Nonnull String message) {
        try {
            String timestampedMessage = "[" + formatter.format(Instant.now()) + "] " + message + System.lineSeparator();
            Files.writeString(logFile, timestampedMessage, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized void log(@Nonnull String format, @Nonnull Object... args) {
        log(String.format(format, args));
    }

    public synchronized void info(@Nonnull String message) {
        log("[INFO] " + message);
    }
    
    public synchronized void error(@Nonnull String message) {
        log("[ERROR] " + message);
    }
    
    public synchronized void error(@Nonnull String message, @Nonnull Throwable t) {
        log("[ERROR] " + message + " - " + t.getMessage());
        t.printStackTrace();
    }
}
