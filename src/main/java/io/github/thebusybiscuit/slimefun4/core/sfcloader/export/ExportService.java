package io.github.thebusybiscuit.slimefun4.core.sfcloader.export;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.CapturedFile;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.LoaderManifest;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.SFCLogger;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.ZipUtil;
import io.github.thebusybiscuit.slimefun4.core.sfcloader.util.FileUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.bukkit.plugin.Plugin;

public class ExportService {

    private final Plugin plugin;
    private final SFCLogger logger;
    private final Path exportsDir;

    public ExportService(@Nonnull Plugin plugin, @Nonnull SFCLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.exportsDir = plugin.getDataFolder().toPath().resolve("sfcloader").resolve("exports");
    }

    @Nonnull
    public Path export(@Nonnull String filename) throws IOException {
        Path stagingDir = Files.createTempDirectory("sfcloader-export-");
        try {
            logger.log("Starting export to %s", filename);
            SettingsCollector collector = new SettingsCollector(plugin);
            
            List<CapturedFile> allFiles = new ArrayList<>();
            allFiles.addAll(collector.collectServerFiles(stagingDir));
            allFiles.addAll(collector.collectSlimefunFiles(stagingDir));

            ManifestBuilder builder = new ManifestBuilder();
            LoaderManifest manifest = builder.build(plugin, allFiles);

            Path manifestFile = stagingDir.resolve("manifest.json");
            Files.writeString(manifestFile, manifest.toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            if (Files.notExists(exportsDir)) {
                Files.createDirectories(exportsDir);
            }

            Path loaderFile = exportsDir.resolve(filename + ".loader");
            ZipUtil.createZip(loaderFile, stagingDir);

            String summary = SummaryRenderer.render(manifest);
            Path summaryFile = exportsDir.resolve(filename + ".loader.summary.txt");
            Files.writeString(summaryFile, summary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            logger.log("Successfully exported to %s", loaderFile.getFileName().toString());
            return loaderFile;
        } finally {
            FileUtil.deleteDirectory(stagingDir);
        }
    }

    @Nonnull
    public Path exportSnapshot(@Nonnull Path targetPath) throws IOException {
        Path stagingDir = Files.createTempDirectory("sfcloader-snapshot-");
        try {
            logger.log("Starting snapshot to %s", targetPath.getFileName());
            SettingsCollector collector = new SettingsCollector(plugin);
            
            List<CapturedFile> allFiles = new ArrayList<>();
            allFiles.addAll(collector.collectServerFiles(stagingDir));
            allFiles.addAll(collector.collectSlimefunFiles(stagingDir));

            ManifestBuilder builder = new ManifestBuilder();
            LoaderManifest manifest = builder.build(plugin, allFiles);

            Path manifestFile = stagingDir.resolve("manifest.json");
            Files.writeString(manifestFile, manifest.toJson(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            if (Files.notExists(targetPath.getParent())) {
                Files.createDirectories(targetPath.getParent());
            }

            ZipUtil.createZip(targetPath, stagingDir);

            logger.log("Successfully exported snapshot to %s", targetPath.getFileName().toString());
            return targetPath;
        } finally {
            FileUtil.deleteDirectory(stagingDir);
        }
    }
}
