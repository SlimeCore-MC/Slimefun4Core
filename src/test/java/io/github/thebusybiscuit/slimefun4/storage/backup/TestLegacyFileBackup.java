package io.github.thebusybiscuit.slimefun4.storage.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the legacy file archive: it must capture player and block data alike, restore
 * byte for byte, and refuse an archive that tries to write outside the data root.
 */
class TestLegacyFileBackup {

    @TempDir
    Path workspace;

    private File root;
    private File archive;

    @BeforeEach
    void setUp() throws IOException {
        root = workspace.resolve("Slimefun").toFile();
        archive = workspace.resolve("archive.zip").toFile();

        write("Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "researches: [1, 2]");
        write("waypoints/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "0: world;1.0;2.0;3.0");
        write("stored-blocks/world/0_0.sfb", "id: ENHANCED_FURNACE");
        write("stored-blocks/world_nether/deep/1_1.sfb", "id: CARGO_NODE_INPUT");
        write("stored-inventories/world;10;20;30.sfi", "slot0: DIAMOND");
        write("universal-inventories/9f11-6d8e.sfi", "slot0: EMERALD");
        write("stored-chunks/chunks.sfc", "world;0;0: {}");
    }

    @Test
    @DisplayName("Every section of the data tree is captured")
    void archiveCapturesEverySection() throws IOException {
        Map<String, Integer> counts = LegacyFileBackup.writeArchive(archive, root);

        for (String section : LegacyFileBackup.getSections()) {
            assertTrue(counts.containsKey(section), "Section " + section + " is missing from the archive");
            assertTrue(counts.get(section) > 0, "Section " + section + " captured no files");
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        assertEquals(7, total, "Every seeded file must be captured");
        assertEquals(2, counts.get("stored-blocks"), "Nested world folders must be walked");
        assertEquals(total, LegacyFileBackup.countEntries(archive));
        assertTrue(archive.length() > 0L);
    }

    @Test
    @DisplayName("An empty data tree captures nothing, so no backup can be written from it")
    void emptyTreeCapturesNothing() throws IOException {
        File emptyRoot = workspace.resolve("empty").toFile();
        assertTrue(emptyRoot.mkdirs());

        Map<String, Integer> counts = LegacyFileBackup.writeArchive(archive, emptyRoot);

        assertEquals(0, counts.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(0, LegacyFileBackup.countEntries(archive));
    }

    @Test
    @DisplayName("A restore puts every byte of every section back")
    void roundTripRestoresEveryByte() throws IOException {
        Map<String, byte[]> original = snapshot();
        LegacyFileBackup.writeArchive(archive, root);

        write("Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "corrupted");
        write("stored-blocks/world/stray.sfb", "id: STRAY");
        deleteRecursively(new File(root, "waypoints"));

        int restored = LegacyFileBackup.extractArchive(archive, root);

        assertEquals(7, restored);

        for (Map.Entry<String, byte[]> entry : original.entrySet()) {
            File file = new File(root, entry.getKey());
            assertTrue(file.isFile(), "Missing after restore: " + entry.getKey());
            assertArrayEquals(entry.getValue(), Files.readAllBytes(file.toPath()),
                "Content differs after restore: " + entry.getKey());
        }

        assertFalse(new File(root, "stored-blocks/world/stray.sfb").exists(),
            "A restored section replaces the live one instead of merging into it");
        assertEquals(original.size(), snapshot().size(), "The restored tree must hold no extra files");
    }

    @Test
    @DisplayName("An entry that points outside the data root is rejected")
    void zipSlipEntryIsRejected() throws IOException {
        writeArchiveWithEntry("../escaped.txt", "owned");

        IOException failure = assertThrows(IOException.class, () -> LegacyFileBackup.extractArchive(archive, root));

        assertTrue(failure.getMessage().contains("outside of the backup target"),
            "Unexpected failure message: " + failure.getMessage());
        assertFalse(workspace.resolve("escaped.txt").toFile().exists(), "The escaping entry must not be written");
        assertFalse(new File(root, "escaped.txt").exists());
        assertEquals("researches: [1, 2]", read("Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml"),
            "A rejected archive must leave the live tree untouched");
    }

    @Test
    @DisplayName("An archive holding no files is refused instead of wiping the tree")
    void emptyArchiveIsRefused() throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("Players/"));
            output.closeEntry();
        }

        IOException failure = assertThrows(IOException.class, () -> LegacyFileBackup.extractArchive(archive, root));

        assertTrue(failure.getMessage().contains("no restorable entries"),
            "Unexpected failure message: " + failure.getMessage());
        assertEquals(7, snapshot().size(), "The live tree must survive a useless archive");
    }

    @Test
    @DisplayName("Restoring leaves no staging directory behind")
    void stagingDirectoryIsCleanedUp() throws IOException {
        LegacyFileBackup.writeArchive(archive, root);
        LegacyFileBackup.extractArchive(archive, root);

        File[] leftovers = workspace.toFile().listFiles((dir, name) -> name.startsWith(".slimefun-restore-"));

        assertTrue(leftovers == null || leftovers.length == 0, "The staging directory must be removed");
    }

    private void writeArchiveWithEntry(String entryName, String content) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry(entryName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(root.toPath().resolve(relativePath), StandardCharsets.UTF_8);
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

    private void deleteRecursively(File file) throws IOException {
        try (Stream<Path> stream = Files.walk(file.toPath())) {
            for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
