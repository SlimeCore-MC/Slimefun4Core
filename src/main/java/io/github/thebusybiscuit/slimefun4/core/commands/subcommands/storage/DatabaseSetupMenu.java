package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.config.Config;
import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4ChatInput;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageConnectionProbe;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The provider setup wizard of {@code /sf storage gui}.
 *
 * <p>Edits are collected in a per-player draft and only reach {@code database.yml} when
 * the admin presses apply. The live storage engine is never hot-swapped: applying writes
 * the file and asks for a restart, which is the only way a pool can be rebuilt safely
 * while the server is holding player data.
 *
 * <p>The password is deliberately not part of the draft. It is read from
 * {@code database.yml} only inside {@link StorageConnectionProbe} and is shown here as
 * nothing more than set or not set.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class DatabaseSetupMenu {

    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    private static final StorageProviderType[] PICKER = {
        StorageProviderType.SQLITE,
        StorageProviderType.MYSQL,
        StorageProviderType.MARIADB,
        StorageProviderType.POSTGRESQL,
        StorageProviderType.LEGACY
    };

    private static final int[] PICKER_SLOTS = { 10, 11, 12, 13, 14 };

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-]{1,255}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.@\\-]{1,64}");
    private static final Pattern FILE_PATH = Pattern.compile("[A-Za-z0-9_./\\\\:\\- ]{1,255}");

    private DatabaseSetupMenu() {}

    static void open(@Nonnull Plugin plugin, @Nonnull Player player) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        Config config = svc.getDbConfig();
        Draft draft = DRAFTS.computeIfAbsent(player.getUniqueId(),
            id -> Draft.seed(config, activeType(config, svc)));

        ChestMenu menu = StorageMenuLayout.newMenu("&2Database setup");
        StorageMenuLayout.fillBackground(menu);

        addHeader(menu, svc, config, draft);
        addPicker(plugin, player, menu, config, draft);
        addFields(plugin, player, menu, config, draft);
        addActions(plugin, player, menu, config, draft);

        menu.open(player);
    }

    private static void addHeader(
            @Nonnull ChestMenu menu,
            @Nonnull StorageEngineService svc,
            @Nonnull Config config,
            @Nonnull Draft draft) {

        String configured = config.getString("provider");
        File file = config.getFile();

        StorageMenuLayout.addInfoItem(menu, 4, Material.ANVIL, "&aDatabase setup",
            StorageMenuLayout.lore(
                "&7Running now: &f" + svc.getProvider().getProviderName(),
                "&7In the file: &f" + (configured == null || configured.isBlank() ? "not set" : configured),
                "&7Draft:       &f" + draft.type.name().toLowerCase(Locale.ROOT),
                "",
                "&7File: &f" + (file == null ? "database.yml" : file.getPath()),
                "",
                "&7Nothing here changes the running",
                "&7server. Apply writes the file, then",
                "&7restart to load the new provider."));
    }

    private static void addPicker(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Config config,
            @Nonnull Draft draft) {

        for (int index = 0; index < PICKER.length; index++) {
            StorageProviderType type = PICKER[index];
            boolean selected = draft.type == type;

            StorageMenuLayout.addManagedButton(menu, PICKER_SLOTS[index],
                CustomItemStack.create(icon(type),
                    (selected ? "&a> " : "&7") + label(type),
                    StorageMenuLayout.lore(
                        "&7" + describe(type)[0],
                        "&7" + describe(type)[1],
                        "",
                        "&7Keys: &f" + type.name().toLowerCase(Locale.ROOT) + ".*",
                        "",
                        selected ? "&aSelected in this draft" : "&eClick &7to select")),
                (pl, right, shift) -> {
                    DRAFTS.put(pl.getUniqueId(), Draft.seed(config, type));
                    open(plugin, pl);
                });
        }
    }

    @Nonnull
    private static Material icon(@Nonnull StorageProviderType type) {
        return switch (type) {
            case SQLITE -> Material.BOOK;
            case MYSQL -> Material.SEA_LANTERN;
            case MARIADB -> Material.PRISMARINE;
            case POSTGRESQL -> Material.LAPIS_BLOCK;
            case LEGACY -> Material.WRITTEN_BOOK;
        };
    }

    @Nonnull
    private static String label(@Nonnull StorageProviderType type) {
        return switch (type) {
            case SQLITE -> "SQLite";
            case MYSQL -> "MySQL";
            case MARIADB -> "MariaDB";
            case POSTGRESQL -> "PostgreSQL";
            case LEGACY -> "Legacy YAML";
        };
    }

    @Nonnull
    private static String[] describe(@Nonnull StorageProviderType type) {
        return switch (type) {
            case SQLITE -> new String[] { "One database file, no server.", "The simplest real database." };
            case MYSQL -> new String[] { "Remote server, shared by many", "servers on a network." };
            case MARIADB -> new String[] { "MySQL-compatible fork, same", "settings and driver behaviour." };
            case POSTGRESQL -> new String[] { "Remote server with stricter", "types and stronger guarantees." };
            case LEGACY -> new String[] { "The original YAML file tree.", "Backups are file archives." };
        };
    }

    private static void addFields(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Config config,
            @Nonnull Draft draft) {

        if (draft.type == StorageProviderType.LEGACY) {
            StorageMenuLayout.addInfoItem(menu, 22, Material.WRITTEN_BOOK, "&bNo settings to edit",
                StorageMenuLayout.lore(
                    "&7Legacy storage always reads and",
                    "&7writes &fdata-storage/Slimefun&7.",
                    "",
                    "&7Backups of this provider are",
                    "&7byte-exact ZIP archives of that tree."));
            return;
        }

        if (draft.type == StorageProviderType.SQLITE) {
            StorageMenuLayout.addManagedButton(menu, 21,
                CustomItemStack.create(Material.BARREL, "&bDatabase file",
                    StorageMenuLayout.lore(
                        "&7Value: &f" + draft.filePath,
                        "&7Key:   &fsqlite.database",
                        "",
                        "&7Relative paths start at the",
                        "&7server root.",
                        "",
                        "&eClick &7to change")),
                (pl, right, shift) -> editText(plugin, pl, "sqlite.database", draft.filePath, FILE_PATH,
                    value -> draft.filePath = value));

            StorageMenuLayout.addInfoItem(menu, 23, Material.LIME_STAINED_GLASS_PANE, "&bNo credentials needed",
                StorageMenuLayout.lore(
                    "&7SQLite is a local file, so there is",
                    "&7no host, port, user or password.",
                    "",
                    "&7Keep the file inside your server",
                    "&7folder so your backups include it."));
            return;
        }

        addServerFields(plugin, menu, config, draft, player.hasPermission(StorageMenuLayout.PERM_MANAGE));
    }

    private static void addServerFields(
            @Nonnull Plugin plugin,
            @Nonnull ChestMenu menu,
            @Nonnull Config config,
            @Nonnull Draft draft,
            boolean manage) {

        String section = draft.type.name().toLowerCase(Locale.ROOT);

        StorageMenuLayout.addManagedButton(menu, 19,
            CustomItemStack.create(Material.COMPASS, "&bHost",
                StorageMenuLayout.lore(
                    "&7Value: &f" + draft.host,
                    "&7Key:   &f" + section + ".host",
                    "",
                    "&eClick &7to change")),
            (pl, right, shift) -> editText(plugin, pl, section + ".host", draft.host, HOST,
                value -> draft.host = value));

        StorageMenuLayout.addManagedButton(menu, 20,
            CustomItemStack.create(Material.REPEATER, "&bPort",
                StorageMenuLayout.lore(
                    "&7Value: &f" + draft.port,
                    "&7Key:   &f" + section + ".port",
                    "",
                    "&7Default: &f" + defaultPort(draft.type),
                    "",
                    "&eClick &7to change")),
            (pl, right, shift) -> editPort(plugin, pl, section + ".port", draft));

        StorageMenuLayout.addManagedButton(menu, 21,
            CustomItemStack.create(Material.BARREL, "&bDatabase name",
                StorageMenuLayout.lore(
                    "&7Value: &f" + draft.database,
                    "&7Key:   &f" + section + ".database",
                    "",
                    "&7The database must already exist.",
                    "&7Slimefun creates only its tables.",
                    "",
                    "&eClick &7to change")),
            (pl, right, shift) -> editText(plugin, pl, section + ".database", draft.database, IDENTIFIER,
                value -> draft.database = value));

        StorageMenuLayout.addManagedButton(menu, 22,
            CustomItemStack.create(Material.NAME_TAG, "&bUsername",
                StorageMenuLayout.lore(
                    "&7Value: &f" + (manage ? draft.username : "hidden"),
                    "&7Key:   &f" + section + ".username",
                    "",
                    "&eClick &7to change")),
            (pl, right, shift) -> editText(plugin, pl, section + ".username", draft.username, USERNAME,
                value -> draft.username = value));

        boolean passwordSet = hasPassword(config, section);

        StorageMenuLayout.addInfoItem(menu, 23, Material.IRON_DOOR, "&bPassword",
            StorageMenuLayout.lore(
                "&7State: " + (passwordSet ? "&aSET (hidden)" : "&cNOT SET"),
                "&7Key:   &f" + section + ".password",
                "",
                "&7Passwords are never shown, typed or",
                "&7logged here. Edit the file directly:",
                "&f" + (config.getFile() == null ? "database.yml" : config.getFile().getPath()),
                "",
                "&7Then restart the server."));

        StorageMenuLayout.addManagedButton(menu, 24,
            CustomItemStack.create(draft.ssl ? Material.SHIELD : Material.LEATHER, "&bEncrypted connection",
                StorageMenuLayout.lore(
                    "&7Value: " + (draft.ssl ? "&aon" : "&7off"),
                    "&7Key:   &f" + section + ".ssl",
                    "",
                    "&7Turn this on only if the server",
                    "&7presents a certificate you trust.",
                    "",
                    "&eClick &7to toggle")),
            (pl, right, shift) -> {
                draft.ssl = !draft.ssl;
                open(plugin, pl);
            });
    }

    private static void addActions(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Config config,
            @Nonnull Draft draft) {

        StorageMenuLayout.addManagedButton(menu, 29,
            CustomItemStack.create(Material.SPYGLASS, "&b> Test connection",
                StorageMenuLayout.lore(
                    "&7Opens one connection with the draft",
                    "&7settings and runs a test query.",
                    "",
                    "&7No table is created and the running",
                    "&7storage engine is not touched.",
                    "",
                    "&eClick &7to test")),
            (pl, right, shift) -> test(plugin, pl, draft));

        StorageMenuLayout.addManagedButton(menu, 31,
            CustomItemStack.create(Material.WRITABLE_BOOK, "&a> Apply to database.yml",
                StorageMenuLayout.lore(
                    "&7Writes the draft to the file.",
                    "&7The password key is left untouched.",
                    "",
                    "&cA restart is required before the",
                    "&cnew provider is used.",
                    "",
                    "&eClick &7to continue")),
            (pl, right, shift) -> confirmApply(plugin, pl, draft));

        StorageMenuLayout.addButton(menu, 33,
            CustomItemStack.create(Material.BUCKET, "&7> Discard draft",
                StorageMenuLayout.lore(
                    "&7Reloads the values from the file",
                    "&7and forgets your edits.",
                    "",
                    "&eClick &7to discard")),
            (pl, right, shift) -> {
                DRAFTS.remove(pl.getUniqueId());
                open(plugin, pl);
            });

        StorageMenuLayout.addButton(menu, StorageMenuLayout.SLOT_CENTER,
            CustomItemStack.create(Material.ENCHANTED_BOOK, "&b> Guide for " + label(draft.type),
                StorageMenuLayout.lore(
                    "&7Every key this provider reads and",
                    "&7what it expects.",
                    "",
                    "&eClick &7to read")),
            (pl, right, shift) -> StorageGuideMenu.open(plugin, pl, pageFor(draft.type)));

        StorageMenuLayout.addBackButton(menu, player, () -> StorageGuiMenu.open(plugin, player));
    }

    private static void test(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull Draft draft) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Testing " + ChatColor.WHITE + label(draft.type)
            + ChatColor.GRAY + ", this waits up to 8 seconds.");

        StorageConnectionProbe.ProbeTarget target = new StorageConnectionProbe.ProbeTarget(
            draft.host, draft.port, draft.database, draft.username, draft.ssl, draft.filePath);

        StorageMenuLayout.runAsync(plugin, () -> {
            StorageConnectionProbe.ProbeResult result =
                StorageConnectionProbe.probe(draft.type, target, svc.getDbConfig());

            StorageMenuLayout.sync(() -> {
                if (result.success()) {
                    player.sendMessage(ChatColor.GREEN + "Connection works: " + ChatColor.WHITE + result.detail());
                } else {
                    player.sendMessage(ChatColor.RED + "Connection failed: " + ChatColor.WHITE + result.detail());
                    player.sendMessage(ChatColor.GRAY + "Check the host, port, database name, user and the "
                        + "password key in database.yml.");
                }

                open(plugin, player);
            });
        });
    }

    private static void confirmApply(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull Draft draft) {
        String section = draft.type.name().toLowerCase(Locale.ROOT);

        StorageConfirmMenu.open(player, "&6Write database.yml?", Material.WRITABLE_BOOK,
            "&eApply this provider",
            StorageMenuLayout.lore(
                "&7provider: &f" + section,
                draft.type == StorageProviderType.SQLITE
                    ? "&7" + section + ".database: &f" + draft.filePath
                    : "&7" + section + ".host: &f" + draft.host,
                draft.type == StorageProviderType.SQLITE || draft.type == StorageProviderType.LEGACY
                    ? "&7No credentials are written."
                    : "&7" + section + ".port/database/username/ssl",
                "",
                "&7The password key is not written and",
                "&7not read by this screen.",
                "",
                "&cThe running server keeps using the",
                "&cold provider until you restart."),
            CustomItemStack.create(Material.WRITABLE_BOOK, "&a> Write the file",
                StorageMenuLayout.lore("&7Only database.yml is changed.", "", "&eClick &7to write")),
            () -> apply(plugin, player, draft),
            () -> open(plugin, player));
    }

    private static void apply(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull Draft draft) {
        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");
            return;
        }

        File file = svc.getDbConfig().getFile();

        if (file == null) {
            player.sendMessage(ChatColor.RED + "The database config has no file on disk to write to.");
            return;
        }

        String section = draft.type.name().toLowerCase(Locale.ROOT);

        StorageMenuLayout.runAsync(plugin, () -> {
            String failure = null;

            try {
                Config target = new Config(file);
                target.setValue("provider", section);

                if (draft.type == StorageProviderType.SQLITE) {
                    target.setValue("sqlite.database", draft.filePath);
                } else if (draft.type != StorageProviderType.LEGACY) {
                    target.setValue(section + ".host", draft.host);
                    target.setValue(section + ".port", draft.port);
                    target.setValue(section + ".database", draft.database);
                    target.setValue(section + ".username", draft.username);
                    target.setValue(section + ".ssl", draft.ssl);
                }

                target.save();
            } catch (Exception x) {
                failure = describe(x);
            }

            String error = failure;

            StorageMenuLayout.sync(() -> {
                if (error == null) {
                    DRAFTS.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "database.yml updated: provider is now "
                        + ChatColor.WHITE + section);
                    player.sendMessage(ChatColor.YELLOW + "Restart the server to load it.");
                    player.sendMessage(ChatColor.GRAY + "Take a backup before the restart if you are "
                        + "switching between different providers.");
                } else {
                    player.sendMessage(ChatColor.RED + "Could not write database.yml: " + ChatColor.WHITE + error);
                }
            });
        });
    }

    private static void editText(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull String key,
            @Nonnull String current,
            @Nonnull Pattern allowed,
            @Nonnull Consumer<String> setter) {

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Type the new value for " + ChatColor.WHITE + key
            + ChatColor.GRAY + " in chat.");
        player.sendMessage(ChatColor.GRAY + "Current: " + ChatColor.WHITE + current);
        player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.WHITE + "cancel" + ChatColor.GRAY + " to keep it.");

        SF4ChatInput.waitForPlayer(plugin, player, message -> {
            String value = message.trim();

            StorageMenuLayout.sync(() -> {
                if (value.equalsIgnoreCase("cancel")) {
                    player.sendMessage(ChatColor.GRAY + "Left unchanged.");
                } else if (!allowed.matcher(value).matches()) {
                    player.sendMessage(ChatColor.RED + "That value is not accepted for this field.");
                    player.sendMessage(ChatColor.GRAY + "Letters, digits, dots, dashes, underscores and "
                        + "path separators only.");
                } else {
                    setter.accept(value);
                    player.sendMessage(ChatColor.GREEN + key + " set to " + ChatColor.WHITE + value);
                }

                open(plugin, player);
            });
        });
    }

    private static void editPort(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull String key,
            @Nonnull Draft draft) {

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + "Type the new value for " + ChatColor.WHITE + key
            + ChatColor.GRAY + " in chat.");
        player.sendMessage(ChatColor.GRAY + "Current: " + ChatColor.WHITE + draft.port);
        player.sendMessage(ChatColor.GRAY + "Type " + ChatColor.WHITE + "cancel" + ChatColor.GRAY + " to keep it.");

        SF4ChatInput.waitForPlayer(plugin, player, message -> {
            String value = message.trim();

            StorageMenuLayout.sync(() -> {
                if (value.equalsIgnoreCase("cancel")) {
                    player.sendMessage(ChatColor.GRAY + "Left unchanged.");
                    open(plugin, player);
                    return;
                }

                int port;

                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException x) {
                    port = -1;
                }

                if (port < 1 || port > 65535) {
                    player.sendMessage(ChatColor.RED + "A port has to be a number between 1 and 65535.");
                } else {
                    draft.port = port;
                    player.sendMessage(ChatColor.GREEN + key + " set to " + ChatColor.WHITE + port);
                }

                open(plugin, player);
            });
        });
    }

    private static boolean hasPassword(@Nonnull Config config, @Nonnull String section) {
        String key = section + ".password";

        if (!config.contains(key)) {
            return false;
        }

        String value = config.getString(key);
        return value != null && !value.isEmpty();
    }

    private static int defaultPort(@Nonnull StorageProviderType type) {
        return type == StorageProviderType.POSTGRESQL ? 5432 : 3306;
    }

    private static int pageFor(@Nonnull StorageProviderType type) {
        return switch (type) {
            case SQLITE -> 1;
            case MYSQL -> 2;
            case MARIADB -> 3;
            case POSTGRESQL -> 4;
            case LEGACY -> 5;
        };
    }

    @Nonnull
    private static StorageProviderType activeType(@Nonnull Config config, @Nonnull StorageEngineService svc) {
        String configured = config.getString("provider");

        if (configured != null) {
            String trimmed = configured.trim();

            for (StorageProviderType type : StorageProviderType.values()) {
                if (type.name().equalsIgnoreCase(trimmed)) {
                    return type;
                }
            }
        }

        return svc.getProvider().getProviderType();
    }

    @Nonnull
    private static String read(@Nonnull Config config, @Nonnull String key, @Nonnull String fallback) {
        if (!config.contains(key)) {
            return fallback;
        }

        String value = config.getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Nonnull
    private static String describe(@Nonnull Throwable throwable) {
        String message = throwable.getMessage();

        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName()
            : throwable.getClass().getSimpleName() + ": " + message;
    }

    @Nullable
    private static StorageEngineService service() {
        return Slimefun.instance() == null ? null : Slimefun.instance().getStorageEngineService();
    }

    /**
     * The unsaved settings of a single admin, seeded from {@code database.yml}.
     *
     * <p>A draft never holds a password. Applying it writes every field it does hold and
     * leaves the {@code password} key of the target section exactly as it was.
     */
    private static final class Draft {

        private StorageProviderType type = StorageProviderType.LEGACY;
        private String host = "localhost";
        private int port = 3306;
        private String database = "slimefuncore";
        private String username = "root";
        private boolean ssl;
        private String filePath = "data-storage/Slimefun/slimefun.db";

        @Nonnull
        static Draft seed(@Nonnull Config config, @Nonnull StorageProviderType type) {
            Draft draft = new Draft();
            draft.type = type;
            draft.port = defaultPort(type);

            if (type == StorageProviderType.POSTGRESQL) {
                draft.username = "postgres";
            }

            if (type == StorageProviderType.LEGACY) {
                return draft;
            }

            if (type == StorageProviderType.SQLITE) {
                draft.filePath = read(config, "sqlite.database", draft.filePath);
                return draft;
            }

            String section = type.name().toLowerCase(Locale.ROOT);

            draft.host = read(config, section + ".host", draft.host);
            draft.database = read(config, section + ".database", draft.database);
            draft.username = read(config, section + ".username", draft.username);
            draft.ssl = config.getBoolean(section + ".ssl");

            int port = config.contains(section + ".port") ? config.getInt(section + ".port") : 0;

            if (port > 0 && port <= 65535) {
                draft.port = port;
            }

            return draft;
        }
    }
}
