package io.github.thebusybiscuit.slimefun4.storage.backup;

import com.google.common.annotations.Beta;

import javax.annotation.Nonnull;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Creates and restores byte-exact archives of the player data tree.
 *
 * <p>This archive is the legacy backend's counterpart to a {@link BackupFormat#SQL_JSON} row
 * dump. It carries exactly the data a SQL provider keeps in its tables — researches,
 * backpacks, backpack items and waypoints — and nothing else. Block data is deliberately
 * excluded: it is never database-owned and is covered by {@link BlockStorageBackup}.
 *
 * <p>Restoring replaces the {@code Players} and {@code waypoints} sections only. The staging,
 * zip-slip rejection and replace logic is shared with {@link LegacyFileBackup} rather than
 * duplicated.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class PlayerFileBackup {

    private static final List<String> DIRECTORY_SECTIONS = List.of(
        "Players",
        "waypoints"
    );

    private PlayerFileBackup() {}

    /**
     * Returns the section names captured by a player archive, in archive order.
     *
     * @return An immutable list of section names
     */
    @Nonnull
    public static List<String> getSections() {
        return DIRECTORY_SECTIONS;
    }

    /**
     * Returns the data root player files live under.
     *
     * @return The {@code data-storage/Slimefun} directory
     */
    @Nonnull
    public static File defaultRoot() {
        return LegacyFileBackup.defaultRoot();
    }

    /**
     * Writes a ZIP archive of the player data tree.
     *
     * @param archive The archive file to create
     * @param root    The data root to read from
     * @return A map of section name to the number of files captured for it
     * @throws IOException If the archive could not be written
     */
    @Nonnull
    public static Map<String, Integer> writeArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return LegacyFileBackup.writeArchive(archive, root, DIRECTORY_SECTIONS, false);
    }

    /**
     * Restores a player archive over the given data root.
     *
     * <p>Block data is left untouched: only the player sections are replaced.
     *
     * @param archive The archive to restore
     * @param root    The data root to write into
     * @return The number of files restored
     * @throws IOException If the archive is unusable or the tree could not be replaced
     */
    public static int extractArchive(@Nonnull File archive, @Nonnull File root) throws IOException {
        return LegacyFileBackup.extractArchive(archive, root, DIRECTORY_SECTIONS, false);
    }

    /**
     * Reports whether a data root holds any player data at all.
     *
     * <p>Taking an archive of an empty tree fails, because an empty capture is treated as a
     * failed backup everywhere else. A brand new server legitimately has no player data yet,
     * so callers check this first and skip the archive rather than aborting.
     *
     * @param root The data root to inspect
     * @return {@code true} when at least one player file exists
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

        return false;
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
     * Counts the entries a player archive would restore without touching disk.
     *
     * @param archive The archive to inspect
     * @return The number of file entries, or {@code -1} if the archive is unreadable
     */
    public static int countEntries(@Nonnull File archive) {
        return LegacyFileBackup.countEntries(archive);
    }
}
