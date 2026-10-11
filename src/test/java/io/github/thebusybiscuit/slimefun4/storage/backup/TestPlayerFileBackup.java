package io.github.thebusybiscuit.slimefun4.storage.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the player archive, which is the database half of a checkpoint on the legacy backend.
 *
 * <p>It must carry exactly what a SQL row dump carries and nothing else. Block data belongs to
 * the storage half on every provider, so it must never appear in a player archive and must never
 * be touched when one is restored.
 */
class TestPlayerFileBackup {

    private static final String PLAYER = "3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30";

    @TempDir
    Path workspace;

    private File root;
    private File archive;

    @BeforeEach
    void setUp() throws IOException {
        root = workspace.resolve("Slimefun").toFile();
        archive = workspace.resolve("player-archive.zip").toFile();

        write("Players/" + PLAYER + ".yml", "researches: [1, 2]");
        write("waypoints/" + PLAYER + ".yml", "0: world;1.0;2.0;3.0");
        write("stored-blocks/world/0_0.sfb", "id: ENHANCED_FURNACE");
        write("stored-inventories/world;10;20;30.sfi", "slot0: DIAMOND");
        write("universal-inventories/9f11-6d8e.sfi", "slot0: EMERALD");
        write("stored-chunks/chunks.sfc", "world;0;0: {}");
    }

    @Test
    @DisplayName("Only the player owned sections are captured")
    void archiveHoldsPlayerSectionsOnly() throws IOException {
        Map<String, Integer> counts = PlayerFileBackup.writeArchive(archive, root);

        assertEquals(List.of("Players", "waypoints"), List.copyOf(counts.keySet()));
        assertEquals(1, counts.get("Players"));
        assertEquals(1, counts.get("waypoints"));
        assertFalse(counts.containsKey("stored-chunks"), "The chunk index belongs to the storage half");
        assertEquals(2, PlayerFileBackup.countEntries(archive));
        assertTrue(archive.length() > 0L);
    }

    @Test
    @DisplayName("No block data ever reaches a player archive")
    void blockDataIsNeverCaptured() throws IOException {
        PlayerFileBackup.writeArchive(archive, root);

        List<String> entries = entryNames();

        assertEquals(2, entries.size());

        for (String name : entries) {
            assertTrue(name.startsWith("Players/") || name.startsWith("waypoints/"),
                "Unexpected archive entry: " + name);
        }

        for (String section : List.of("stored-blocks", "stored-inventories", "universal-inventories", "stored-chunks")) {
            for (String name : entries) {
                assertFalse(name.startsWith(section), "Block data leaked into the player archive: " + name);
            }
        }
    }

    @Test
    @DisplayName("A restore replaces player data and leaves block data alone")
    void restoreLeavesBlockDataUntouched() throws IOException {
        Map<String, byte[]> original = snapshot();
        PlayerFileBackup.writeArchive(archive, root);

        write("Players/" + PLAYER + ".yml", "corrupted");
        write("Players/stray.yml", "stray: true");
        write("stored-blocks/world/1_1.sfb", "id: LATER_BLOCK");

        int restored = PlayerFileBackup.extractArchive(archive, root);

        assertEquals(2, restored);
        assertArrayEquals(original.get("Players/" + PLAYER + ".yml"), read("Players/" + PLAYER + ".yml"),
            "The player file must come back byte for byte");
        assertArrayEquals(original.get("waypoints/" + PLAYER + ".yml"), read("waypoints/" + PLAYER + ".yml"));
        assertFalse(new File(root, "Players/stray.yml").exists(), "A restored section replaces the live one");

        for (String section : List.of("stored-blocks", "stored-inventories", "universal-inventories", "stored-chunks")) {
            for (Map.Entry<String, byte[]> entry : original.entrySet()) {
                if (entry.getKey().startsWith(section)) {
                    assertArrayEquals(entry.getValue(), read(entry.getKey()),
                        "Block data must survive a player restore: " + entry.getKey());
                }
            }
        }

        assertArrayEquals("id: LATER_BLOCK".getBytes(StandardCharsets.UTF_8), read("stored-blocks/world/1_1.sfb"),
            "A block written after the archive was taken is not rolled back by a player restore");
    }

    @Test
    @DisplayName("Block data alone does not count as player data")
    void hasDataOnlyLooksAtPlayerSections() throws IOException {
        assertTrue(PlayerFileBackup.hasData(root));

        File blocksOnly = workspace.resolve("blocks-only").toFile();
        Path block = blocksOnly.toPath().resolve("stored-blocks/world/0_0.sfb");
        Files.createDirectories(block.getParent());
        Files.writeString(block, "id: ENHANCED_FURNACE", StandardCharsets.UTF_8);

        assertFalse(PlayerFileBackup.hasData(blocksOnly), "A server with blocks but no players has no player data");

        File empty = workspace.resolve("empty").toFile();

        assertTrue(empty.mkdirs());
        assertFalse(PlayerFileBackup.hasData(empty));
        assertFalse(PlayerFileBackup.hasData(workspace.resolve("missing").toFile()));
        assertEquals(0, PlayerFileBackup.writeArchive(archive, empty).values().stream()
            .mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("The captured sections are fixed and a subset of the legacy archive")
    void sectionsAreFixed() {
        assertEquals(List.of("Players", "waypoints"), PlayerFileBackup.getSections());
        assertThrows(UnsupportedOperationException.class, () -> PlayerFileBackup.getSections().add("stored-blocks"));
        assertTrue(LegacyFileBackup.getSections().containsAll(PlayerFileBackup.getSections()));
        assertEquals(new File(LegacyFileBackup.DEFAULT_ROOT), PlayerFileBackup.defaultRoot());
    }

    @Test
    @DisplayName("An archive with selected empty sections clears live player data")
    void emptySectionsRestoreAnEmptyState() throws IOException {
        File empty = workspace.resolve("empty-player-state").toFile();
        PlayerFileBackup.writeArchive(archive, empty);

        int restored = PlayerFileBackup.extractArchive(archive, root);

        assertEquals(0, restored);
        assertFalse(PlayerFileBackup.hasData(root));
        assertTrue(new File(root, "stored-blocks/world/0_0.sfb").isFile());
    }

    @Test
    @DisplayName("An entry that points outside the data root is rejected")
    void zipSlipEntryIsRejected() throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("../escaped.txt"));
            output.write("owned".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        IOException failure = assertThrows(IOException.class, () -> PlayerFileBackup.extractArchive(archive, root));

        assertTrue(failure.getMessage().contains("outside of the backup target"), failure.getMessage());
        assertFalse(workspace.resolve("escaped.txt").toFile().exists());
        assertEquals(6, snapshot().size(), "A rejected archive must leave the live tree untouched");
    }

    @Test
    @DisplayName("An archive holding no files is refused instead of wiping player data")
    void emptyArchiveIsRefused() throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("Players/"));
            output.closeEntry();
        }

        IOException failure = assertThrows(IOException.class, () -> PlayerFileBackup.extractArchive(archive, root));

        assertTrue(failure.getMessage().contains("no restorable entries"), failure.getMessage());
        assertEquals(6, snapshot().size(), "The live tree must survive a useless archive");
    }

    @Test
    @DisplayName("An unreadable archive reports a negative entry count instead of throwing")
    void unreadableArchiveCountsAsNegative() {
        assertEquals(-1, PlayerFileBackup.countEntries(workspace.resolve("nothing.zip").toFile()));
    }

    private List<String> entryNames() throws IOException {
        List<String> names = new ArrayList<>();

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().endsWith("/.slimefun-empty")) {
                    names.add(entry.getName());
                }

                input.closeEntry();
            }
        }

        return names;
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private byte[] read(String relativePath) throws IOException {
        return Files.readAllBytes(root.toPath().resolve(relativePath));
    }

    private Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Path base = root.toPath();

        try (Stream<Path> stream = Files.walk(base)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path)) {
                    files.put(base.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
        }

        return files;
    }
}
