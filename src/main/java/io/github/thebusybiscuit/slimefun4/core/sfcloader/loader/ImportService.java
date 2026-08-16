package io.github.thebusybiscuit.slimefun4.core.sfcloader.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

import javax.annotation.Nonnull;

import org.bukkit.plugin.Plugin;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.*;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.*;

public class ImportService {

    private final Plugin plugin;
    private final SFCLogger logger;
    private final RollbackService rollbackService;
    private final LoaderValidator validator;
    private final RestoreExecutor restoreExecutor;
    
    private Path stagedLoaderFile = null;

    public ImportService(@Nonnull Plugin plugin, @Nonnull SFCLogger logger, @Nonnull RollbackService rollbackService) {
        this.plugin = plugin;
        this.logger = logger;
        this.rollbackService = rollbackService;
        this.validator = new LoaderValidator();
        this.restoreExecutor = new RestoreExecutor(plugin, logger);
    }

    @Nonnull
    public RestoreResult importLoader(@Nonnull Path loaderFile, boolean requireConfirmation) {
        if (requireConfirmation) {
            stage(loaderFile);
            return new RestoreResult(true, List.of(), List.of(), "Loader staged for confirmation.");
        }
        
        logger.info("Starting import of loader: " + loaderFile.getFileName());
        LoaderValidator.ValidationResult validation = validator.validate(loaderFile);
        
        if (!validation.valid() || validation.manifest() == null) {
            logger.error("Validation failed for loader: " + loaderFile.getFileName());
            return new RestoreResult(false, List.of(), List.of(), "Validation failed: " + String.join(", ", validation.errors()));
        }
        
        Path stagingDir = Paths.get(plugin.getDataFolder().getPath(), "sfcloader", "cache", "staging");
        RestoreResult result;
        try {
            logger.info("Creating pre-restore backup...");
            rollbackService.createPreRestoreBackup();
            
            if (Files.exists(stagingDir)) {
                FileUtil.deleteDirectory(stagingDir);
            }
            Files.createDirectories(stagingDir);
            
            logger.info("Extracting loader to staging directory...");
            ZipUtil.extract(loaderFile, stagingDir);
            
            logger.info("Executing restore...");
            result = restoreExecutor.restore(stagingDir, validation.manifest());
            
            if (result.success()) {
                Path appliedDir = Paths.get(plugin.getDataFolder().getPath(), "sfcloader", "imports", "applied");
                Files.createDirectories(appliedDir);
                
                String timestamp = String.valueOf(Instant.now().toEpochMilli());
                Path targetAppliedFile = appliedDir.resolve(timestamp + "-" + loaderFile.getFileName().toString());
                
                Files.move(loaderFile, targetAppliedFile, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Successfully applied loader and moved to: " + targetAppliedFile.toString());
            } else {
                logger.error("Restore failed: " + result.message());
            }
            
        } catch (Exception e) {
            logger.error("Exception occurred during import", e);
            result = new RestoreResult(false, List.of(), List.of(), "Exception during import: " + e.getMessage());
        } finally {
            try {
                if (Files.exists(stagingDir)) {
                    FileUtil.deleteDirectory(stagingDir);
                }
            } catch (Exception e) {
                logger.error("Failed to clean up staging directory", e);
            }
        }
        
        return result;
    }

    @Nonnull
    public String preview(@Nonnull Path loaderFile) {
        LoaderValidator.ValidationResult validation = validator.validate(loaderFile);
        
        if (!validation.valid() || validation.manifest() == null) {
            return "Cannot preview. Validation failed:\n- " + String.join("\n- ", validation.errors());
        }
        
        LoaderManifest manifest = validation.manifest();
        StringBuilder summary = new StringBuilder();
        summary.append("Loader Preview: ").append(manifest.getName() != null ? manifest.getName() : "Unknown").append("\n");
        summary.append("Format Version: ").append(manifest.getFormatVersion()).append("\n");
        
        if (manifest.getFiles() != null) {
            summary.append("Contains ").append(manifest.getFiles().size()).append(" files:\n");
            for (CapturedFile cf : manifest.getFiles()) {
                summary.append("- ").append(cf.path()).append("\n");
            }
        }
        
        return summary.toString();
    }

    public void stage(@Nonnull Path loaderFile) {
        this.stagedLoaderFile = loaderFile;
        logger.info("Staged loader for confirmation: " + loaderFile.getFileName());
    }

    @Nonnull
    public RestoreResult confirmStaged() {
        if (this.stagedLoaderFile == null) {
            return new RestoreResult(false, List.of(), List.of(), "No loader is currently staged for confirmation.");
        }
        
        if (!Files.exists(this.stagedLoaderFile)) {
            RestoreResult result = new RestoreResult(false, List.of(), List.of(), "Staged loader file no longer exists.");
            this.stagedLoaderFile = null;
            return result;
        }
        
        Path target = this.stagedLoaderFile;
        this.stagedLoaderFile = null; // Clear staged after confirming
        
        return importLoader(target, false);
    }
}
