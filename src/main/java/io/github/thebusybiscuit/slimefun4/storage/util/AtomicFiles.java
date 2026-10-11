package io.github.thebusybiscuit.slimefun4.storage.util;

import io.github.bakedlibs.dough.config.Config;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Durable file replacement primitives shared by every component that rewrites a YAML
 * file in place.
 *
 * <p>A write goes to a sibling temporary file first and is then moved over the target,
 * so a crash mid-write can never leave a half-written player or block file behind.
 *
 * @author SlimefunCore Contributors
 */
public final class AtomicFiles {

    private AtomicFiles() {}

    /**
     * Moves a file over another, preferring an atomic move where the filesystem supports it.
     *
     * @param source The file to move
     * @param target The file to replace
     * @throws IOException If neither move succeeds
     */
    public static void moveReplace(@Nonnull File source, @Nonnull File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Saves a {@link Config} to its own file through a temporary file.
     *
     * <p>The temporary file is removed again if anything fails, and the target is left
     * exactly as it was.
     *
     * @param config The config to write
     * @throws IOException If the file could not be written or moved into place
     */
    public static void saveConfig(@Nonnull Config config) throws IOException {
        File targetFile = config.getFile();
        File parent = targetFile.getParentFile();
        File tmpFile = new File(parent, targetFile.getName() + ".tmp");

        try {
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }

            config.save(tmpFile);
            moveReplace(tmpFile, targetFile);
        } catch (Exception x) {
            try {
                Files.deleteIfExists(tmpFile.toPath());
            } catch (IOException ignored) {
            }

            if (x instanceof IOException io) {
                throw io;
            }

            throw new IOException("Could not save \"" + targetFile.getName() + '"', x);
        }
    }
}
