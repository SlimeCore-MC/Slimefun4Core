package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bukkit.Server;
import org.bukkit.World;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Creates and restores byte-exact archives of Minecraft world folders.
 *
 * <p>Unlike {@link LegacyFileBackup} and {@link BlockStorageBackup}, which archive sections of a
 * single Slimefun data root, this archives whole top-level world folders under the server world
 * container. Each world is stored under its own name prefix inside one ZIP
 * ({@code world/}, {@code world_nether/}, {@code world_the_end/}).
 *
 * <p>Volatile lock files ({@code session.lock} and any {@code *.lock}) are excluded so a restored
 * world never carries a stale lock from the machine it was captured on.
 *
 * <p>This class never touches a loaded world. Restore only unpacks into a staging directory; the
 * staged files are moved over the live folders on the next startup, before the server loads them.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class WorldBackup {

    /**
     * Fixed name of the world archive inside a snapshot directory.
     */
    public static final String ARCHIVE_FILE = "worlds.zip";

    private static final int BUFFER_SIZE = 8192;
    private static final String NETHER_SUFFIX = "_nether";
    private static final String END_SUFFIX = "_the_end";

    private WorldBackup() {}

    /**
     * Resolves the world folders to capture.
     *
     * <p>When {@code configured} is empty the primary world and its {@code _nether} / {@code _the_end}
     * companions are auto-detected from the first loaded world. Otherwise the configured names are
     * resolved verbatim under the server world container.
     *
     * @param server     The running server
     * @param configured The configured world names, may be empty for auto-detect
     * @return The resolved world directories, in a stable order, only those that exist
     */
    @Nonnull
    public static List<File> resolveWorlds(@Nonnull Server server, @Nonnull List<String> configured) {
        File container = server.getWorldContainer();
        List<String> names = new ArrayList<>();

        if (configured.isEmpty()) {
            List<World> worlds = server.getWorlds();

            if (!worlds.isEmpty()) {
                String primary = worlds.get(0).getName();
                names.add(primary);
                names.add(primary + NETHER_SUFFIX);
                names.add(primary + END_SUFFIX);
            }
        } else {
            for (String name : configured) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }

        List<File> directories = new ArrayList<>();

        for (String name : names) {
            File directory = new File(container, name);

            if (directory.isDirectory()) {
                directories.add(directory);
            }
        }

        return directories;
    }

    /**
     * Reports whether any of the given world folders holds capturable data.
     *
     * @param worldDirectories The resolved world directories
     * @return {@code true} when at least one directory exists and is not empty
     */
    public static boolean hasData(@Nonnull List<File> worldDirectories) {
        for (File directory : worldDirectories) {
            if (directory.isDirectory()) {
                String[] children = directory.list();

                if (children != null && children.length > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Writes a ZIP archive of the given world folders.
     *
     * @param archive          The archive file to create
     * @param worldDirectories The world directories to capture, each stored under its own name prefix
     * @return A map of world name to the number of files captured for it
     * @throws IOException If the archive could not be written
     */
    @Nonnull
    public static Map<String, Integer> writeArchive(@Nonnull File archive, @Nonnull List<File> worldDirectories) throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        File parent = archive.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            byte[] buffer = new byte[BUFFER_SIZE];

            for (File world : worldDirectories) {
                String name = world.getName();
                int count = addDirectory(output, world, name, buffer);
                counts.put(name, count);
            }
        }

        return counts;
    }
    private static int addDirectory(@Nonnull ZipOutputStream output, @Nonnull File directory,
            @Nonnull String zipPath, @Nonnull byte[] buffer) throws IOException {
        File[] children = directory.listFiles();

        if (children == null) {
            return 0;
        }

        Arrays.sort(children, Comparator.comparing(File::getName));
        int written = 0;

        for (File child : children) {
            String childPath = zipPath + '/' + child.getName();

            if (child.isDirectory()) {
                written += addDirectory(output, child, childPath, buffer);
            } else if (child.isFile() && !isExcluded(child.getName())) {
                written += copyInto(output, child, childPath, buffer);
            }
        }

        return written;
    }

    private static boolean isExcluded(@Nonnull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.equals("session.lock") || lower.endsWith(".lock");
    }

    private static int copyInto(@Nonnull ZipOutputStream output, @Nonnull File file,
            @Nonnull String zipPath, @Nonnull byte[] buffer) throws IOException {
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

    /**
     * Counts the file entries a world archive holds without touching disk.
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
                if (!entry.isDirectory()) {
                    entries++;
                }

                input.closeEntry();
            }
        } catch (IOException x) {
            return -1;
        }

        return entries;
    }

    /**
     * Unpacks a world archive into a staging directory.
     *
     * <p>Any entry whose resolved path escapes the staging directory is rejected outright, so a
     * malformed archive can never write outside the target.
     *
     * @param archive The archive to unpack
     * @param staging The staging directory to unpack into, created if absent
     * @return The number of files unpacked
     * @throws IOException If the archive is unusable
     */
    public static int extractInto(@Nonnull File archive, @Nonnull File staging) throws IOException {
        Files.createDirectories(staging.toPath());
        Path base = staging.toPath().toAbsolutePath().normalize();
        int extracted = 0;

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                Path resolved = base.resolve(entry.getName()).toAbsolutePath().normalize();

                if (!resolved.startsWith(base)) {
                    throw new IOException("Refusing to extract entry outside of the world target: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
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

    /**
     * Moves every staged world folder over its live counterpart under the world container.
     *
     * <p>Only top-level directories of {@code staging} are treated as worlds; any other file
     * (such as a restore marker) is ignored. This must run before the server loads its worlds.
     *
     * @param staging   The staging directory holding the unpacked world folders
     * @param container The server world container to replace worlds in
     * @return The number of world folders replaced
     * @throws IOException If a world folder could not be replaced
     */
    public static int applyStagedWorlds(@Nonnull File staging, @Nonnull File container) throws IOException {
        File[] staged = staging.listFiles();

        if (staged == null) {
            return 0;
        }

        int replaced = 0;

        for (File world : staged) {
            if (!world.isDirectory()) {
                continue;
            }

            replaceDirectory(world, new File(container, world.getName()));
            replaced++;
        }

        return replaced;
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

    private static void copyRecursively(@Nonnull File source, @Nonnull File target) throws IOException {
        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();

        try (Stream<Path> stream = Files.walk(sourcePath)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path destination = targetPath.resolve(sourcePath.relativize(path).toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Path directoryParent = destination.getParent();

                    if (directoryParent != null) {
                        Files.createDirectories(directoryParent);
                    }

                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Recursively deletes a file or directory, ignoring individual failures.
     *
     * @param file The file or directory to delete, may be absent
     */
    public static void deleteRecursively(@Nullable File file) {
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
