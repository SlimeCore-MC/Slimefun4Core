package io.github.thebusybiscuit.slimefun4.storage.migration;

import io.github.bakedlibs.dough.config.Config;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.StorageException;
import io.github.thebusybiscuit.slimefun4.storage.StorageProvider;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderFactory;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;
import io.github.thebusybiscuit.slimefun4.storage.util.AtomicFiles;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Everything a migration needs to know about a provider it is not currently running on.
 *
 * <p>The destination is built from a second, in-memory {@link Config} read from the same
 * {@code database.yml}: the {@code provider} key is changed with
 * {@link Config#setValue(String, Object)} and the config is never saved, so the running
 * provider is left completely alone. {@link StorageProviderFactory} then builds the
 * destination from that config exactly as it builds the live one at startup.
 *
 * <p>Readiness is reported by key name. {@code password} is never read into a message, only
 * tested for presence, so no output path of this class can leak a credential.
 *
 * <p>{@link #applyProvider} rewrites the single {@code provider:} line in place instead of
 * saving the {@link Config}. Saving would re-serialize the whole document and strip every
 * comment out of a file that is almost entirely comments.
 *
 * @author SlimefunCore Contributors
 */
public final class MigrationTargets {

    private static final List<StorageProviderType> ORDER = List.of(
        StorageProviderType.LEGACY,
        StorageProviderType.SQLITE,
        StorageProviderType.MYSQL,
        StorageProviderType.MARIADB,
        StorageProviderType.POSTGRESQL
    );

    private static final String PROVIDER_KEY = "provider";
    private static final String LEGACY_ROOT = "data-storage/Slimefun";
    private static final String DEFAULT_SQLITE_PATH = "data-storage/Slimefun/slimefun.db";

    private MigrationTargets() {}

    /**
     * Describes how ready one provider is to be migrated into.
     *
     * @param type         The provider
     * @param complete     Whether its {@code database.yml} block holds everything it needs
     * @param missingKeys  The keys it still needs, by key name only
     * @param detail       Where its data would live, never containing a credential
     * @param username     The configured user, or {@code null} when the provider needs none
     * @param passwordSet  Whether a password is configured, never the password itself
     */
    public record Readiness(
        @Nonnull StorageProviderType type,
        boolean complete,
        @Nonnull List<String> missingKeys,
        @Nonnull String detail,
        @Nullable String username,
        boolean passwordSet
    ) {

        /**
         * @return The credentials of this target, with the password reduced to its presence
         */
        @Nonnull
        public String describeCredentials() {
            if (username == null) {
                return "no credentials required";
            }

            return "username " + username + ", password " + (passwordSet ? "SET (hidden)" : "NOT SET");
        }
    }

    /**
     * @return Every provider a migration can target, in a stable display order
     */
    @Nonnull
    public static List<StorageProviderType> getOrder() {
        return ORDER;
    }

    /**
     * @param type The provider
     * @return The token used for it in commands and in {@code database.yml}
     */
    @Nonnull
    public static String token(@Nonnull StorageProviderType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param type The provider
     * @return Its {@code database.yml} section, or an empty string when it has none
     */
    @Nonnull
    public static String section(@Nonnull StorageProviderType type) {
        return type == StorageProviderType.LEGACY ? "" : token(type);
    }

    /**
     * Resolves a provider token typed by an operator.
     *
     * @param token The token, in any casing
     * @return The provider, or empty when the token names none
     */
    @Nonnull
    public static Optional<StorageProviderType> parse(@Nullable String token) {
        if (token == null) {
            return Optional.empty();
        }

        return switch (token.trim().toLowerCase(Locale.ROOT)) {
            case "legacy", "yaml", "yml", "files" -> Optional.of(StorageProviderType.LEGACY);
            case "sqlite", "sqlite3" -> Optional.of(StorageProviderType.SQLITE);
            case "mysql" -> Optional.of(StorageProviderType.MYSQL);
            case "mariadb" -> Optional.of(StorageProviderType.MARIADB);
            case "postgresql", "postgres", "psql" -> Optional.of(StorageProviderType.POSTGRESQL);
            default -> Optional.empty();
        };
    }

    /**
     * @return Every provider token an operator may type, in display order
     */
    @Nonnull
    public static List<String> getTokens() {
        List<String> tokens = new ArrayList<>();

        for (StorageProviderType type : ORDER) {
            tokens.add(token(type));
        }

        return List.copyOf(tokens);
    }

    /**
     * Lists the {@code database.yml} keys a provider still needs before it can be opened.
     *
     * <p>An empty {@code password} is not reported: the SQL providers only require a host, a
     * database and a user, and a blank password is a legitimate local setup.
     *
     * @param dbConfig The loaded {@code database.yml}
     * @param type     The provider to check
     * @return The missing keys, by key name, in check order
     */
    @Nonnull
    public static List<String> missingKeys(@Nonnull Config dbConfig, @Nonnull StorageProviderType type) {
        List<String> missing = new ArrayList<>();

        if (type == StorageProviderType.LEGACY) {
            return List.copyOf(missing);
        }

        String section = section(type);

        if (type == StorageProviderType.SQLITE) {
            if (isBlank(dbConfig.getString(section + ".database"))) {
                missing.add(section + ".database");
            }

            return List.copyOf(missing);
        }

        if (isBlank(dbConfig.getString(section + ".host"))) {
            missing.add(section + ".host");
        }

        if (dbConfig.getInt(section + ".port") <= 0) {
            missing.add(section + ".port");
        }

        if (isBlank(dbConfig.getString(section + ".database"))) {
            missing.add(section + ".database");
        }

        if (isBlank(dbConfig.getString(section + ".username"))) {
            missing.add(section + ".username");
        }

        return List.copyOf(missing);
    }

    /**
     * Inspects one provider without opening it.
     *
     * @param dbConfig The loaded {@code database.yml}
     * @param type     The provider to inspect
     * @return Its readiness
     */
    @Nonnull
    public static Readiness inspect(@Nonnull Config dbConfig, @Nonnull StorageProviderType type) {
        List<String> missing = missingKeys(dbConfig, type);

        if (type == StorageProviderType.LEGACY) {
            return new Readiness(type, true, missing, LEGACY_ROOT, null, false);
        }

        String section = section(type);

        if (type == StorageProviderType.SQLITE) {
            String path = dbConfig.getString(section + ".database");

            return new Readiness(type, missing.isEmpty(), missing,
                isBlank(path) ? DEFAULT_SQLITE_PATH : path, null, false);
        }

        String host = dbConfig.getString(section + ".host");
        int port = dbConfig.getInt(section + ".port");
        String database = dbConfig.getString(section + ".database");
        String username = dbConfig.getString(section + ".username");
        String password = dbConfig.getString(section + ".password");

        String detail = (isBlank(host) ? "?" : host) + ':' + (port <= 0 ? "?" : String.valueOf(port))
            + '/' + (isBlank(database) ? "?" : database);

        return new Readiness(type, missing.isEmpty(), missing, detail,
            isBlank(username) ? "" : username, !isBlank(password));
    }

    /**
     * Inspects every provider a migration can target.
     *
     * @param dbConfig The loaded {@code database.yml}
     * @return One {@link Readiness} per provider, in display order
     */
    @Nonnull
    public static List<Readiness> inspectAll(@Nonnull Config dbConfig) {
        List<Readiness> readiness = new ArrayList<>();

        for (StorageProviderType type : ORDER) {
            readiness.add(inspect(dbConfig, type));
        }

        return List.copyOf(readiness);
    }

    /**
     * Builds a second, in-memory view of {@code database.yml} pointed at another provider.
     *
     * <p>The returned config is never saved, so the running provider keeps reading exactly
     * the configuration it started with.
     *
     * @param dbConfig    The loaded {@code database.yml}, used only for its file location
     * @param destination The provider the copy should describe
     * @return A fresh config identical to the file except for its {@code provider} key
     */
    @Nonnull
    public static Config configFor(@Nonnull Config dbConfig, @Nonnull StorageProviderType destination) {
        Config copy = new Config(dbConfig.getFile());

        copy.setValue(PROVIDER_KEY, token(destination));

        return copy;
    }

    /**
     * Opens the destination provider.
     *
     * <p>The provider's own {@code initialize()} creates and commits its schema, which is
     * why this is only ever called on a real run and never during a dry run.
     *
     * @param plugin      The plugin instance the provider logs and schedules through
     * @param dbConfig    The loaded {@code database.yml}
     * @param destination The provider to open
     * @return An initialized provider the caller must {@code shutdown()}
     * @throws MigrationException If the provider could not be built or reached
     */
    @Nonnull
    public static StorageProvider open(
        @Nonnull Slimefun plugin,
        @Nonnull Config dbConfig,
        @Nonnull StorageProviderType destination
    ) throws MigrationException {
        StorageProvider provider = StorageProviderFactory.create(plugin, configFor(dbConfig, destination));

        try {
            provider.initialize();
        } catch (StorageException | RuntimeException x) {
            provider.shutdown();

            throw new MigrationException("The destination " + token(destination)
                + " could not be opened, nothing was written", x);
        }

        if (!provider.isAvailable()) {
            provider.shutdown();

            throw new MigrationException("The destination " + token(destination)
                + " reported itself unavailable, nothing was written");
        }

        return provider;
    }

    /**
     * Rewrites the {@code provider:} line of {@code database.yml} and nothing else.
     *
     * <p>Every comment, every other key and the file's ordering survive, because the file is
     * edited line by line rather than re-serialized. The write goes through
     * {@link AtomicFiles#moveReplace} and is read back to confirm it took effect.
     *
     * @param file        The {@code database.yml} to edit
     * @param destination The provider to activate on the next start
     * @throws IOException If the file could not be read, written or verified
     */
    public static void applyProvider(
        @Nonnull File file,
        @Nonnull StorageProviderType destination
    ) throws IOException {
        if (!file.isFile()) {
            throw new IOException("database.yml was not found at " + file.getPath());
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        String token = token(destination);
        boolean replaced = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (line.startsWith(PROVIDER_KEY + ':')) {
                int comment = line.indexOf('#');

                lines.set(i, PROVIDER_KEY + ": " + token
                    + (comment < 0 ? "" : ' ' + line.substring(comment)));
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            lines.add(PROVIDER_KEY + ": " + token);
        }

        File parent = file.getParentFile();
        File tmpFile = new File(parent, file.getName() + ".provider.tmp");

        try {
            Files.write(tmpFile.toPath(), lines, StandardCharsets.UTF_8);
            AtomicFiles.moveReplace(tmpFile, file);
        } catch (IOException x) {
            Files.deleteIfExists(tmpFile.toPath());

            throw x;
        }

        String written = new Config(file).getString(PROVIDER_KEY);

        if (written == null || !token.equals(written.trim().toLowerCase(Locale.ROOT))) {
            throw new IOException("database.yml still does not read provider: " + token + " after the rewrite");
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
