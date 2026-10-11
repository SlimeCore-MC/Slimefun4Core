package io.github.thebusybiscuit.slimefun4.storage.provider;

import com.google.common.annotations.Beta;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.bakedlibs.dough.config.Config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;

/**
 * Tests whether a set of database settings can actually connect, without touching
 * the live storage engine and without creating or altering any table.
 *
 * <p>This exists so that {@code /sf storage gui} can validate credentials before an
 * admin commits them to {@code database.yml}. A provider's own {@code initialize()}
 * would additionally run schema creation against the target database, which is not
 * something a connection test should do.
 *
 * <p>The password is never accepted as a parameter. It is read from the given
 * {@link Config} by key name so that it never has to pass through user interface code.
 *
 * @author SlimefunCore Contributors
 */
@Beta
public final class StorageConnectionProbe {

    private static final long TIMEOUT_MS = 8_000L;

    private StorageConnectionProbe() {}

    /**
     * The non-secret connection settings to test.
     *
     * @param host     The server host, ignored for SQLite
     * @param port     The server port, ignored for SQLite
     * @param database The database name, ignored for SQLite
     * @param username The login name, ignored for SQLite
     * @param ssl      Whether to request an encrypted connection
     * @param filePath The SQLite database file path, ignored for every other provider
     */
    public record ProbeTarget(
        @Nullable String host,
        int port,
        @Nullable String database,
        @Nullable String username,
        boolean ssl,
        @Nullable String filePath
    ) {}

    /**
     * The outcome of a connection test.
     *
     * @param success    Whether a connection was established and a query succeeded
     * @param detail     A short human readable result or failure reason
     * @param pingMillis The round-trip time of the test query, {@code -1} on failure
     */
    public record ProbeResult(boolean success, @Nonnull String detail, long pingMillis) {}

    /**
     * Attempts to open a single connection with the given settings and run
     * {@code SELECT 1} against it.
     *
     * <p>Blocking: call this from an async task. Nothing is written to the target
     * database and the live provider is left untouched.
     *
     * @param type     The provider dialect to test
     * @param target   The non-secret settings to test
     * @param dbConfig The config to read the password from, by key name
     * @return The outcome, never {@code null}
     */
    @Nonnull
    public static ProbeResult probe(
            @Nonnull StorageProviderType type,
            @Nonnull ProbeTarget target,
            @Nullable Config dbConfig) {

        if (type == StorageProviderType.LEGACY) {
            File root = new File("data-storage/Slimefun");
            return root.isDirectory()
                ? new ProbeResult(true, "Legacy YAML tree found at " + root.getPath(), 0L)
                : new ProbeResult(false, "Legacy data folder is missing: " + root.getPath(), -1L);
        }

        String url = buildUrl(type, target);

        if (url == null) {
            return new ProbeResult(false, "Incomplete settings for " + type, -1L);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("Slimefun-Probe");
        cfg.setJdbcUrl(url);
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(TIMEOUT_MS);
        cfg.setInitializationFailTimeout(TIMEOUT_MS);
        cfg.setValidationTimeout(Math.min(TIMEOUT_MS, 5_000L));
        cfg.setConnectionTestQuery("SELECT 1");

        String driver = driverClassName(type);

        if (driver != null) {
            cfg.setDriverClassName(driver);
        }

        if (type != StorageProviderType.SQLITE) {
            cfg.setUsername(target.username());
            cfg.setPassword(readPassword(type, dbConfig));
        }

        long started = System.nanoTime();

        try (HikariDataSource source = new HikariDataSource(cfg);
                Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {

            statement.execute("SELECT 1");
            long ping = (System.nanoTime() - started) / 1_000_000L;
            return new ProbeResult(true, "Connected in " + ping + " ms", ping);
        } catch (Exception x) {
            return new ProbeResult(false, describe(x), -1L);
        }
    }

    @Nullable
    private static String buildUrl(@Nonnull StorageProviderType type, @Nonnull ProbeTarget target) {
        if (type == StorageProviderType.SQLITE) {
            String path = target.filePath();

            if (path == null || path.isBlank()) {
                return null;
            }

            return "jdbc:sqlite:" + new File(path).getAbsolutePath();
        }

        String host = target.host();
        String database = target.database();

        if (host == null || host.isBlank() || database == null || database.isBlank()) {
            return null;
        }

        int port = target.port() > 0 ? target.port() : defaultPort(type);

        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?characterEncoding=utf8&autoReconnect=true&"
                + (target.ssl() ? "useSSL=true&requireSSL=true" : "useSSL=false&allowPublicKeyRetrieval=true");
            case MARIADB -> "jdbc:mariadb://" + host + ":" + port + "/" + database
                + (target.ssl() ? "?useSsl=true" : "?useSsl=false")
                + "&characterEncoding=utf8&autoReconnect=true";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            default -> null;
        };
    }

    @Nullable
    private static String driverClassName(@Nonnull StorageProviderType type) {
        return switch (type) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
            default -> null;
        };
    }

    private static int defaultPort(@Nonnull StorageProviderType type) {
        return type == StorageProviderType.POSTGRESQL ? 5432 : 3306;
    }

    @Nonnull
    private static String readPassword(@Nonnull StorageProviderType type, @Nullable Config dbConfig) {
        if (dbConfig == null) {
            return "";
        }

        String password = dbConfig.getString(type.name().toLowerCase(Locale.ROOT) + ".password");
        return password == null ? "" : password;
    }

    @Nonnull
    private static String describe(@Nonnull Throwable throwable) {
        Throwable root = throwable;

        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        String message = root.getMessage();
        return message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    }
}
