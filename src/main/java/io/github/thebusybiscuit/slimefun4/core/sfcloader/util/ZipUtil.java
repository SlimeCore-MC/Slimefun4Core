package io.github.thebusybiscuit.slimefun4.core.sfcloader.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.annotation.Nonnull;

public final class ZipUtil {

    private ZipUtil() {}

    public static void createZip(@Nonnull Path outputZip, @Nonnull Path sourceDir) throws IOException {
        if (Files.notExists(outputZip.getParent())) {
            Files.createDirectories(outputZip.getParent());
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputZip));
             Stream<Path> paths = Files.walk(sourceDir)) {
            
            paths.filter(path -> !Files.isDirectory(path))
                 .forEach(path -> {
                     ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString().replace('\\', '/'));
                     try {
                         zos.putNextEntry(zipEntry);
                         Files.copy(path, zos);
                         zos.closeEntry();
                     } catch (IOException e) {
                         throw new RuntimeException("Failed to zip file " + path, e);
                     }
                 });
        }
    }

    public static void extract(@Nonnull Path zipFile, @Nonnull Path targetDir) throws IOException {
        if (Files.notExists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                Path newPath = targetDir.resolve(zipEntry.getName()).normalize();
                
                // ZIP-SLIP protection
                if (!newPath.startsWith(targetDir.normalize())) {
                    throw new IOException("Bad zip entry (ZIP-SLIP): " + zipEntry.getName());
                }
                
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(newPath);
                } else {
                    if (Files.notExists(newPath.getParent())) {
                        Files.createDirectories(newPath.getParent());
                    }
                    Files.copy(zis, newPath);
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }
}
