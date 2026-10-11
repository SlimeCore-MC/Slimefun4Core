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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the block archive, which is the only backup block data has ever had that is
 * listed, checksummed and restorable.
 *
 * <p>Two properties matter most: the archive must carry the block sections and nothing
 * else, so restoring it cannot roll player data back, and a restore must be byte exact,
 * because a stored inventory is a serialized {@code ItemStack} tree.
 */
class TestBlockStorageBackup {

    private static final byte[] CHUNK_BYTES = {0x00, (byte) 0xFF, 0x10, 0x7F, (byte) 0x80, 0x01};

    @TempDir
    Path workspace;

    private File root;

    @BeforeEach
    void setUp() throws IOException {
        root = workspace.resolve("server").resolve("data-storage").resolve("Slimefun").toFile();

        write("stored-blocks/world/x0_y0_z0.yml", "world;1;2;3:\n  id: ELECTRIC_FURNACE\n");
        write("stored-blocks/world_nether/x1_y0_z1.yml", "world_nether;16;64;16:\n  id: CARGO_NODE\n");
        write("stored-inventories/world;1;2;3.yml", "0:\n  ==: org.bukkit.inventory.ItemStack\n");
        write("universal-inventories/abcd-1234.yml", "0:\n  ==: org.bukkit.inventory.ItemStack\n");
        writeBytes("stored-chunks/chunks.sfc", CHUNK_BYTES);

        write("Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "researches: [1]");
        write("waypoints/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "0: world;1.0;2.0;3.0");
    }

    @Test
    @DisplayName("All four block sections are captured, each with a non-zero count")
    void everySectionIsCaptured() throws IOException {
        File archive = workspace.resolve("blocks.zip").toFile();
        Map<String, Integer> counts = BlockStorageBackup.writeArchive(archive, root);

        assertEquals(BlockStorageBackup.getSections().size(), counts.size());

        for (String section : BlockStorageBackup.getSections()) {
            assertTrue(counts.containsKey(section), "Section " + section + " was not captured");
            assertTrue(counts.get(section) > 0, "Section " + section + " captured nothing");
        }

        assertEquals(2, counts.get("stored-blocks"), "Both worlds must be captured");
        assertEquals(1, counts.get("stored-chunks"));
        assertEquals(5, BlockStorageBackup.countEntries(archive));
    }

    @Test
    @DisplayName("Player data is not captured, so restoring blocks cannot roll a player back")
    void playerDataIsNeverCaptured() throws IOException {
        File archive = workspace.resolve("scoped.zip").toFile();
        BlockStorageBackup.writeArchive(archive, root);

        List<String> entries = listEntries(archive);

        assertFalse(entries.isEmpty());

        for (String entry : entries) {
            assertFalse(entry.startsWith("Players/"), "A block archive must not carry player data: " + entry);
            assertFalse(entry.startsWith("waypoints/"), "A block archive must not carry waypoints: " + entry);
        }

        assertTrue(entries.contains("stored-chunks/chunks.sfc"));
        assertTrue(entries.contains("stored-blocks/world/x0_y0_z0.yml"));
    }

    @Test
    @DisplayName("A restore is byte exact, including the binary chunk file")
    void restoreIsByteExact() throws IOException {
        File archive = workspace.resolve("exact.zip").toFile();
        BlockStorageBackup.writeArchive(archive, root);

        File target = workspace.resolve("restored").resolve("Slimefun").toFile();
        int extracted = BlockStorageBackup.extractArchive(archive, target);

        assertEquals(5, extracted);
        assertArrayEquals(CHUNK_BYTES, Files.readAllBytes(new File(target, "stored-chunks/chunks.sfc").toPath()));

        for (String relative : List.of(
            "stored-blocks/world/x0_y0_z0.yml",
            "stored-blocks/world_nether/x1_y0_z1.yml",
            "stored-inventories/world;1;2;3.yml",
            "universal-inventories/abcd-1234.yml"
        )) {
            assertArrayEquals(
                Files.readAllBytes(new File(root, relative).toPath()),
                Files.readAllBytes(new File(target, relative).toPath()),
                relative + " did not survive the round trip");
        }
    }

    @Test
    @DisplayName("A restore over a live tree replaces the block sections and leaves players alone")
    void restoreLeavesPlayerDataInPlace() throws IOException {
        File archive = workspace.resolve("over-live.zip").toFile();
        BlockStorageBackup.writeArchive(archive, root);

        write("stored-blocks/world/x9_y9_z9.yml", "world;9;9;9:\n  id: STRAY\n");
        write("Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml", "researches: [1, 2, 3]");

        BlockStorageBackup.extractArchive(archive, root);

        assertFalse(new File(root, "stored-blocks/world/x9_y9_z9.yml").exists(),
            "A block section is replaced wholesale, not merged");
        assertEquals("researches: [1, 2, 3]",
            Files.readString(new File(root, "Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml").toPath()),
            "Player data must be untouched by a block restore");
    }

    @Test
    @DisplayName("An archive with selected empty sections clears live block data")
    void emptySectionsRestoreAnEmptyState() throws IOException {
        File empty = workspace.resolve("empty").resolve("Slimefun").toFile();
        File archive = workspace.resolve("empty-state.zip").toFile();
        BlockStorageBackup.writeArchive(archive, empty);

        int restored = BlockStorageBackup.extractArchive(archive, root);

        assertEquals(0, restored);
        assertFalse(BlockStorageBackup.hasData(root));
        assertTrue(new File(root, "Players/3f7a1c2e-0b44-4c9a-9f11-6d8e2a5b7c30.yml").isFile());
    }

    @Test
    @DisplayName("An archive entry that escapes the target is refused outright")
    void zipSlipIsRefused() throws IOException {
        File archive = workspace.resolve("malicious.zip").toFile();

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("stored-blocks/world/ok.yml"));
            output.write("id: FINE\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new ZipEntry("../../escaped.yml"));
            output.write("owned".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        File target = workspace.resolve("victim").resolve("Slimefun").toFile();
        IOException thrown = assertThrows(IOException.class, () -> BlockStorageBackup.extractArchive(archive, target));

        assertTrue(thrown.getMessage().contains("Refusing to extract entry outside"), thrown.getMessage());
        assertFalse(workspace.resolve("escaped.yml").toFile().exists(),
            "The escaping entry must never reach disk");
        assertFalse(workspace.resolve("victim").resolve("escaped.yml").toFile().exists());
    }

    @Test
    @DisplayName("An archive with no restorable entries is refused rather than wiping the tree")
    void anEmptyArchiveIsRefused() throws IOException {
        File archive = workspace.resolve("hollow.zip").toFile();

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("stored-blocks/"));
            output.closeEntry();
        }

        IOException thrown = assertThrows(IOException.class, () -> BlockStorageBackup.extractArchive(archive, root));

        assertTrue(thrown.getMessage().contains("no restorable entries"), thrown.getMessage());
        assertTrue(new File(root, "stored-blocks/world/x0_y0_z0.yml").isFile(),
            "The live tree must survive a refused restore");
    }

    @Test
    @DisplayName("A tree with no block data reports nothing to archive")
    void hasDataDistinguishesAnEmptyTree() throws IOException {
        assertTrue(BlockStorageBackup.hasData(root));

        File empty = workspace.resolve("fresh").resolve("Slimefun").toFile();

        assertFalse(BlockStorageBackup.hasData(empty), "A missing tree holds no block data");

        Files.createDirectories(new File(empty, "stored-blocks/world").toPath());
        Files.createDirectories(new File(empty, "stored-chunks").toPath());

        assertFalse(BlockStorageBackup.hasData(empty), "Empty directories are not block data");

        Files.writeString(new File(empty, "stored-blocks/world/x0_y0_z0.yml").toPath(), "id: X");

        assertTrue(BlockStorageBackup.hasData(empty));
    }

    @Test
    @DisplayName("A chunk file on its own still counts as block data")
    void chunkFileAloneCountsAsData() throws IOException {
        File onlyChunks = workspace.resolve("chunks-only").resolve("Slimefun").toFile();
        Files.createDirectories(new File(onlyChunks, "stored-chunks").toPath());
        Files.write(new File(onlyChunks, "stored-chunks/chunks.sfc").toPath(), CHUNK_BYTES);

        assertTrue(BlockStorageBackup.hasData(onlyChunks));

        File archive = workspace.resolve("chunks-only.zip").toFile();
        Map<String, Integer> counts = BlockStorageBackup.writeArchive(archive, onlyChunks);

        assertEquals(1, counts.get("stored-chunks"));
        assertEquals(0, counts.get("stored-blocks"));
        assertEquals(1, BlockStorageBackup.countEntries(archive));
    }

    @Test
    @DisplayName("An unreadable archive reports minus one rather than a fake count")
    void anUnreadableArchiveReportsMinusOne() {
        assertEquals(-1, BlockStorageBackup.countEntries(workspace.resolve("absent.zip").toFile()));
    }

    private List<String> listEntries(File archive) throws IOException {
        List<String> entries = new ArrayList<>();

        try (ZipInputStream input = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;

            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && !entry.getName().endsWith("/.slimefun-empty")) {
                    entries.add(entry.getName());
                }

                input.closeEntry();
            }
        }

        return entries;
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private void writeBytes(String relativePath, byte[] content) throws IOException {
        Path target = root.toPath().resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }
}
