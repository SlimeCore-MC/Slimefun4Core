package io.github.thebusybiscuit.slimefun4.core.sfcloader.export;

import io.github.thebusybiscuit.slimefun4.core.sfcloader.model.CapturedFile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.bukkit.plugin.Plugin;

public class SettingsCollector {

    private final Plugin plugin;

    public SettingsCollector(@Nonnull Plugin plugin) {
        this.plugin = plugin;
    }

    @Nonnull
    public List<CapturedFile> collectServerFiles(@Nonnull Path stagingDir) {
        List<CapturedFile> captured = new ArrayList<>();
        File serverRoot = plugin.getServer().getWorldContainer();
        if (serverRoot == null) {
            serverRoot = new File(".");
        }
        Path rootPath = serverRoot.toPath();
        Path targetServerDir = stagingDir.resolve("server");

        String[] targetFiles = {
            "server.properties",
            "bukkit.yml",
            "spigot.yml",
            "paper-global.yml",
            "paper-world-defaults.yml"
        };

        for (String fileName : targetFiles) {
            Path sourceFile = rootPath.resolve(fileName);
            Path targetFile = targetServerDir.resolve(fileName);
            
            if (Files.exists(sourceFile) && Files.isRegularFile(sourceFile)) {
                try {
                    Files.createDirectories(targetServerDir);
                    Files.copy(sourceFile, targetFile);
                    long size = Files.size(targetFile);
                    captured.add(new CapturedFile("server/" + fileName, size, "captured"));
                } catch (IOException e) {
                    captured.add(new CapturedFile("server/" + fileName, 0, "error"));
                }
            } else {
                captured.add(new CapturedFile("server/" + fileName, 0, "not_present"));
            }
        }
        
        return captured;
    }

    @Nonnull
    public List<CapturedFile> collectSlimefunFiles(@Nonnull Path stagingDir) {
        List<CapturedFile> captured = new ArrayList<>();
        Path sfDir = plugin.getDataFolder().toPath();
        Path targetSfDir = stagingDir.resolve("slimefun");
        
        if (Files.exists(sfDir) && Files.isDirectory(sfDir)) {
            try (Stream<Path> paths = Files.list(sfDir)) {
                paths.filter(p -> p.getFileName().toString().endsWith(".yml"))
                     .forEach(sourceFile -> {
                         String fileName = sourceFile.getFileName().toString();
                         Path targetFile = targetSfDir.resolve(fileName);
                         try {
                             Files.createDirectories(targetSfDir);
                             Files.copy(sourceFile, targetFile);
                             long size = Files.size(targetFile);
                             captured.add(new CapturedFile("slimefun/" + fileName, size, "captured"));
                         } catch (IOException e) {
                             captured.add(new CapturedFile("slimefun/" + fileName, 0, "error"));
                         }
                     });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        // Ensure standard files are at least reported as not_present if missing
        String[] standardSfFiles = {"config.yml", "database.yml", "addonmanager.yml"};
        for (String fileName : standardSfFiles) {
            if (captured.stream().noneMatch(c -> c.path().equals("slimefun/" + fileName))) {
                captured.add(new CapturedFile("slimefun/" + fileName, 0, "not_present"));
            }
        }

        return captured;
    }
}
