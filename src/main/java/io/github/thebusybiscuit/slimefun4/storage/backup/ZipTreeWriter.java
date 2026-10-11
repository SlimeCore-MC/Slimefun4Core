package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes files and directory trees into a {@link ZipOutputStream} byte-for-byte.
 *
 * <p>Entries are added in a stable, name-sorted order so that two archives taken
 * from identical trees are structurally identical. Missing or unreadable paths are
 * skipped rather than throwing, which lets a backup of a partially populated data
 * folder still succeed.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class ZipTreeWriter {

    private static final int BUFFER_SIZE = 8192;

    private ZipTreeWriter() {}

    /**
     * Recursively adds every regular file below {@code directory} to the archive.
     *
     * @param output    The open ZIP stream to write into
     * @param directory The directory to walk, may be absent
     * @param zipPath   The path prefix to use inside the archive
     * @return The number of file entries written
     * @throws IOException If writing to the archive fails
     */
    public static int addDirectory(@Nonnull ZipOutputStream output, @Nonnull File directory, @Nonnull String zipPath) throws IOException {
        if (!directory.isDirectory()) {
            return 0;
        }

        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }

        Arrays.sort(children, Comparator.comparing(File::getName));

        int written = 0;
        byte[] buffer = new byte[BUFFER_SIZE];

        for (File child : children) {
            String childPath = zipPath.isEmpty() ? child.getName() : zipPath + '/' + child.getName();

            if (child.isDirectory()) {
                written += addDirectory(output, child, childPath);
            } else if (child.isFile() && !child.getName().equals(".slimefun-empty")) {
                written += copyInto(output, child, childPath, buffer);
            }
        }

        return written;
    }

    /**
     * Adds a single file to the archive.
     *
     * @param output  The open ZIP stream to write into
     * @param file    The file to add, may be absent
     * @param zipPath The full entry path inside the archive
     * @return {@code 1} if the file was written, {@code 0} if it was absent
     * @throws IOException If writing to the archive fails
     */
    public static int addFile(@Nonnull ZipOutputStream output, @Nonnull File file, @Nonnull String zipPath) throws IOException {
        if (!file.isFile()) {
            return 0;
        }

        return copyInto(output, file, zipPath, new byte[BUFFER_SIZE]);
    }

    private static int copyInto(@Nonnull ZipOutputStream output, @Nonnull File file, @Nonnull String zipPath, @Nonnull byte[] buffer) throws IOException {
        ZipEntry entry = new ZipEntry(zipPath);
        entry.setTime(file.lastModified());
        output.putNextEntry(entry);

        try (FileInputStream input = new FileInputStream(file)) {
            int length;

            while ((length = input.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }
        }

        output.closeEntry();
        return 1;
    }
}
