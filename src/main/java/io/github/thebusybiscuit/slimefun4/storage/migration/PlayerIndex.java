package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.schema.SqlDialect;

import javax.annotation.Nonnull;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Enumerates the players a backend holds data for.
 *
 * <p>Neither {@code Storage} nor {@code StorageProvider} can list players, which is the
 * concrete reason {@link StorageMigrator} was never callable. Both backends store the
 * owning uuid explicitly, so enumeration is a directory listing on the legacy backend and
 * a distinct select on a SQL one.
 *
 * @author SlimefunCore Contributors
 */
public final class PlayerIndex {

    private static final List<String> PLAYER_TABLES =
        List.of("player_researches", "player_backpacks", "player_waypoints");

    private static final List<String> LEGACY_DIRECTORIES = List.of("Players", "waypoints");

    private PlayerIndex() {}

    /**
     * The outcome of an enumeration pass.
     *
     * @param uuids     Every player uuid found, sorted for deterministic reporting
     * @param unreadable Names that were present but could not be read as a uuid
     */
    public record Index(@Nonnull List<UUID> uuids, @Nonnull List<String> unreadable) {

        /**
         * @return How many players were found
         */
        public int size() {
            return uuids.size();
        }
    }

    /**
     * Lists every player found under a legacy YAML data root.
     *
     * @param root The data root, normally {@code data-storage/Slimefun}
     * @return The enumeration outcome
     */
    @Nonnull
    public static Index fromLegacy(@Nonnull File root) {
        Set<UUID> found = new TreeSet<>(Comparator.comparing(UUID::toString));
        List<String> unreadable = new ArrayList<>();

        for (String section : LEGACY_DIRECTORIES) {
            File directory = new File(root, section);
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));

            if (files == null) {
                continue;
            }

            for (File file : files) {
                String name = file.getName();
                String base = name.substring(0, name.length() - ".yml".length());

                try {
                    found.add(UUID.fromString(base));
                } catch (IllegalArgumentException x) {
                    unreadable.add(section + '/' + name);
                }
            }
        }

        return new Index(List.copyOf(found), List.copyOf(unreadable));
    }

    /**
     * Lists every player found across the managed player tables of a SQL backend.
     *
     * @param connection An open connection to the backend
     * @param type       The provider type, used for identifier quoting
     * @return The enumeration outcome
     * @throws SQLException If a table could not be read
     */
    @Nonnull
    public static Index fromSql(@Nonnull Connection connection, @Nonnull StorageProviderType type) throws SQLException {
        Set<UUID> found = new TreeSet<>(Comparator.comparing(UUID::toString));
        List<String> unreadable = new ArrayList<>();

        for (String table : PLAYER_TABLES) {
            String sql = "SELECT DISTINCT " + SqlDialect.quoteIdent("uuid", type)
                + " FROM " + SqlDialect.quoteIdent(table, type);

            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {

                while (rs.next()) {
                    String raw = rs.getString(1);

                    if (raw == null || raw.isBlank()) {
                        unreadable.add(table + "/<null>");
                        continue;
                    }

                    try {
                        found.add(UUID.fromString(raw.trim()));
                    } catch (IllegalArgumentException x) {
                        unreadable.add(table + '/' + raw);
                    }
                }
            }
        }

        return new Index(List.copyOf(found), List.copyOf(unreadable));
    }

    /**
     * @return The managed tables that carry a player uuid
     */
    @Nonnull
    public static List<String> getPlayerTables() {
        return PLAYER_TABLES;
    }
}
