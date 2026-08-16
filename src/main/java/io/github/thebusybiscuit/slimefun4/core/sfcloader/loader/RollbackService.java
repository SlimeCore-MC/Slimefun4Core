package io.github.thebusybiscuit.slimefun4.core.sfcloader.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.bukkit.plugin.Plugin;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.*;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.*;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.export.*;

public class RollbackService {

    private final Plugin plugin;
    private final SFCLogger logger;
    private final ExportService exportService;
    private final Path backupDir;
    private final int maxBackups = 10;

    public RollbackService(@Nonnull Plugin plugin, @Nonnull SFCLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.exportService = new ExportService(plugin, logger);
        this.backupDir = Paths.get(plugin.getDataFolder().getPath(), "sfcloader", "cache", "pre-restore");
        try {
            Files.createDirectories(this.backupDir);
        } catch (IOException e) {
            logger.error("Failed to create pre-restore backup directory", e);
        }
    }

    @Nonnull
    public Path createPreRestoreBackup() throws IOException {
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        Path backupPath = backupDir.resolve(timestamp + ".loader");
        
        exportService.exportSnapshot(backupPath);
        pruneOldBackups();
        
        return backupPath;
    }

    private void pruneOldBackups() {
        if (!Files.exists(backupDir)) return;
        
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> backups = files
                .filter(p -> p.getFileName().toString().endsWith(".loader"))
                .sorted(Comparator.comparing(p -> p.toFile().lastModified(), Comparator.reverseOrder()))
                .collect(Collectors.toList());

            if (backups.size() > maxBackups) {
                for (int i = maxBackups; i < backups.size(); i++) {
                    Files.deleteIfExists(backups.get(i));
                }
            }
        } catch (IOException e) {
            logger.error("Failed to prune old pre-restore backups", e);
        }
    }

    @Nonnull
    public Optional<Path> getLatestBackup() {
        if (!Files.exists(backupDir)) return Optional.empty();
        
        try (Stream<Path> files = Files.list(backupDir)) {
            return files
                .filter(p -> p.getFileName().toString().endsWith(".loader"))
                .max(Comparator.comparing(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            logger.error("Failed to get latest pre-restore backup", e);
            return Optional.empty();
        }
    }

    @Nonnull
    public RestoreResult rollback() {
        Optional<Path> latest = getLatestBackup();
        if (latest.isEmpty()) {
            return new RestoreResult(false, List.of(), List.of(), "No pre-restore backup available to rollback to.");
        }
        
        try {
            Path extractedDir = Paths.get(plugin.getDataFolder().getPath(), "sfcloader", "cache", "rollback-staging");
            if (Files.exists(extractedDir)) {
                FileUtil.deleteDirectory(extractedDir); // Assuming utility method
            }
            Files.createDirectories(extractedDir);
            
            ZipUtil.extract(latest.get(), extractedDir); // Assuming utility method
            
            // Re-validate and parse manifest
            LoaderValidator validator = new LoaderValidator();
            LoaderValidator.ValidationResult validation = validator.validate(latest.get());
            
            if (!validation.valid() || validation.manifest() == null) {
                return new RestoreResult(false, List.of(), List.of(), "Backup validation failed.");
            }
            
            RestoreExecutor executor = new RestoreExecutor(plugin, logger);
            return executor.restore(extractedDir, validation.manifest());
            
        } catch (Exception e) {
            logger.error("Failed during rollback", e);
            return new RestoreResult(false, List.of(), List.of(), "Exception during rollback: " + e.getMessage());
        }
    }
}
