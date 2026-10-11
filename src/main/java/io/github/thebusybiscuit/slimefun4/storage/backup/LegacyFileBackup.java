package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates and restores byte-exact archives of the legacy YAML data tree.
 *
 * <p>The legacy backend has no SQL connection to dump, so its backup is a ZIP of
 * the live files themselves. Nothing is re-serialized, which means no
 * {@code ItemStack} can be lost or downgraded in translation.
 *
 * <p>Both player data and block data are captured:
 * {@code Players/}, {@code waypoints/}, {@code stored-blocks/}, {@code stored-inventories/},
 * {@code universal-inventories/} and {@code stored-chunks/chunks.sfc}.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class LegacyFileBackup {

    /**
     * The default root of the legacy data tree, relative to the server directory.
     */
    public static final String DEFAULT_ROOT = "data-storage/Slimefun";

    private static final List<String> DIRECTORY_SECTIONS = List.of(
        "Players",
        "waypoints",
        "stored-blocks",
        "stored-inventories",
        "universal-inventories"
    );

    private static final String CHUNK_SECTION = "stored-chunks";
    private static final String CHUNK_FILE = "chunks.sfc";

    private LegacyFileBackup() {}

    /**
     * Returns the section names captured by a legacy archive, in archive order.
     *
     * @return An immutable list of section names
     */
    @Nonnull
    public static List<String> getSections() {
        List<String> sections = new ArrayList<>(DIRECTORY_SECTIONS);
        sections.add(CHUNK_SECTION);
        return List.copyOf(sections);
    }

    /**
     * Returns the default legacy data root.
     *
     * @return The {@code data-storage/Slimefun} directory
     */
    @Nonnull
    public static File defaultRoot() {
        return new File(DEFAULT_ROOT);
    }

    /**
     * Writes a ZIP archive of the legacy data tree.
     *
     * @param archive The archive file to create
     * @param root    The legacy data root to read from
     * @return A map of section name to the number of files captured for it
     * @throws IOException If the archive could not be written
     */
    @Nonnull
    public static Map<String, Integer> writeArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return writeArchive(archive, root, DIRECTORY_SECTIONS, true);
    }

    @Nonnull
    static Map<String, Integer> writeArchive(
            @Nonnull File archive,
            @Nonnull File root,
            @Nonnull List<String> directorySections,
            boolean includeChunks) throws IOException {

        Map<String, Integer> counts = new LinkedHashMap<>();
        File parent = archive.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            for (String section : directorySections) {
                int count = ZipTreeWriter.addDirectory(output, new File(root, section), section);
                counts.put(section, count);

                if (count == 0) {
                    output.putNextEntry(new ZipEntry(section + "/.slimefun-empty"));
                    output.closeEntry();
                }
            }

            if (includeChunks) {
                File chunks = new File(new File(root, CHUNK_SECTION), CHUNK_FILE);
                int count = ZipTreeWriter.addFile(output, chunks, CHUNK_SECTION + '/' + CHUNK_FILE);
                counts.put(CHUNK_SECTION, count);

                if (count == 0) {
                    output.putNextEntry(new ZipEntry(CHUNK_SECTION + "/.slimefun-empty"));
                    output.closeEntry();
                }
            }
        }

        return counts;
    }

    /**
     * Restores a legacy archive over the given data root.
     *
     * <p>Entries are first unpacked into a staging directory on the same volume so
     * that a malformed or truncated archive cannot leave the live tree half
     * overwritten. Any entry whose resolved path escapes the staging directory is
     * rejected outright.
     *
     * @param archive The archive to restore
     * @param root    The legacy data root to write into
     * @return The number of files restored
     * @throws IOException If the archive is unusable or the tree could not be replaced
     */
    public static int extractArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return extractArchive(archive, root, DIRECTORY_SECTIONS, true);
    }

    static int extractArchive(
            @Nonnull File archive,
            @Nonnull File root,
            @Nonnull List<String> directorySections,
            boolean includeChunks) throws IOException {

        Path staging = createStagingDirectory(root);

        try {
            int extracted = unzipInto(archive, staging);

            boolean emptyState = hasEmptyStateMarker(staging, directorySections, includeChunks);

            if (extracted == 0 && !emptyState) {
                throw new IOException("Backup archive contains no restorable entries: " + archive.getName());
            }

            Files.createDirectories(root.toPath());

            for (String section : directorySections) {
                File staged = new File(staging.toFile(), section);
                File target = new File(root, section);

                if (staged.isDirectory()) {
                    Files.deleteIfExists(new File(staged, ".slimefun-empty").toPath());
                    replaceDirectory(staged, target);
                } else {
                    deleteRecursivelyStrict(target);
                }
            }

            if (includeChunks) {
                File stagedChunks = new File(new File(staging.toFile(), CHUNK_SECTION), CHUNK_FILE);
                File marker = new File(new File(staging.toFile(), CHUNK_SECTION), ".slimefun-empty");
                File targetDirectory = new File(root, CHUNK_SECTION);
                File target = new File(targetDirectory, CHUNK_FILE);

                if (stagedChunks.isFile()) {
                    Files.createDirectories(targetDirectory.toPath());
                    moveReplace(stagedChunks, target);
                } else if (marker.isFile()) {
                    Files.deleteIfExists(target.toPath());
                }
            }

            return extracted;
        } finally {
            deleteRecursively(staging.toFile());
        }
    }

    /**
     * Counts the entries a legacy archive would restore without touching disk.
     *
     * @param archive The archive to inspect
     * @return The number of file entries, or {@code -1} if the archive is unreadable
     */
    public static int countEntries(@Nonnull File archive) {
        if (!archive.isFile()) {
            return -1;
        }

        int entries = 0;

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().endsWith("/.slimefun-empty")) {
                    entries++;
                }

                input.closeEntry();
            }
        } catch (IOException x) {
            return -1;
        }

        return entries;
    }

    private static int unzipInto(@Nonnull File archive, @Nonnull Path destination) throws IOException {
        Path base = destination.toAbsolutePath().normalize();
        int extracted = 0;

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                Path resolved = base.resolve(entry.getName()).toAbsolutePath().normalize();

                if (!resolved.startsWith(base)) {
                    throw new IOException("Refusing to extract entry outside of the backup target: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else if (entry.getName().endsWith("/.slimefun-empty")) {
                    Files.createDirectories(resolved.getParent());
                    Files.createFile(resolved);
                } else {
                    Path parent = resolved.getParent();

                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    Files.copy(input, resolved, StandardCopyOption.REPLACE_EXISTING);
                    extracted++;
                }

                input.closeEntry();
            }
        }

        return extracted;
    }

    private static boolean hasEmptyStateMarker(@Nonnull Path staging,
            @Nonnull List<String> directorySections, boolean includeChunks) {
        for (String section : directorySections) {
            if (Files.isRegularFile(staging.resolve(section).resolve(".slimefun-empty"))) {
                return true;
            }
        }

        return includeChunks
            && Files.isRegularFile(staging.resolve(CHUNK_SECTION).resolve(".slimefun-empty"));
    }

    @Nonnull
    private static Path createStagingDirectory(@Nonnull File root) throws IOException {
        File parent = root.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
            return Files.createTempDirectory(parent.toPath(), ".slimefun-restore-");
        }

        return Files.createTempDirectory(".slimefun-restore-");
    }

    private static void replaceDirectory(@Nonnull File staged, @Nonnull File target) throws IOException {
        deleteRecursively(target);
        File parent = target.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try {
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            copyRecursively(staged, target);
        }
    }

    private static void moveReplace(@Nonnull File source, @Nonnull File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyRecursively(@Nonnull File source, @Nonnull File target) throws IOException {
        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();

        try (Stream<Path> stream = Files.walk(sourcePath)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path destination = targetPath.resolve(sourcePath.relativize(path).toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();

                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursivelyStrict(@Nonnull File file) throws IOException {
        if (!file.exists()) {
            return;
        }

        try (Stream<Path> stream = Files.walk(file.toPath())) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            return;
        }

        try (Stream<Path> stream = Files.walk(file.toPath())) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
