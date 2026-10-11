package io.github.thebusybiscuit.slimefun4.core.commands.subcommands.storage;

import com.google.common.annotations.Beta;

import io.github.thebusybiscuit.slimefun4.core.services.StorageEngineService;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.storage.checkpoint.CheckpointService;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game guide of {@code /sf storage gui}.
 *
 * <p>One page per provider plus an overview, a backup page, a checkpoint page, a migration page
 * and a command page. Every page names the exact {@code database.yml} keys it talks about, so an
 * admin can move between this screen and the file without guessing.
 *
 * <p>The pages are static text. Only the path of {@code database.yml} is read from the
 * running service, which is a field access and safe on the server thread.
 *
 * @author SlimefunCore Contributors
 */
@Beta
final class StorageGuideMenu {

    private static final int[] CARD_SLOTS = { 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34 };
    private static final String MIGRATION_TITLE = "Moving your data";
    private static final String CHECKPOINT_TITLE = "Checkpoints";

    private StorageGuideMenu() {}

    /**
     * Opens the guide on the migration page, whichever position it holds.
     *
     * @param plugin The plugin the menu schedules through
     * @param player The reader
     */
    static void openMigrationPage(@Nonnull Plugin plugin, @Nonnull Player player) {
        openTitled(plugin, player, MIGRATION_TITLE);
    }

    /**
     * Opens the guide on the checkpoint page, whichever position it holds.
     *
     * @param plugin The plugin the menu schedules through
     * @param player The reader
     */
    static void openCheckpointPage(@Nonnull Plugin plugin, @Nonnull Player player) {
        openTitled(plugin, player, CHECKPOINT_TITLE);
    }

    /**
     * Opens the page carrying the given title, falling back to the first page.
     *
     * <p>The page is found by its title rather than by an index, so inserting a page ahead of
     * it cannot silently send the reader somewhere else.
     */
    private static void openTitled(@Nonnull Plugin plugin, @Nonnull Player player, @Nonnull String title) {
        List<Page> pages = pages();

        for (int index = 0; index < pages.size(); index++) {
            if (title.equals(pages.get(index).title())) {
                open(plugin, player, index);

                return;
            }
        }

        open(plugin, player, 0);
    }

    static void open(@Nonnull Plugin plugin, @Nonnull Player player, int page) {
        List<Page> pages = pages();
        int index = Math.max(0, Math.min(page, pages.size() - 1));
        Page current = pages.get(index);

        ChestMenu menu = StorageMenuLayout.newMenu("&2Storage guide &7(" + (index + 1)
            + "/" + pages.size() + ")");

        StorageMenuLayout.fillBackground(menu);
        StorageMenuLayout.addInfoItem(menu, 4, current.icon(), "&a" + current.title(), current.summary());

        List<Card> cards = current.cards();

        for (int slot = 0; slot < cards.size() && slot < CARD_SLOTS.length; slot++) {
            Card card = cards.get(slot);
            StorageMenuLayout.addInfoItem(menu, CARD_SLOTS[slot], card.icon(), "&b" + card.title(), card.lines());
        }

        addNavigation(plugin, player, menu, pages, index);

        menu.open(player);
    }

    private static void addNavigation(
            @Nonnull Plugin plugin,
            @Nonnull Player player,
            @Nonnull ChestMenu menu,
            @Nonnull List<Page> pages,
            int index) {

        int human = index + 1;
        int total = pages.size();

        menu.addItem(StorageMenuLayout.SLOT_PREVIOUS, ChestMenuUtils.getPreviousButton(player, human, total));
        menu.addMenuClickHandler(StorageMenuLayout.SLOT_PREVIOUS, (pl, slot, item, action) -> {
            if (index > 0) {
                open(plugin, pl, index - 1);
            }

            return false;
        });

        List<String> contents = new ArrayList<>();

        for (int page = 0; page < total; page++) {
            contents.add((page == index ? "&a> " : "&7") + (page + 1) + ". " + pages.get(page).title());
        }

        contents.add("");
        contents.add("&7Use the arrows to move between pages.");

        StorageMenuLayout.addInfoItem(menu, StorageMenuLayout.SLOT_CENTER, Material.BOOKSHELF,
            "&bContents", contents);

        menu.addItem(StorageMenuLayout.SLOT_NEXT, ChestMenuUtils.getNextButton(player, human, total));
        menu.addMenuClickHandler(StorageMenuLayout.SLOT_NEXT, (pl, slot, item, action) -> {
            if (index < total - 1) {
                open(plugin, pl, index + 1);
            }

            return false;
        });

        StorageMenuLayout.addBackButton(menu, player, () -> StorageGuiMenu.open(plugin, player));
    }

    @Nonnull
    private static String configPath() {
        StorageEngineService svc = Slimefun.instance() == null
            ? null
            : Slimefun.instance().getStorageEngineService();

        if (svc == null || svc.getDbConfig() == null || svc.getDbConfig().getFile() == null) {
            return "plugins/Slimefun/database.yml";
        }

        return svc.getDbConfig().getFile().getPath();
    }

    @Nonnull
    private static Card card(@Nonnull Material icon, @Nonnull String title, @Nonnull String... lines) {
        return new Card(icon, title, StorageMenuLayout.lore(lines));
    }

    @Nonnull
    private static List<Page> pages() {
        return List.of(overview(), sqlite(), mysql(), mariadb(), postgresql(), legacy(), backups(),
            checkpoints(), migration(), commands());
    }

    @Nonnull
    private static Page overview() {
        return new Page(Material.ENCHANTED_BOOK, "Overview",
            StorageMenuLayout.lore(
                "&7What this panel does and which",
                "&7file it writes.",
                "",
                "&7File: &f" + configPath(),
                "",
                "&7A provider change needs a restart."),
            List.of(
                card(Material.HOPPER, "How a save travels",
                    "&71. Something changes player or",
                    "&7   block data.",
                    "&72. The change lands in the cache.",
                    "&73. The save queue writes it to the",
                    "&7   provider in the background.",
                    "",
                    "&7Clearing the cache keeps unsaved",
                    "&7changes, it only drops read copies."),
                card(Material.ANVIL, "Which provider to pick",
                    "&7One server, keep it simple: &fSQLite",
                    "&7Several servers, one database:",
                    "&f  MySQL&7, &fMariaDB&7, &fPostgreSQL",
                    "&7Keep the old YAML tree: &fLegacy",
                    "",
                    "&aYour data can be copied across with",
                    "&f/sf storage migrate&7. See the",
                    "&f" + MIGRATION_TITLE + "&7 page."),
                card(Material.WRITABLE_BOOK, "database.yml",
                    "&7Key: &fprovider",
                    "&7Values: &fsqlite&7, &fmysql&7, &fmariadb&7,",
                    "&f  postgresql&7, &flegacy",
                    "",
                    "&7Also here: &fauto-create-tables&7,",
                    "&fauto-migrate&7, &fverify-schema",
                    "",
                    "&7Path: &f" + configPath()),
                card(Material.CHEST, "What a backup holds",
                    "&7SQL providers: a JSON dump of every",
                    "&7managed table.",
                    "&7Legacy: a ZIP of the YAML tree.",
                    "&7Block archive: machines, chests and",
                    "&7chunk data, on any provider.",
                    "",
                    "&7All three are checksummed and all",
                    "&7three show up in the browser."),
                card(Material.PAPER, "Only in the file",
                    "&7These keys are not editable here:",
                    "&fpool.*     &7pool sizes and timeouts",
                    "&fcache.*    &7size and expiry",
                    "&fsave.*     &7async flush interval",
                    "&fbackup.*   &7schedule and retention",
                    "&f<provider>.password")));
    }

    @Nonnull
    private static Page sqlite() {
        return new Page(Material.BOOK, "SQLite",
            StorageMenuLayout.lore(
                "&7One database file, no server to run.",
                "",
                "&7Set &fprovider: sqlite",
                "&7The pool is always one connection."),
            List.of(
                card(Material.BARREL, "Keys",
                    "&fsqlite.database",
                    "&7  Path to the database file.",
                    "&7  Default: &fdata-storage/Slimefun/",
                    "&f  slimefun.db",
                    "",
                    "&7Relative paths start at the server",
                    "&7root, next to your world folders."),
                card(Material.LIME_STAINED_GLASS_PANE, "Editable here",
                    "&aThe database file path.",
                    "",
                    "&7There is no host, port, user or",
                    "&7password for SQLite at all."),
                card(Material.SPYGLASS, "Good to know",
                    "&7Copy the file while the server is",
                    "&7stopped if you want a manual copy.",
                    "",
                    "&7Test connection creates the file if",
                    "&7the folder exists, and creates no",
                    "&7tables.")));
    }

    @Nonnull
    private static Page mysql() {
        return new Page(Material.SEA_LANTERN, "MySQL",
            StorageMenuLayout.lore(
                "&7A remote server several Minecraft",
                "&7servers can share.",
                "",
                "&7Set &fprovider: mysql"),
            List.of(
                card(Material.COMPASS, "Keys",
                    "&fmysql.host      &7address",
                    "&fmysql.port      &7default &f3306",
                    "&fmysql.database  &7must already exist",
                    "&fmysql.username",
                    "&fmysql.password  &7file only",
                    "&fmysql.ssl       &7true or false"),
                card(Material.NAME_TAG, "Rights the user needs",
                    "&fSELECT&7, &fINSERT&7, &fUPDATE&7, &fDELETE",
                    "&fCREATE&7 and &fALTER&7 on that database",
                    "",
                    "&7Slimefun creates only its own",
                    "&7tables, never the database itself."),
                card(Material.IRON_DOOR, "Password",
                    "&7Set it in &f" + configPath(),
                    "",
                    "&7This panel shows only whether the",
                    "&7key is set. It never reads, prints",
                    "&7or logs the value.")));
    }

    @Nonnull
    private static Page mariadb() {
        return new Page(Material.PRISMARINE, "MariaDB",
            StorageMenuLayout.lore(
                "&7A MySQL-compatible fork. Same",
                "&7settings, its own driver.",
                "",
                "&7Set &fprovider: mariadb"),
            List.of(
                card(Material.COMPASS, "Keys",
                    "&fmariadb.host      &7address",
                    "&fmariadb.port      &7default &f3306",
                    "&fmariadb.database  &7must already exist",
                    "&fmariadb.username",
                    "&fmariadb.password  &7file only",
                    "&fmariadb.ssl       &7true or false"),
                card(Material.SEA_LANTERN, "Same as MySQL",
                    "&7The table layout, the quoting and",
                    "&7the required rights are identical.",
                    "",
                    "&7Pick this one when your server is",
                    "&7actually MariaDB, so the matching",
                    "&7driver is used."),
                card(Material.SPYGLASS, "Health check",
                    "&7Schema verify walks every managed",
                    "&7table and lists what is missing.",
                    "",
                    "&7A missing column can be fixed with",
                    "&f/sf storage repair&7.")));
    }

    @Nonnull
    private static Page postgresql() {
        return new Page(Material.LAPIS_BLOCK, "PostgreSQL",
            StorageMenuLayout.lore(
                "&7Stricter types, strong guarantees.",
                "",
                "&7Set &fprovider: postgresql"),
            List.of(
                card(Material.COMPASS, "Keys",
                    "&fpostgresql.host      &7address",
                    "&fpostgresql.port      &7default &f5432",
                    "&fpostgresql.database  &7must exist",
                    "&fpostgresql.username  &7often &fpostgres",
                    "&fpostgresql.password  &7file only",
                    "&fpostgresql.ssl       &7true or false"),
                card(Material.NAME_TAG, "Rights the user needs",
                    "&fSELECT&7, &fINSERT&7, &fUPDATE&7, &fDELETE",
                    "&fCREATE&7 on the schema in use",
                    "",
                    "&7Identifiers are quoted with double",
                    "&7quotes here, not backticks."),
                card(Material.SHULKER_SHELL, "Binary columns",
                    "&7Backpack contents are stored in a",
                    "&fBYTEA&7 column.",
                    "",
                    "&7Backup and restore keep those bytes",
                    "&7as they are.")));
    }

    @Nonnull
    private static Page legacy() {
        return new Page(Material.WRITTEN_BOOK, "Legacy YAML",
            StorageMenuLayout.lore(
                "&7The original file tree. This is the",
                "&7shipped default.",
                "",
                "&7Set &fprovider: legacy"),
            List.of(
                card(Material.BARREL, "Keys",
                    "&7None. Legacy always reads and",
                    "&7writes &fdata-storage/Slimefun&7.",
                    "",
                    "&7No host, no user, no password, and",
                    "&7no database to create."),
                card(Material.CHEST, "What a backup holds",
                    "&fPlayers&7 profiles and research",
                    "&fwaypoints",
                    "&fstored-blocks&7 per world",
                    "&fstored-inventories&7 machine contents",
                    "&funiversal-inventories",
                    "&fstored-chunks/chunks.sfc",
                    "",
                    "&7Copied byte for byte into a ZIP."),
                card(Material.ORANGE_STAINED_GLASS_PANE, "No schema",
                    "&7There are no tables here, so the",
                    "&7panel reports integrity as",
                    "&fNot applicable&7 and the table count",
                    "&7as &fn/a&7.",
                    "",
                    "&7That is healthy, not an error."),
                card(Material.SPYGLASS, "When to move on",
                    "&7Thousands of stored blocks make the",
                    "&7file tree slow to read and write.",
                    "",
                    "&7SQLite is the smallest step up and",
                    "&7needs no server.")));
    }

    @Nonnull
    private static Page backups() {
        return new Page(Material.CHEST, "Backups and restore",
            StorageMenuLayout.lore(
                "&7How copies are made, checked and",
                "&7put back.",
                "",
                "&7The home screen shows the folder in",
                "&7use and the result of the last run."),
            List.of(
                card(Material.WRITABLE_BOOK, "Keys",
                    "&fbackup.enabled",
                    "&fbackup.automatic",
                    "&fbackup.interval-hours &7default &f6",
                    "&fbackup.keep-last      &7default &f20",
                    "&fbackup.compress",
                    "&fbackup.directory",
                    "&7  ships as &fdata-storage/Slimefun/",
                    "&f  db-backups"),
                card(Material.PAPER, "What the labels mean",
                    "&aOK&7 checksum matches the file",
                    "&cEMPTY&7 the run captured no data",
                    "&cCHECKSUM MISMATCH&7 the file changed",
                    "&7  or is damaged",
                    "&cFILE MISSING&7 only the metadata is",
                    "&7  left on disk",
                    "",
                    "&7Older empty archives from before",
                    "&7this fix stay listed as &cEMPTY&7."),
                card(Material.LAVA_BUCKET, "Retention",
                    "&7Purging runs only after a backup",
                    "&7that succeeded and captured data.",
                    "",
                    "&7Oldest by creation time goes first,",
                    "&7and the last valid archive is never",
                    "&7deleted.",
                    "",
                    "&7Each kind is counted on its own, so",
                    "&7a block archive never pushes out a",
                    "&7database dump.",
                    "",
                    "&7File and block archives are large,",
                    "&7so their retention is capped at &f5&7."),
                card(Material.REDSTONE_BLOCK, "Restoring",
                    "&7A snapshot of the current data is",
                    "&7taken before anything is replaced.",
                    "",
                    "&cRun it with no players online, then",
                    "&crestart the server.",
                    "",
                    "&7Restore needs two confirmations and",
                    "&fslimefun.admin.storage&7.")));
    }

    @Nonnull
    private static Page checkpoints() {
        return new Page(Material.SHULKER_BOX, CHECKPOINT_TITLE,
            StorageMenuLayout.lore(
                "&7One complete Slimefun state, kept as",
                "&7two halves under one folder.",
                "",
                "&7Folder: &f" + CheckpointService.DEFAULT_DIRECTORY,
                "&7Ids run &fcheckpoint-001&7 upwards."),
            List.of(
                card(Material.CHEST, "The two halves",
                    "&fStorage half",
                    "&7  A ZIP of the file based Slimefun",
                    "&7  data tree: &fPlayers&7, &fwaypoints&7,",
                    "&f  stored-blocks&7, &fstored-inventories&7,",
                    "&f  universal-inventories&7 and",
                    "&f  stored-chunks/chunks.sfc",
                    "",
                    "&fDatabase half",
                    "&7  Database owned player data only:",
                    "&7  researches, backpacks, waypoints.",
                    "",
                    "&7Both are written, then both are",
                    "&7verified. A half valid checkpoint is",
                    "&7never kept."),
                card(Material.BARRIER, "Never in a checkpoint",
                    "&cMinecraft world data.",
                    "",
                    "&7Untouched by create and by restore:",
                    "&fregion/*.mca",
                    "&flevel.dat",
                    "&fentities/",
                    "&fplayerdata/",
                    "",
                    "&7Other plugins' data is outside too."),
                card(Material.HOPPER, "Where the blocks are",
                    "&7Machines, chests and chunk data are",
                    "&7files on &7every&7 provider, MySQL",
                    "&7included.",
                    "",
                    "&aSo they are part of the storage half,",
                    "&anot the database half.",
                    "",
                    "&7Switching provider does not move",
                    "&7them, because they were never in a",
                    "&7database."),
                card(Material.WRITABLE_BOOK, "Keys",
                    "&fcheckpoint.enabled",
                    "&fcheckpoint.automatic",
                    "&fcheckpoint.interval-hours &7default &f12",
                    "&fcheckpoint.only-when-changed",
                    "&fcheckpoint.max-checkpoints &7default &f"
                        + CheckpointService.DEFAULT_MAX_CHECKPOINTS,
                    "&fcheckpoint.compress",
                    "&fcheckpoint.directory",
                    "&fcheckpoint.keep-safety-backups",
                    "",
                    "&7Path: &f" + configPath()),
                card(Material.LAVA_BUCKET, "The order at the limit",
                    "&7With &f" + CheckpointService.DEFAULT_MAX_CHECKPOINTS
                        + "&7 kept and one more asked for:",
                    "&71. Both halves are written.",
                    "&72. Both halves are verified.",
                    "&73. The metadata and checksums are",
                    "&7   re-read from disk.",
                    "&74. &aOnly then&7 is the oldest deleted.",
                    "",
                    "&aA failed run deletes nothing, and its",
                    "&aincomplete folder is removed."),
                card(Material.COMPARATOR, "Change detection",
                    "&7Two flags are reported:",
                    "&fStorage changed&7 and &fDatabase changed",
                    "",
                    "&f/sf checkpoint status",
                    "",
                    "&7It never creates a checkpoint on its",
                    "&7own. With &fonly-when-changed&7 set, the",
                    "&7scheduled run is skipped while both",
                    "&7flags read &fNO&7."),
                card(Material.COMMAND_BLOCK, "Restoring",
                    "&cConsole only. Each half restores independently.",
                    "",
                    "&f/sf checkpoint restore storage|database <id>",
                    "&f/sf checkpoint restore <id> confirm",
                    "",
                    "&7Ten ordered steps, reported one by",
                    "&7one. It refuses while any player is",
                    "&7online, takes a safety copy of the",
                    "&7current state first, and rolls that",
                    "&7copy back if a step fails.",
                    "",
                    "&7Restart the server afterwards."),
                card(Material.PAPER, "History is not rewritten",
                    "&7Restoring an older checkpoint does",
                    "&7not delete the newer ones.",
                    "",
                    "&7It changes the active Slimefun data",
                    "&7state, not the checkpoint list.",
                    "",
                    "&7Safety copies live in the &fsafety",
                    "&7sub-folder, newest &f3&7 kept."),
                card(Material.MINECART, "Not the same as migration",
                    "&fCheckpoint&7 copies both halves of one",
                    "&7state so it can be put back.",
                    "",
                    "&fMigration&7 moves database owned player",
                    "&7data from one provider to another.",
                    "",
                    "&7Migration never writes world files,",
                    "&7never writes &fdata-storage&7, and never",
                    "&7modifies checkpoint history.",
                    "",
                    "&7See the &f" + MIGRATION_TITLE + "&7 page.")));
    }

    @Nonnull
    private static Page migration() {
        return new Page(Material.MINECART, MIGRATION_TITLE,
            StorageMenuLayout.lore(
                "&7Copying your player data from one",
                "&7provider into another.",
                "",
                "&7Nothing is ever removed from where",
                "&7it is now. A migration is a copy."),
            List.of(
                card(Material.PAPER, "The order to follow",
                    "&71. Fill in the target block in",
                    "&7   &f" + configPath(),
                    "&72. Empty the server.",
                    "&73. &f/sf storage migrate targets",
                    "&74. &f/sf storage migrate check <target>",
                    "&75. &f/sf storage migrate start <t> confirm",
                    "&76. &f/sf storage migrate apply <target>",
                    "&77. Restart and look around in game."),
                card(Material.BARRIER, "The rules it enforces",
                    "&cNo players may be online for the",
                    "&creal run. A dry run is fine either",
                    "&cway, it writes nothing.",
                    "&cThe destination must be empty,",
                    "&cunless you pass &foverwrite&c.",
                    "&cOne unreadable record aborts the",
                    "&cwhole run.",
                    "",
                    "&7The count is checked again just",
                    "&7before the destination is opened."),
                card(Material.LIME_STAINED_GLASS_PANE, "What it protects",
                    "&7Before a single row is written:",
                    "&7  a block archive is taken,",
                    "&7  your player data is archived,",
                    "&7  both checksums are checked.",
                    "",
                    "&7The copy is then re-read and",
                    "&7compared player by player."),
                card(Material.HOPPER, "Machines and chests",
                    "&eThey are not moved, because they",
                    "&enever lived in the database.",
                    "",
                    "&7Block data is YAML under",
                    "&fdata-storage/Slimefun&7 on every",
                    "&7provider, MySQL included.",
                    "",
                    "&7They are archived instead, with",
                    "&f/sf storage backup blocks&7."),
                card(Material.YELLOW_STAINED_GLASS_PANE, "Dry run first",
                    "&f/sf storage migrate check <target>",
                    "",
                    "&7Reads every record, converts it in",
                    "&7memory, throws the result away and",
                    "&7lists anything it could not read.",
                    "",
                    "&7It opens no connection to the",
                    "&7destination and writes nothing."),
                card(Material.LEVER, "Finishing the switch",
                    "&f/sf storage migrate apply <target>",
                    "",
                    "&7Rewrites the one &fprovider:&7 line",
                    "&7and leaves every comment alone.",
                    "",
                    "&7It refuses unless a migration into",
                    "&7that provider was verified.",
                    "",
                    "&7Then restart the server."),
                card(Material.REDSTONE_BLOCK, "If it goes wrong",
                    "&7Your old data is still there, so",
                    "&7set &fprovider:&7 back and restart.",
                    "",
                    "&7The two archives taken before the",
                    "&7run are kept forever and are never",
                    "&7purged by retention.",
                    "",
                    "&7Delete them yourself once you are",
                    "&7happy."),
                card(Material.SPYGLASS, "Worth knowing",
                    "&7A copy roughly doubles the space",
                    "&7in use. Check your free disk first.",
                    "",
                    "&fmigrate targets&7 reads the file",
                    "&7only. It reports whether the keys",
                    "&7are filled in, not whether the",
                    "&7server answers.",
                    "",
                    "&7Progress goes to the console.")));
    }

    @Nonnull
    private static Page commands() {
        return new Page(Material.COMMAND_BLOCK, "Commands and permissions",
            StorageMenuLayout.lore(
                "&7Everything here has a command form,",
                "&7usable from the console too."),
            List.of(
                card(Material.ENDER_CHEST, "Reading",
                    "&f/sf storage gui",
                    "&f/sf storage info",
                    "&f/sf storage stats",
                    "&f/sf storage status",
                    "&f/sf storage provider",
                    "&f/sf storage cache",
                    "&f/sf storage backup list",
                    "&f/sf storage backup verify <id>",
                    "&f/sf storage migrate status"),
                card(Material.ANVIL, "Changing",
                    "&f/sf storage verify",
                    "&f/sf storage repair",
                    "&f/sf storage cache clear",
                    "&f/sf storage backup create",
                    "&f/sf storage backup blocks",
                    "&f/sf storage backup restore <id>",
                    "&f/sf storage backup delete <id>",
                    "",
                    "&7An &f<id>&7 accepts a short prefix."),
                card(Material.MINECART, "Moving data",
                    "&f/sf storage migrate",
                    "&f/sf storage migrate targets",
                    "&f/sf storage migrate check <target>",
                    "&f/sf storage migrate start <target>",
                    "&f  confirm &7[&foverwrite&7]",
                    "&f/sf storage migrate apply <target>",
                    "",
                    "&7Everything but &fstatus&7 needs",
                    "&f" + StorageMenuLayout.PERM_MANAGE),
                card(Material.SHULKER_BOX, "Checkpoints",
                    "&f/sf checkpoint list",
                    "&f/sf checkpoint info <id>",
                    "&f/sf checkpoint status",
                    "&f/sf checkpoint create",
                    "&f/sf checkpoint restore storage|database <id>",
                    "&f/sf checkpoint restore <id> confirm",
                    "",
                    "&7Reading needs &fslimefun.command.checkpoint",
                    "&7Creating needs &fslimefun.admin.checkpoint",
                    "&cRestore is console only.",
                    "",
                    "&7An &f<id>&7 accepts &f3&7, &f003&7 or",
                    "&fcheckpoint-003&7."),
                card(Material.NAME_TAG, "Permissions",
                    "&f" + StorageMenuLayout.PERM_VIEW,
                    "&7  read the panel and the pages",
                    "",
                    "&f" + StorageMenuLayout.PERM_MANAGE,
                    "&7  create, restore, delete, repair,",
                    "&7  clear the cache, write the file",
                    "",
                    "&fslimefun.command.checkpoint",
                    "&7  read the checkpoint list and info",
                    "",
                    "&fslimefun.admin.checkpoint",
                    "&7  create checkpoints, and restore",
                    "&7  them from the console only",
                    "",
                    "&7All of them are checked again on",
                    "&7click, so taking one away closes the",
                    "&7door on an open menu."),
                card(Material.CLOCK, "If something looks wrong",
                    "&71. &fstatus&7 for the one-liner.",
                    "&72. &fverify&7 to list schema issues.",
                    "&73. &frepair&7 for missing columns.",
                    "&74. &fbackup create&7 before any bigger",
                    "&7   change.",
                    "",
                    "&7Failures are reported in chat and",
                    "&7in the console, never hidden.")));
    }

    private record Card(@Nonnull Material icon, @Nonnull String title, @Nonnull List<String> lines) {}

    private record Page(
        @Nonnull Material icon,
        @Nonnull String title,
        @Nonnull List<String> summary,
        @Nonnull List<Card> cards
    ) {}
}
