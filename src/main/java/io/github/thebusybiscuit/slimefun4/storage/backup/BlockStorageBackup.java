package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates and restores byte-exact archives of the block data tree.
 *
 * <p>Block data was never moved to the storage layer: machines, stored inventories and
 * chunk data live in YAML under {@code data-storage/Slimefun} on every provider, MySQL
 * included. A SQL row dump therefore captures none of it, which left servers on a SQL
 * provider with no block backup at all beyond the shutdown-time archive written by
 * {@code BackupService}, which is not listed, checksummed or restorable.
 *
 * <p>This archive covers exactly the block sections and nothing else, so it can be taken
 * on any provider and restored on its own. The staging, zip-slip rejection and replace
 * logic is shared with {@link LegacyFileBackup} rather than duplicated.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class BlockStorageBackup {

    private static final List<String> DIRECTORY_SECTIONS = List.of(
        "stored-blocks",
        "stored-inventories",
        "universal-inventories"
    );

    private static final String CHUNK_SECTION = "stored-chunks";
    private static final String CHUNK_FILE = "chunks.sfc";

    private BlockStorageBackup() {}

    /**
     * Returns the section names captured by a block archive, in archive order.
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
     * Returns the data root block files live under.
     *
     * @return The {@code data-storage/Slimefun} directory
     */
    @Nonnull
    public static File defaultRoot() {
        return LegacyFileBackup.defaultRoot();
    }

    /**
     * Writes a ZIP archive of the block data tree.
     *
     * @param archive The archive file to create
     * @param root    The data root to read from
     * @return A map of section name to the number of files captured for it
     * @throws IOException If the archive could not be written
     */
    @Nonnull
    public static Map<String, Integer> writeArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return LegacyFileBackup.writeArchive(archive, root, DIRECTORY_SECTIONS, true);
    }

    /**
     * Restores a block archive over the given data root.
     *
     * <p>Player data is left untouched: only the block sections are replaced.
     *
     * @param archive The archive to restore
     * @param root    The data root to write into
     * @return The number of files restored
     * @throws IOException If the archive is unusable or the tree could not be replaced
     */
    public static int extractArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return LegacyFileBackup.extractArchive(archive, root, DIRECTORY_SECTIONS, true);
    }

    /**
     * Reports whether a data root holds any block data at all.
     *
     * <p>Taking an archive of an empty tree fails, because an empty capture is treated as a
     * failed backup everywhere else. A brand new server legitimately has no block data yet,
     * so callers check this first and skip the archive rather than aborting.
     *
     * @param root The data root to inspect
     * @return {@code true} when at least one block file exists
     */
    public static boolean hasData(@Nonnull File root) {
        if (!root.isDirectory()) {
            return false;
        }

        for (String section : DIRECTORY_SECTIONS) {
            if (containsFile(new File(root, section))) {
                return true;
            }
        }

        return new File(new File(root, CHUNK_SECTION), CHUNK_FILE).isFile();
    }

    private static boolean containsFile(@Nonnull File directory) {
        File[] children = directory.listFiles();

        if (children == null) {
            return false;
        }

        for (File child : children) {
            if (child.isDirectory() ? containsFile(child)
                    : child.isFile() && !child.getName().equals(".slimefun-empty")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Counts the entries a block archive would restore without touching disk.
     *
     * @param archive The archive to inspect
     * @return The number of file entries, or {@code -1} if the archive is unreadable
     */
    public static int countEntries(@Nonnull File archive) {
        return LegacyFileBackup.countEntries(archive);
    }
}
