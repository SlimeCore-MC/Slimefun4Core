package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.bakedlibs.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationReport;
import io.github.thebusybiscuit.slimefun4.storage.migration.MigrationTargets;
import io.github.thebusybiscuit.slimefun4.storage.migration.StorageMigrator;
import io.github.thebusybiscuit.slimefun4.storage.provider.StorageProviderType;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The migration screen of {@code /sf storage gui}.
 *
 * <p>One tile per target shows whether its {@code database.yml} block is complete. A left click
 * runs a dry run, which writes nothing; a right click walks two confirmations and then copies
 * every player record. Both run off the server thread and both publish their
 * {@link MigrationReport} on {@link StorageEngineService}, so this screen, {@code /sf storage
 * migrate status} and {@code migrate apply} always agree.
 *
 * <p>Readiness is read from configuration only, no connection is opened while the screen is
 * built. Credentials are reduced to whether they are set: neither the username nor the password
 * is ever rendered, because opening this screen only requires
 * {@link StorageMenuLayout#PERM_VIEW} while migrating requires
 * {@link StorageMenuLayout#PERM_MANAGE}.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class MigrationMenu {

    private static final int[] TARGET_SLOTS = { 19, 20, 21, 22, 23 };
    private static final int SLOT_REPORT = 30;
    private static final int SLOT_APPLY = 31;
    private static final int SLOT_GUIDE = 32;
    private static final int SLOT_REFRESH = 33;
    private static final int PROBLEM_LINES = 4;

    private MigrationMenu() {}

    static void open(@Nonnull Plugin plugin, @Nonnull Player player) {
        StorageMenuLayout.runAsync(plugin, () -> {
            Snapshot snapshot = Snapshot.capture();

            StorageMenuLayout.sync(() -> {
                if (snapshot == null) {
                    player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");

                    return;
                }

                build(plugin, player, snapshot).open(player);
            });
        });
    }

    @Nonnull
    private static ChestMenu build(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull Snapshot snapshot) {

        ChestMenu menu = StorageMenuLayout.newMenu("&2Migrate storage");

        StorageMenuLayout.fillBackground(menu);

        StorageMenuLayout.addInfoItem(menu, 4, Material.MINECART,
            "&aMigrate player data",
            StorageMenuLayout.lore(
                "&7Now on: &f" + snapshot.providerName(),
                "&7database.yml: &f" + snapshot.configured(),
                "",
                "&7A migration copies. Nothing is",
                "&7removed from the current backend,",
                "&7it stays as your fallback.",
                "",
                "&eMachines, chests and chunk data are",
                "&enot moved. They live in YAML on every",
                "&eprovider and are archived instead."));

        StorageMenuLayout.addInfoItem(menu, 13,
            snapshot.online() == 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
            snapshot.online() == 0 ? "&aNo players online" : "&c" + snapshot.online() + " player(s) online",
            onlineLore(snapshot.online()));

        addTargetTiles(plugin, menu, snapshot);
        addActionTiles(plugin, player, menu, snapshot);

        StorageMenuLayout.addBackButton(menu, player, () -> StorageGuiMenu.open(plugin, player));

        return menu;
    }

    @Nonnull
    private static List<String> onlineLore(int online) {
        if (online == 0) {
            return StorageMenuLayout.lore(
                "&7A migration may run right now.",
                "",
                "&7The count is checked again right",
                "&7before the destination is opened, so",
                "&7a player joining still stops it.");
        }

        return StorageMenuLayout.lore(
            "&cA migration is refused while anyone",
            "&cis online.",
            "",
            "&7A dry run still works. It reads and",
            "&7reports without writing anything.",
            "",
            "&7Empty the server for the real run.");
    }

    private static void addTargetTiles(
            @Nonnull Plugin plugin,
            @Nonnull ChestMenu menu,
            @Nonnull Snapshot snapshot) {

        List<MigrationTargets.Readiness> targets = snapshot.targets();

        for (int index = 0; index < targets.size() && index < TARGET_SLOTS.length; index++) {
            MigrationTargets.Readiness readiness = targets.get(index);
            String token = MigrationTargets.token(readiness.type());
            boolean active = readiness.type() == snapshot.current();

            if (active) {
                StorageMenuLayout.addInfoItem(menu, TARGET_SLOTS[index], Material.GRAY_DYE,
                    "&7" + token + " &8(active)",
                    StorageMenuLayout.lore(
                        "&7Where: &f" + readiness.detail(),
                        "",
                        "&7This is the backend you are on,",
                        "&7so there is nothing to migrate."));

                continue;
            }

            StorageMenuLayout.addManagedButton(menu, TARGET_SLOTS[index],
                CustomItemStack.create(icon(readiness), "&b> " + token, targetLore(readiness, snapshot)),
                (pl, right, shift) -> {
                    if (!readiness.complete()) {
                        pl.sendMessage(ChatColor.RED + "database.yml is incomplete for " + token + ": missing "
                            + String.join(", ", readiness.missingKeys()));

                        return;
                    }

                    if (!right) {
                        start(plugin, pl, readiness.type(), true, false);

                        return;
                    }

                    int online = plugin.getServer().getOnlinePlayers().size();

                    if (online > 0) {
                        pl.sendMessage(ChatColor.RED + "Refused: " + online
                            + " player(s) are online. Storage may only be migrated on an empty server.");
                        pl.sendMessage(ChatColor.GRAY + "A dry run works either way. Left click to run one.");

                        return;
                    }

                    confirmMigrate(plugin, pl, readiness.type(), false);
                });
        }
    }

    @Nonnull
    private static List<String> targetLore(
            @Nonnull MigrationTargets.Readiness readiness,
            @Nonnull Snapshot snapshot) {

        List<String> lore = StorageMenuLayout.lore(
            readiness.complete() ? "&aReady to receive data" : "&cdatabase.yml is incomplete",
            "&7Where: &f" + readiness.detail(),
            "&7" + credentials(readiness));

        if (!readiness.complete()) {
            lore.add("&cMissing: &f" + String.join(", ", readiness.missingKeys()));
            lore.add("");
            lore.add("&7Fill those keys in the file first.");

            return lore;
        }

        lore.add("");

        if (snapshot.online() > 0) {
            lore.add("&eLeft click &7for a dry run");
            lore.add("&7  reads everything, writes nothing");
            lore.add("&cThe real run needs an empty server.");

            return lore;
        }

        lore.add("&eLeft click &7for a dry run");
        lore.add("&7  reads everything, writes nothing");
        lore.add("&eRight click &7to migrate");
        lore.add("&7  two confirmations follow");

        return lore;
    }

    private static void addActionTiles(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull Snapshot snapshot) {

        StorageMenuLayout.addInfoItem(menu, SLOT_REPORT, reportIcon(snapshot.report()),
            "&bLast attempt", reportLore(snapshot.report()));

        MigrationReport verified = snapshot.verified();

        if (verified == null) {
            StorageMenuLayout.addInfoItem(menu, SLOT_APPLY, Material.GRAY_DYE,
                "&8> Activate a destination",
                StorageMenuLayout.lore(
                    "&7Nothing has been migrated and",
                    "&7verified yet.",
                    "",
                    "&7Migrate first, then this switches",
                    "&7database.yml over for you."));
        } else {
            String token = MigrationTargets.token(verified.getDestination());

            StorageMenuLayout.addManagedButton(menu, SLOT_APPLY,
                CustomItemStack.create(Material.LEVER, "&a> Activate " + token,
                    StorageMenuLayout.lore(
                        "&7Verified: &f" + verified.getPlayers() + " player(s)",
                        "&7Copied:   &f" + verified.getCopiedEntries() + " entr(ies)",
                        "",
                        "&7Rewrites one line in database.yml:",
                        "&fprovider: " + token,
                        "",
                        "&7Every comment and every other key",
                        "&7is left exactly as it is.",
                        "",
                        "&eClick &7to activate, then restart")),
                (pl, right, shift) -> confirmApply(plugin, pl, verified.getDestination()));
        }

        StorageMenuLayout.addButton(menu, SLOT_GUIDE,
            CustomItemStack.create(Material.ENCHANTED_BOOK, "&b> How this works",
                StorageMenuLayout.lore(
                    "&7The step by step order, what is",
                    "&7backed up and how to roll back.",
                    "",
                    "&eClick &7to read")),
            (pl, right, shift) -> StorageGuideMenu.openMigrationPage(plugin, pl));

        StorageMenuLayout.addButton(menu, SLOT_REFRESH,
            CustomItemStack.create(Material.COMPASS, "&b> Refresh",
                StorageMenuLayout.lore(
                    "&7Re-reads readiness, the online count",
                    "&7and the last report.",
                    "",
                    "&eClick &7to refresh")),
            (pl, right, shift) -> open(plugin, pl));
    }

    private static void confirmMigrate(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull StorageProviderType target,
            boolean overwrite) {

        String token = MigrationTargets.token(target);

        StorageConfirmMenu.open(player, "&6Migrate into " + token + "?", Material.MINECART,
            "&eStep 1 of 2: what will happen",
            StorageMenuLayout.lore(
                "&71. Block data is flushed and archived.",
                "&72. Your player data is archived.",
                "&73. Every record is copied into &f" + token + "&7.",
                "&74. The copy is re-read and compared.",
                "",
                "&7Both archives are checksum checked",
                "&7before anything is written, and a",
                "&7failure at any step writes nothing.",
                "",
                "&cA copy roughly doubles the space used."),
            CustomItemStack.create(Material.LIME_WOOL, "&a> Continue",
                StorageMenuLayout.lore("&7One more confirmation follows.", "", "&eClick &7to continue")),
            () -> confirmMigrateFinal(plugin, player, target, overwrite),
            () -> open(plugin, player));
    }

    private static void confirmMigrateFinal(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull StorageProviderType target,
            boolean overwrite) {

        String token = MigrationTargets.token(target);
        int online = plugin.getServer().getOnlinePlayers().size();

        StorageConfirmMenu.open(player, "&4Start the migration?", Material.REDSTONE_BLOCK,
            "&cStep 2 of 2: this writes to " + token,
            StorageMenuLayout.lore(
                "&7Destination: &f" + token,
                "&7Overwrite:   &f" + (overwrite ? "yes" : "no"),
                "",
                online > 0
                    ? "&c" + online + " player(s) are online, this will"
                    : "&aNo players are online.",
                online > 0
                    ? "&cbe refused before anything is read."
                    : "&7This is the right moment.",
                "",
                "&7A used destination is refused unless",
                "&7you run the command form with",
                "&foverwrite&7.",
                "",
                "&cThis is the last confirmation."),
            CustomItemStack.create(Material.REDSTONE_BLOCK, "&4> Migrate now",
                StorageMenuLayout.lore("&7Runs off the server thread.", "", "&eClick &7to start")),
            () -> start(plugin, player, target, false, overwrite),
            () -> open(plugin, player));
    }

    private static void confirmApply(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull StorageProviderType target) {

        String token = MigrationTargets.token(target);

        StorageConfirmMenu.open(player, "&6Activate " + token + "?", Material.LEVER,
            "&eThis edits database.yml",
            StorageMenuLayout.lore(
                "&7New value: &fprovider: " + token,
                "",
                "&7Nothing else in the file changes and",
                "&7no data is touched.",
                "",
                "&7The server keeps running on the old",
                "&7backend until you restart it.",
                "",
                "&7Your previous data stays where it is."),
            CustomItemStack.create(Material.LEVER, "&a> Write it",
                StorageMenuLayout.lore("&7Then restart the server.", "", "&eClick &7to write")),
            () -> apply(plugin, player, target),
            () -> open(plugin, player));
    }

    private static void start(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull StorageProviderType target,
            boolean dryRun,
            boolean overwrite) {

        StorageEngineService svc = service();
        Slimefun instance = Slimefun.instance();

        if (svc == null || instance == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");

            return;
        }

        player.closeInventory();
        player.sendMessage(ChatColor.GRAY + (dryRun ? "Checking a migration into " : "Migrating into ")
            + MigrationTargets.token(target) + ", this can take a while.");
        player.sendMessage(ChatColor.GRAY + "Every step is written to the server console.");

        StorageMenuLayout.runAsync(plugin, () -> {
            MigrationReport report = StorageMigrator.run(instance, svc.getProvider(), svc.getDbConfig(),
                new StorageMigrator.Request(target, dryRun, overwrite,
                    StorageMigrator.onlinePlayers(instance), true));

            svc.setLastMigrationResult(report);

            StorageMenuLayout.sync(() -> {
                player.sendMessage((report.isSuccess() ? ChatColor.GREEN : ChatColor.RED)
                    + report.describe());

                for (String warning : report.getWarnings()) {
                    player.sendMessage(ChatColor.YELLOW + " ! " + warning);
                }

                int shown = 0;

                for (String problem : report.getProblems()) {
                    if (shown++ < PROBLEM_LINES) {
                        player.sendMessage(ChatColor.RED + " x " + problem);
                    }
                }

                if (shown > PROBLEM_LINES) {
                    player.sendMessage(ChatColor.RED + " x " + (shown - PROBLEM_LINES)
                        + " more, see the console.");
                }

                if (report.isSuccess()) {
                    player.sendMessage(ChatColor.GRAY + "Open the panel again to activate "
                        + MigrationTargets.token(report.getDestination()) + ".");
                }

                open(plugin, player);
            });
        });
    }

    private static void apply(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull StorageProviderType target) {

        StorageEngineService svc = service();

        if (svc == null) {
            player.sendMessage(ChatColor.RED + "The storage engine is not initialized yet.");

            return;
        }

        MigrationReport verified = svc.getLastSuccessfulMigration();

        if (verified == null || verified.getDestination() != target) {
            player.sendMessage(ChatColor.RED + "There is no verified migration into "
                + MigrationTargets.token(target) + " to activate.");

            return;
        }

        try {
            MigrationTargets.applyProvider(svc.getDbConfig().getFile(), target);
        } catch (IOException | RuntimeException x) {
            plugin.getLogger().log(Level.SEVERE, "Could not rewrite database.yml", x);
            player.sendMessage(ChatColor.RED + "database.yml could not be updated: " + x.getMessage());
            player.sendMessage(ChatColor.GRAY + "Nothing changed. The migrated data is still there.");

            return;
        }

        player.sendMessage(ChatColor.GREEN + "database.yml now reads " + ChatColor.WHITE + "provider: "
            + MigrationTargets.token(target));
        player.sendMessage(ChatColor.YELLOW + "Restart the server to start on "
            + MigrationTargets.token(target) + ".");
        player.sendMessage(ChatColor.GRAY + "Your previous data is untouched and remains your fallback.");

        open(plugin, player);
    }

    @Nonnull
    private static Material icon(@Nonnull MigrationTargets.Readiness readiness) {
        if (!readiness.complete()) {
            return Material.ORANGE_STAINED_GLASS_PANE;
        }

        return readiness.type() == StorageProviderType.LEGACY ? Material.WRITTEN_BOOK : Material.BARREL;
    }

    @Nonnull
    private static String credentials(@Nonnull MigrationTargets.Readiness readiness) {
        if (readiness.username() == null) {
            return "No credentials needed";
        }

        return "username " + (readiness.username().isBlank() ? "&cnot set" : "&fset")
            + " &7password " + (readiness.passwordSet() ? "&fSET (hidden)" : "&cNOT SET");
    }

    @Nonnull
    private static Material reportIcon(@Nullable MigrationReport report) {
        if (report == null) {
            return Material.PAPER;
        }

        return switch (report.getOutcome()) {
            case SUCCESS -> Material.LIME_STAINED_GLASS_PANE;
            case DRY_RUN -> Material.YELLOW_STAINED_GLASS_PANE;
            default -> Material.RED_STAINED_GLASS_PANE;
        };
    }

    @Nonnull
    private static List<String> reportLore(@Nullable MigrationReport report) {
        if (report == null) {
            return StorageMenuLayout.lore(
                "&7No migration has been attempted",
                "&7since this server was started.",
                "",
                "&7A dry run is the safe way to look",
                "&7at what would move.");
        }

        List<String> lore = StorageMenuLayout.lore(
            outcomeColor(report) + report.getOutcome().getLabel()
                + " &7at &f" + StorageMenuLayout.formatTimestamp(report.getTimestamp()),
            "&7From: &f" + MigrationTargets.token(report.getSource())
                + " &7to &f" + MigrationTargets.token(report.getDestination()),
            "&7Players: &f" + report.getPlayers()
                + "  &7Entries: &f" + report.getCopiedEntries(),
            "&7Took: &f" + report.getDuration().toMillis() + " ms",
            "&7" + report.getDetail());

        if (report.getPlayerBackupId() != null) {
            lore.add("&7Player backup: &f" + StorageMenuLayout.shorten(report.getPlayerBackupId()));
        }

        if (report.getBlockBackupId() != null) {
            lore.add("&7Block backup:  &f" + StorageMenuLayout.shorten(report.getBlockBackupId()));
        }

        addFirst(lore, report.getProblems());

        for (Map.Entry<UUID, String> mismatch : report.getMismatches().entrySet()) {
            lore.add("&cMismatch: &f" + StorageMenuLayout.shorten(mismatch.getKey().toString())
                + " &7" + mismatch.getValue());

            if (report.getMismatches().size() > 1) {
                lore.add("&cand " + (report.getMismatches().size() - 1) + " more, see the console");
            }

            break;
        }

        return lore;
    }

    private static void addFirst(@Nonnull List<String> lore, @Nonnull List<String> problems) {
        int shown = 0;

        for (String problem : problems) {
            if (shown++ < PROBLEM_LINES) {
                lore.add("&c" + problem);
            }
        }

        if (shown > PROBLEM_LINES) {
            lore.add("&c" + (shown - PROBLEM_LINES) + " more, see the console");
        }
    }

    @Nonnull
    private static String outcomeColor(@Nonnull MigrationReport report) {
        return switch (report.getOutcome()) {
            case SUCCESS -> "&a";
            case DRY_RUN -> "&e";
            default -> "&c";
        };
    }

    @Nullable
    private static StorageEngineService service() {
        return Slimefun.instance() == null ? null : Slimefun.instance().getStorageEngineService();
    }

    private record Snapshot(
        @Nonnull String providerName,
        @Nonnull String configured,
        @Nonnull StorageProviderType current,
        int online,
        @Nonnull List<MigrationTargets.Readiness> targets,
        @Nullable MigrationReport report,
        @Nullable MigrationReport verified
    ) {

        @Nullable
        static Snapshot capture() {
            StorageEngineService svc = service();
            Slimefun instance = Slimefun.instance();

            if (svc == null || instance == null) {
                return null;
            }

            String configured = svc.getDbConfig().getString("provider");

            return new Snapshot(
                svc.getProvider().getProviderName(),
                configured == null || configured.isBlank() ? "not set" : configured,
                svc.getProvider().getProviderType(),
                Math.max(0, StorageMigrator.onlinePlayers(instance).getAsInt()),
                new ArrayList<>(MigrationTargets.inspectAll(svc.getDbConfig())),
                svc.getLastMigrationResult(),
                svc.getLastSuccessfulMigration());
        }
    }
}
