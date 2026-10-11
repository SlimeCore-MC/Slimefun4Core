package io.github.thebusybiscuit.slimefun4.core.guide.options;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.addons.AddonDefinition;
import io.github.thebusybiscuit.slimefun4.core.addons.AddonManager;
import io.github.thebusybiscuit.slimefun4.core.addons.AddonOperationResult;
import io.github.thebusybiscuit.slimefun4.core.addons.AddonService;
import io.github.thebusybiscuit.slimefun4.core.addons.GitHubSyncService;
import io.github.thebusybiscuit.slimefun4.core.addons.ManagedAddonStatus;
import io.github.thebusybiscuit.slimefun4.core.addons.RemoteAddonInfo;
import io.github.thebusybiscuit.slimefun4.core.addons.VersionTagResolver;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu.MenuClickHandler;

import java.util.concurrent.CompletableFuture;

/**
 * Interactive Addon Manager surface embedded in the Slimefun guide settings menu.
 *
 * <p>This mirrors the {@code /sf addon} command workflow (list, status, sync, download, update,
 * enable, disable, remove) as a paged {@link ChestMenu}. It adds no new privileges: every action
 * button re-checks the same permission node the command enforces, and all work is delegated to the
 * already-shipped {@link AddonService} and {@link GitHubSyncService}. Blocking IO
 * ({@code listAddons()}, {@code syncNow()}, the operation futures) runs off the main thread; Bukkit
 * inventory mutation only happens back on the main thread.
 *
 * <p>Buttons use custom player-head textures so the panel does not fall back to plain wool/glass
 * icons. Borders are tinted panes rather than the default gray background.
 *
 * @author SlimefunCore Contributors
 *
 * @see SlimefunGuideSettings
 */
final class AddonGuideMenu {

    static final String PERM_ADMIN = "addon.admin";
    static final String PERM_UPDATE = "addon.update";
    static final String PERM_MANAGE = "addon.manage";

    // ── Optional custom head textures ────────────────────────────────────
    // Paste a real texture value/hash (from e.g. minecraft-heads.com) to replace the material icon
    // for a button. Leave as null to use the tasteful default material. Never put a made-up hash
    // here — an invalid value renders as a broken Steve head in-game.
    private static final String HEAD_BACK = "cdc9e4dcfa4221a1fadc1b5b2b11d8beeb57879af1c42362142bae1edd5";
    private static final String HEAD_PREV = "8550b7f74e9ed7633aa274ea30cc3d2e87abb36d4d1f4ca608cd44590cce0b";
    private static final String HEAD_NEXT = "96339ff2e5342ba18bdc48a99cca65d123ce781d878272f9d964ead3b8ad370";
    private static final String HEAD_DOWNLOAD = "ce1f3cc63c73a6a1dde72fe09c6ac5569376d7b61231bb740764368788cbf1fa";
    private static final String HEAD_SYNC = "11d720cd39df3be74b0cac75e3937f0085a37824743cad6330dc9f4666a4510d";
    private static final String HEAD_INFO = "2b8d0de4ed3a220007a5fd97d2969d02d21075dc55d6f5e4c21d3be6ef4ef98";
    private static final String HEAD_ENABLE = "5ff31431d64587ff6ef98c0675810681f8c13bf96f51d9cb07ed7852b2ffd1";

    private static final int ADDONS_PER_PAGE = 28;

    // List screen layout: rows 0 and 5 are the tinted frame; addons fill the 28 inner slots.
    private static final int[] TOP_BORDER = { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
    private static final int[] INNER_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final int[] EDGE_SLOTS = { 9, 17, 18, 26, 27, 35, 36, 44 };
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 46;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_SYNC = 52;
    private static final int SLOT_NEXT = 53;
    private static final int[] BOTTOM_FILLER = { 47, 48, 50, 51 };

    // Detail screen layout.
    private static final int[] DETAIL_TOP = { 0, 1, 2, 3, 5, 6, 7, 8 };
    private static final int SLOT_DETAIL_ICON = 4;
    private static final int SLOT_DETAIL_REMOTE = 13;
    private static final int SLOT_DETAIL_DOWNLOAD = 19;
    private static final int SLOT_DETAIL_UPDATE = 20;
    private static final int SLOT_DETAIL_ENABLE = 21;
    private static final int SLOT_DETAIL_DISABLE = 23;
    private static final int SLOT_DETAIL_REMOVE = 24;
    private static final int[] DETAIL_MID_FILLER = { 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 22, 25, 26 };
    private static final int SLOT_DETAIL_VERSIONS_HEADER = 27;
    private static final int VERSION_LIST_START = 28;
    private static final int VERSION_LIST_MAX = 7;
    private static final int[] DETAIL_BOTTOM = { 45, 46, 47, 48, 50, 51, 52, 53 };
    private static final int SLOT_DETAIL_BACK = 49;

    private AddonGuideMenu() {}

    /**
     * Returns whether the given {@link Player} may see and use the Addon Manager at all. A player
     * needs at least one addon management node; OPs satisfy all of them by default.
     *
     * @param p
     *            The {@link Player}
     *
     * @return {@code true} if the player holds any addon management node
     */
    static boolean canManage(@Nonnull Player p) {
        return p.hasPermission(PERM_ADMIN) || p.hasPermission(PERM_UPDATE) || p.hasPermission(PERM_MANAGE);
    }

    /**
     * Opens the paged addon list. The addon snapshot is read on a background thread and the menu is
     * built and opened back on the main thread.
     *
     * @param p
     *            The {@link Player} to open the menu for
     * @param guide
     *            The guide {@link ItemStack} (used for the back button to settings)
     * @param page
     *            The zero-based page index to open
     */
    @ParametersAreNonnullByDefault
    static void open(Player p, ItemStack guide, int page) {
        AddonService service = requireService(p);

        if (service == null) {
            return;
        }

        AddonHeadIcons.preload();

        Slimefun.getThreadService().newThread(Slimefun.instance(), "AddonGuideMenu#list", () -> {
            List<ManagedAddonStatus> statuses = service.listAddons();
            Slimefun.runSync(() -> buildList(p, guide, statuses, page).open(p));
        });
    }

    @Nonnull
    @ParametersAreNonnullByDefault
    private static ChestMenu buildList(Player p, ItemStack guide, List<ManagedAddonStatus> statuses, int page) {
        int pages = Math.max(1, (statuses.size() + ADDONS_PER_PAGE - 1) / ADDONS_PER_PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));

        ChestMenu menu = new ChestMenu("&9✦ &bAddon Manager &7(" + (current + 1) + "/" + pages + ")");
        menu.setEmptySlotsClickable(false);

        ItemStack topPane = pane(Material.BLUE_STAINED_GLASS_PANE, "&9");
        ItemStack edgePane = pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        ItemStack bottomPane = pane(Material.CYAN_STAINED_GLASS_PANE, " ");

        for (int slot : TOP_BORDER) {
            menu.addItem(slot, topPane, empty());
        }
        for (int slot : EDGE_SLOTS) {
            menu.addItem(slot, edgePane, empty());
        }
        for (int slot : BOTTOM_FILLER) {
            menu.addItem(slot, bottomPane, empty());
        }

        int first = current * ADDONS_PER_PAGE;

        for (int i = 0; i < INNER_SLOTS.length; i++) {
            int position = first + i;
            int slot = INNER_SLOTS[i];

            if (position >= statuses.size()) {
                menu.addItem(slot, null, empty());
                continue;
            }

            ManagedAddonStatus status = statuses.get(position);
            menu.addItem(slot, describeAddon(status));
            menu.addMenuClickHandler(slot, (pl, s, item, action) -> {
                openDetail(pl, guide, status.definition().key());
                return false;
            });
        }

        if (statuses.isEmpty()) {
            menu.addItem(22, CustomItemStack.create(Material.BARRIER, "&cNo managed addons",
                "",
                "&7No addons are defined in &faddonmanager.yml&7.",
                "&7Use the &bSync &7button to fetch the latest",
                "&7definitions from GitHub."),
                empty());
        }

        // Back to settings.
        menu.addItem(SLOT_BACK, icon(HEAD_BACK, Material.ARROW, "&e⇦ Back",
            "", "&7Return to the guide settings"));
        menu.addMenuClickHandler(SLOT_BACK, (pl, s, item, action) -> {
            SlimefunGuideSettings.openSettings(pl, guide);
            return false;
        });

        // Prev / Next as tinted panes so the disabled state reads clearly.
        menu.addItem(SLOT_PREVIOUS, current > 0
            ? icon(HEAD_PREV, Material.LIME_STAINED_GLASS_PANE, "&a⇦ Previous Page", "", "&7(" + current + " / " + pages + ")")
            : pane(Material.GRAY_STAINED_GLASS_PANE, "&8⇦ Previous Page"));
        menu.addMenuClickHandler(SLOT_PREVIOUS, (pl, s, item, action) -> {
            if (current > 0) {
                open(pl, guide, current - 1);
            }
            return false;
        });

        menu.addItem(SLOT_NEXT, current < pages - 1
            ? icon(HEAD_NEXT, Material.LIME_STAINED_GLASS_PANE, "&aNext Page ⇨", "", "&7(" + (current + 1) + " / " + pages + ")")
            : pane(Material.GRAY_STAINED_GLASS_PANE, "&8Next Page ⇨"));
        menu.addMenuClickHandler(SLOT_NEXT, (pl, s, item, action) -> {
            if (current < pages - 1) {
                open(pl, guide, current + 1);
            }
            return false;
        });

        menu.addItem(SLOT_INFO, icon(HEAD_INFO, Material.KNOWLEDGE_BOOK, "&b✦ Addon Manager",
            "",
            "&7Manage the addons defined in",
            "&faddonmanager.yml&7 without leaving the game.",
            "",
            "&7Click an addon to view its versions and",
            "&7to download, enable, disable or remove it.",
            "",
            "&8Every action needs the matching addon.*",
            "&8permission and takes effect after a restart."),
            empty());

        boolean canSync = p.hasPermission(PERM_ADMIN) || p.hasPermission(PERM_UPDATE);

        if (canSync) {
            menu.addItem(SLOT_SYNC, icon(HEAD_SYNC, Material.COMPASS, "&b↻ Sync from GitHub",
                "",
                "&7Fetches the latest &faddonmanager.yml",
                "&7from the configured cloud source and",
                "&7re-reads the addon definitions.",
                "",
                "&8Needs " + PERM_ADMIN + " or " + PERM_UPDATE,
                "",
                "&e⇨ Click to sync"));
            menu.addMenuClickHandler(SLOT_SYNC, (pl, s, item, action) -> {
                syncFromGitHub(pl, guide, current);
                return false;
            });
        } else {
            menu.addItem(SLOT_SYNC, bottomPane, empty());
        }

        return menu;
    }

    @ParametersAreNonnullByDefault
    private static void syncFromGitHub(Player p, ItemStack guide, int page) {
        if (!p.hasPermission(PERM_ADMIN) && !p.hasPermission(PERM_UPDATE)) {
            p.sendMessage(ChatColor.RED + "You need " + PERM_ADMIN + " or " + PERM_UPDATE + " to sync addons.");
            return;
        }

        AddonManager manager = Slimefun.getAddonManager();

        p.closeInventory();
        p.sendMessage(ChatColor.GRAY + "Syncing addon definitions from GitHub...");

        GitHubSyncService sync = manager.getGitHubSyncService();
        sync.syncNow().whenComplete((result, error) -> Slimefun.runSync(() -> {
            if (error != null) {
                p.sendMessage(ChatColor.RED + "Sync failed: " + error.getMessage());
            } else {
                p.sendMessage(ChatColor.GRAY + "Sync: " + ChatColor.WHITE + result.message());
            }
            open(p, guide, page);
        }));
    }

    @ParametersAreNonnullByDefault
    private static void openDetail(Player p, ItemStack guide, String key) {
        AddonService service = requireService(p);

        if (service == null) {
            return;
        }

        Slimefun.getThreadService().newThread(Slimefun.instance(), "AddonGuideMenu#detail", () -> {
            ManagedAddonStatus status = service.listAddons().stream()
                .filter(s -> s.definition().key().equalsIgnoreCase(key))
                .findFirst()
                .orElse(null);

            // Read the addon's remote source (GitHub release API / download metadata) so the detail
            // screen can show which version the repository currently advertises. Same thread, so the
            // menu opens already populated. Null when disabled or the lookup failed.
            RemoteAddonInfo remote = null;
            if (status != null && isLiveGitHubCheckEnabled()) {
                try {
                    remote = service.inspectRemote(key).join();
                } catch (Exception ignored) {
                    // Fall back to locally-known data.
                }
            }

            RemoteAddonInfo remoteInfo = remote;
            Slimefun.runSync(() -> {
                if (status == null) {
                    p.sendMessage(ChatColor.RED + "That addon is no longer available.");
                    open(p, guide, 0);
                    return;
                }

                buildDetail(p, guide, status, remoteInfo).open(p);
            });
        });
    }

    private static boolean isLiveGitHubCheckEnabled() {
        Slimefun instance = Slimefun.instance();
        return instance != null && instance.getConfig().getBoolean("addon-manager.live-github-check", true);
    }

    @Nonnull
    @ParametersAreNonnullByDefault
    private static ChestMenu buildDetail(Player p, ItemStack guide, ManagedAddonStatus status, @javax.annotation.Nullable RemoteAddonInfo remote) {
        AddonDefinition def = status.definition();
        boolean compatible = def.isCompatibleWith(Slimefun.getMinecraftVersion());

        ChestMenu menu = new ChestMenu("&9✦ &b" + stripColor(def.name()));
        menu.setEmptySlotsClickable(false);

        ItemStack topPane = pane(Material.BLUE_STAINED_GLASS_PANE, "&9");
        ItemStack midPane = pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ");
        ItemStack bottomPane = pane(Material.CYAN_STAINED_GLASS_PANE, " ");

        for (int slot : DETAIL_TOP) {
            menu.addItem(slot, topPane, empty());
        }
        for (int slot : DETAIL_MID_FILLER) {
            menu.addItem(slot, midPane, empty());
        }
        for (int slot : DETAIL_BOTTOM) {
            menu.addItem(slot, bottomPane, empty());
        }

        menu.addItem(SLOT_DETAIL_ICON, describeAddon(status), empty());
        menu.addItem(SLOT_DETAIL_REMOTE, remoteInfoTile(def, status, remote), empty());

        addActionButton(menu, SLOT_DETAIL_DOWNLOAD, guide, def.key(),
            HEAD_DOWNLOAD, Material.HOPPER, "&aDownload", "download", PERM_ADMIN, PERM_UPDATE,
            "&7Downloads the compatible jar for this",
            "&7server version into the repository.");

        addActionButton(menu, SLOT_DETAIL_UPDATE, guide, def.key(),
            HEAD_SYNC, Material.PISTON, "&aCheck for update", "check for update", PERM_ADMIN, PERM_UPDATE,
            "&7Downloads a newer version if one is",
            "&7available (needs download enabled).");

        addActionButton(menu, SLOT_DETAIL_ENABLE, guide, def.key(),
            HEAD_ENABLE, Material.LIME_DYE, "&aEnable", "enable", PERM_ADMIN, PERM_MANAGE,
            "&7Enables the addon and downloads it so",
            "&7it loads on the next restart.");

        addActionButton(menu, SLOT_DETAIL_DISABLE, guide, def.key(),
            null, Material.GRAY_DYE, "&cDisable", "disable", PERM_ADMIN, PERM_MANAGE,
            "&7Disables the addon and removes its jar",
            "&7(on the next restart if it is loaded).");

        addActionButton(menu, SLOT_DETAIL_REMOVE, guide, def.key(),
            null, Material.LAVA_BUCKET, "&4Remove", "remove", PERM_ADMIN, PERM_MANAGE,
            "&7Disables downloads and schedules the",
            "&7jar for removal after the next restart.");

        // Version "folders": every version tag configured for this addon, classified against this
        // server. A tag is compatible only when it is one this server accepts AND has a jar URL.
        List<String> serverTags = VersionTagResolver.resolveTags(Slimefun.getMinecraftVersion());
        String activeVersion = def.version();

        menu.addItem(SLOT_DETAIL_VERSIONS_HEADER, versionSummaryTile(def, serverTags), empty());

        int slot = VERSION_LIST_START;
        int shown = 0;

        for (Map.Entry<String, String> entry : def.versionUrls().entrySet()) {
            if (shown >= VERSION_LIST_MAX) {
                break;
            }

            String tag = entry.getKey();
            boolean hasUrl = entry.getValue() != null && !entry.getValue().isBlank();
            boolean serverAccepts = containsIgnoreCase(serverTags, tag);
            boolean compatibleHere = hasUrl && serverAccepts;
            boolean active = tag.equalsIgnoreCase(activeVersion) && compatibleHere;

            Material material;
            String prefix;
            String statusLine;
            if (!hasUrl) {
                material = Material.RED_STAINED_GLASS_PANE;
                prefix = "&c✖ ";
                statusLine = "&cNo jar uploaded for this version.";
            } else if (compatibleHere) {
                material = Material.LIME_STAINED_GLASS_PANE;
                prefix = "&a✔ ";
                statusLine = active
                    ? "&aCompatible — used by this server."
                    : "&aCompatible with this server.";
            } else {
                material = Material.YELLOW_STAINED_GLASS_PANE;
                prefix = "&e● ";
                statusLine = "&eJar available, but for another MC version.";
            }

            menu.addItem(slot, CustomItemStack.create(material,
                prefix + "&fVersion " + tag + (active ? " &7(active)" : ""),
                "",
                "&7Jar:",
                "&f" + shorten(entry.getValue()),
                "",
                statusLine),
                empty());

            slot++;
            shown++;
        }

        if (def.versionUrls().isEmpty()) {
            menu.addItem(VERSION_LIST_START, CustomItemStack.create(Material.BARRIER, "&cNo versions configured",
                "", "&7This addon has no version folders in",
                "&faddonmanager.yml&7."),
                empty());
        } else if (def.versionUrls().size() > VERSION_LIST_MAX) {
            menu.addItem(VERSION_LIST_START + VERSION_LIST_MAX, CustomItemStack.create(Material.PAPER,
                "&7+ " + (def.versionUrls().size() - VERSION_LIST_MAX) + " more version(s)",
                "", "&7Not all version folders fit on screen."),
                empty());
        }

        if (!compatible) {
            menu.addItem(SLOT_DETAIL_ICON, CustomItemStack.create(Material.BARRIER, "&c" + def.name() + " &7(incompatible)",
                "",
                "&7No jar is configured for this server's",
                "&7Minecraft version. Actions may fail until",
                "&7a compatible version is added upstream."),
                empty());
        }

        menu.addItem(SLOT_DETAIL_BACK, icon(HEAD_BACK, Material.ARROW, "&e⇦ Back",
            "", "&7Return to the addon list"));
        menu.addMenuClickHandler(SLOT_DETAIL_BACK, (pl, s, item, action) -> {
            open(pl, guide, 0);
            return false;
        });

        return menu;
    }

    private static void addActionButton(
            @Nonnull ChestMenu menu,
            int slot,
            @Nonnull ItemStack guide,
            @Nonnull String key,
            @javax.annotation.Nullable String headTexture,
            @Nonnull Material fallback,
            @Nonnull String title,
            @Nonnull String action,
            @Nonnull String primaryPerm,
            @Nonnull String secondaryPerm,
            @Nonnull String... description) {

        String[] lore = new String[description.length + 4];
        lore[0] = "";
        System.arraycopy(description, 0, lore, 1, description.length);
        lore[description.length + 1] = "";
        lore[description.length + 2] = "&8Needs " + primaryPerm + " or " + secondaryPerm;
        lore[description.length + 3] = "&e⇨ Click";

        menu.addItem(slot, icon(headTexture, fallback, title, lore));
        menu.addMenuClickHandler(slot, (pl, s, item, act) -> {
            runAction(pl, guide, key, action, title, primaryPerm, secondaryPerm);
            return false;
        });
    }

    @ParametersAreNonnullByDefault
    private static void runAction(Player p, ItemStack guide, String key, String action, String title, String primaryPerm, String secondaryPerm) {
        if (!p.hasPermission(primaryPerm) && !p.hasPermission(secondaryPerm)) {
            p.sendMessage(ChatColor.RED + "You need " + primaryPerm + " or " + secondaryPerm + " to do that.");
            return;
        }

        AddonService service = requireService(p);

        if (service == null) {
            return;
        }

        p.closeInventory();
        p.sendMessage(ChatColor.GRAY + "Running " + ChatColor.WHITE + stripColor(title).toLowerCase()
            + ChatColor.GRAY + " for " + ChatColor.WHITE + key + ChatColor.GRAY + "...");

        resolveAction(service, action, key).whenComplete((result, error) -> Slimefun.runSync(() -> {
            if (error != null) {
                p.sendMessage(ChatColor.RED + "Operation failed: " + error.getMessage());
            } else {
                p.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message());
                if (result.restartRequired()) {
                    p.sendMessage(ChatColor.YELLOW + "Takes effect after the next restart.");
                }
            }
            openDetail(p, guide, key);
        }));
    }

    @Nonnull
    private static CompletableFuture<AddonOperationResult> resolveAction(
            @Nonnull AddonService service, @Nonnull String action, @Nonnull String key) {
        return switch (action) {
            case "download" -> service.download(key);
            case "check for update" -> service.update(key);
            case "enable" -> service.enable(key);
            case "disable" -> service.disable(key);
            case "remove" -> service.remove(key);
            default -> CompletableFuture.completedFuture(
                AddonOperationResult.failure("Unknown action: " + action, null));
        };
    }

    @Nonnull
    private static ItemStack describeAddon(@Nonnull ManagedAddonStatus status) {
        AddonDefinition def = status.definition();
        boolean compatible = def.isCompatibleWith(Slimefun.getMinecraftVersion());

        String state;
        String nameColor;
        Material paneIcon;
        if (!compatible) {
            paneIcon = Material.BARRIER;
            state = "&cIncompatible";
            nameColor = "&c";
        } else if (status.loaded()) {
            paneIcon = Material.LIME_STAINED_GLASS_PANE;
            state = "&aLoaded";
            nameColor = "&a";
        } else if (status.jarPresent()) {
            paneIcon = Material.YELLOW_STAINED_GLASS_PANE;
            state = "&eDownloaded (not loaded)";
            nameColor = "&e";
        } else {
            paneIcon = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            state = "&7Not installed";
            nameColor = "&f";
        }

        String[] lore = {
            "",
            "&7State: " + state,
            "&7Key: &f" + def.key(),
            "",
            "&7Enabled: " + flag(def.enabled()) + "  &7Download: " + flag(def.download()),
            "&7Auto-update: " + flag(def.autoUpdate()),
            "&7Jar present: " + flag(status.jarPresent()) + "  &7Loaded: " + flag(status.loaded()),
            "&7Cached version: &f" + (status.cachedVersion() == null ? "none" : status.cachedVersion()),
            "&7Resolved version: &f" + def.version(),
            "",
            "&e⇨ Click to manage"
        };

        String name = "&b✦ " + nameColor + def.name();

        // Prefer a decorative player-head icon per addon; fall back to the state pane if the head
        // pool is unavailable (feature disabled, not yet fetched, or the fetch failed).
        Optional<ItemStack> head = AddonHeadIcons.iconFor(def.key());
        if (head.isPresent()) {
            return CustomItemStack.create(head.get(), name, lore);
        }

        return CustomItemStack.create(paneIcon, name, lore);
    }

    /**
     * Builds the tile that reports what the addon's remote source (GitHub release / download metadata)
     * currently advertises versus what is installed on this server.
     */
    @Nonnull
    private static ItemStack remoteInfoTile(@Nonnull AddonDefinition def, @Nonnull ManagedAddonStatus status, @javax.annotation.Nullable RemoteAddonInfo remote) {
        String installed = status.cachedVersion() != null ? status.cachedVersion() : (status.jarPresent() ? "present (unknown version)" : "not installed");

        if (remote == null) {
            boolean enabled = isLiveGitHubCheckEnabled();
            return CustomItemStack.create(Material.MAP, "&b📡 Remote / GitHub",
                "",
                "&7Installed: &f" + installed,
                "&7Resolved (server): &f" + def.version(),
                "",
                enabled ? "&cCould not read the remote source." : "&8Live GitHub check is disabled in config.",
                enabled ? "&7Check the URL/apiUrl in addonmanager.yml." : "&8Set addon-manager.live-github-check: true.");
        }

        String updateLine = remote.updateAvailable()
            ? "&aUpdate available &7(remote differs from installed)"
            : "&7Up to date";

        return CustomItemStack.create(Material.MAP, "&b📡 Remote / GitHub",
            "",
            "&7Installed: &f" + installed,
            "&7Remote version: &f" + remote.resolvedVersion(),
            "&7Resolved (server): &f" + def.version(),
            "",
            "&7Jar for this version:",
            "&f" + shorten(remote.downloadUrl()),
            "",
            updateLine,
            "&8" + remote.message());
    }

    /**
     * Builds the header tile for the version-folder list. It summarises which Minecraft version this
     * server runs, the tag the addon resolves to here, and a tally of how many configured version
     * folders are compatible with this server, available only for another MC version, or missing a jar.
     */
    @Nonnull
    private static ItemStack versionSummaryTile(@Nonnull AddonDefinition def, @Nonnull List<String> serverTags) {
        int compatible = 0;
        int otherVersion = 0;
        int missing = 0;

        for (Map.Entry<String, String> entry : def.versionUrls().entrySet()) {
            boolean hasUrl = entry.getValue() != null && !entry.getValue().isBlank();
            boolean serverAccepts = containsIgnoreCase(serverTags, entry.getKey());
            if (!hasUrl) {
                missing++;
            } else if (serverAccepts) {
                compatible++;
            } else {
                otherVersion++;
            }
        }

        String resolved = def.version();
        String resolvedLine = resolved == null || resolved.isBlank()
            ? "&cnone for this server"
            : "&f" + resolved;

        return CustomItemStack.create(Material.BOOK, "&b✦ Version folders",
            "",
            "&7This server runs MC &f" + Slimefun.getMinecraftVersion(),
            "&7Accepted tags: &f" + String.join("&7, &f", serverTags),
            "&7Resolved for this server: " + resolvedLine,
            "",
            "&7Configured folders:",
            "&a✔ " + compatible + " compatible here",
            "&e● " + otherVersion + " for another MC version",
            "&c✖ " + missing + " with no jar uploaded",
            "",
            "&8A folder is compatible only when this server",
            "&8accepts its tag and it has an uploaded jar.");
    }

    /**
     * Case-insensitive membership test for a list of version tags.
     */
    private static boolean containsIgnoreCase(@Nonnull List<String> list, @javax.annotation.Nullable String value) {
        if (value == null) {
            return false;
        }
        for (String candidate : list) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    // ── Small item helpers ───────────────────────────────────────────────

    /**
     * Builds a button icon. If {@code texture} is a non-blank head texture it is used; otherwise the
     * given {@code fallback} material is used. This keeps the panel clean when no custom head is
     * configured and lets an operator drop in real textures without touching call sites.
     */
    @Nonnull
    private static ItemStack icon(@javax.annotation.Nullable String texture, @Nonnull Material fallback, @Nonnull String name, @Nonnull String... lore) {
        if (texture != null && !texture.isBlank()) {
            return CustomItemStack.create(SlimefunUtils.getCustomHead(texture), name, lore);
        }
        return CustomItemStack.create(fallback, name, lore);
    }

    @Nonnull
    private static ItemStack pane(@Nonnull Material material, @Nonnull String name) {
        return CustomItemStack.create(material, name);
    }

    @Nonnull
    private static MenuClickHandler empty() {
        return (pl, s, item, action) -> false;
    }

    @javax.annotation.Nullable
    private static AddonService requireService(@Nonnull Player p) {
        AddonManager manager = Slimefun.getAddonManager();

        if (manager == null) {
            p.sendMessage(ChatColor.RED + "The addon manager is not available on this server.");
            return null;
        }

        return manager.getAddonService();
    }

    @Nonnull
    private static String flag(boolean value) {
        return value ? "&aYes" : "&cNo";
    }

    @Nonnull
    private static String shorten(String url) {
        if (url == null || url.isBlank()) {
            return "none";
        }
        return url.length() <= 40 ? url : url.substring(0, 39) + "…";
    }

    @Nonnull
    private static String stripColor(@Nonnull String value) {
        String stripped = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
        return stripped == null ? value : stripped;
    }
}
