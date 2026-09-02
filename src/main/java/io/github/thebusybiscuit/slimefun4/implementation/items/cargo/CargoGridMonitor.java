package io.github.thebusybiscuit.slimefun4.implementation.items.cargo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang.WordUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import io.github.thebusybiscuit.slimefun4.api.geo.GEOResource;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoUtils;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4ChatInput;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Colors;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4InvUtils;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4ItemUtils;
import io.github.thebusybiscuit.slimefun4.libraries.bridge.SF4Items;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.HeadTexture;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;

import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;

/**
 * Live 54-slot dashboard for a {@code CARGO_MANAGER}'s attached {@link CargoNet}.
 *
 * <p>The monitor is intentionally read-only except for explicit player deposits and
 * withdrawals. All network scans and transactions are performed on the Bukkit thread
 * and guarded by a per-manager mutex so the GUI never displays a stale plan while an
 * extraction/deposit is being applied.</p>
 */
public final class CargoGridMonitor implements Listener {

    private static final int[] GRID_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private static final int[] FILLER_SLOTS = {
        1, 2, 3, 4, 5, 6, 7,
        17, 26, 35, 36, 44, 45,
        46, 50, 51, 52, 53
    };

    private static final int STATUS_SLOT = 0;
    private static final int INPUT_SLOT = 9;
    private static final int OUTPUT_SLOT = 18;
    private static final int SUMMARY_SLOT = 27;
    private static final int PREVIOUS_SLOT = 47;
    private static final int PAGE_SLOT = 48;
    private static final int NEXT_SLOT = 49;
    private static final int INNER_PREVIOUS_SLOT = 50;
    private static final int SEARCH_SLOT = 8;
    private static final int INNER_NEXT_SLOT = 52;
    private static final int PAGE_SIZE = GRID_SLOTS.length;
    private static final long LIVE_REFRESH_INTERVAL_TICKS = 10L;

    private static final ItemCategory MISC = new ItemCategory("Miscellaneous", (stack, sfId, material) -> true);
    private static final List<ItemCategory> CATEGORIES = List.of(
        new ItemCategory("Resources", CargoGridMonitor::isResourceItem),
        new ItemCategory("Technical", CargoGridMonitor::isTechnicalItem),
        new ItemCategory("Tools & Weapons", CargoGridMonitor::isToolOrWeaponItem),
        new ItemCategory("Vanilla Items", (stack, sfId, material) -> sfId == null),
        MISC
    );

    private static boolean isResourceItem(@Nonnull ItemStack stack, @Nullable String sfId, @Nonnull Material material) {
        if (matchesGeoResource(stack)) {
            return true;
        }

        return sfId != null && containsAny(sfId,
            "RESOURCE",
            "DUST",
            "INGOT",
            "NUGGET",
            "FRAGMENT",
            "CHUNK",
            "ORE",
            "RAW",
            "GEM",
            "CRYSTAL",
            "SHARD",
            "POWDER",
            "PLATE",
            "ROD",
            "OIL",
            "URANIUM",
            "CARBON",
            "CARBONADO",
            "SALT",
            "NETHER_ICE",
            "ICE",
            "ALLOY",
            "SYNTHETIC",
            "BILLON",
            "BRONZE",
            "STEEL",
            "DAMASCUS",
            "CORINTHIAN",
            "ALUMINUM",
            "ALUMINIUM",
            "COPPER",
            "TIN",
            "ZINC",
            "SILVER",
            "LEAD",
            "MAGNESIUM",
            "NICKEL",
            "COBALT",
            "TITANIUM",
            "PLATINUM",
            "SILICON",
            "SULFATE",
            "ACID",
            "HYDROGEN",
            "OXYGEN",
            "NITROGEN",
            "CHLORIDE",
            "FLUORIDE",
            "POLYMER",
            "PLASTIC",
            "FUEL",
            "COOLANT"
        );
    }

    private static boolean matchesGeoResource(@Nonnull ItemStack stack) {
        try {
            for (GEOResource resource : Slimefun.getRegistry().getGEOResources().values()) {
                if (SlimefunUtils.isItemSimilar(stack, resource.getItem(), true, false)) {
                    return true;
                }
            }
        } catch (IllegalStateException | LinkageError ignored) {
            // The registry can be unavailable in isolated tests; ID matching still covers normal runtime.
        }

        return false;
    }

    private static boolean isTechnicalItem(@Nonnull ItemStack stack, @Nullable String sfId, @Nonnull Material material) {
        return sfId != null && containsAny(sfId,
            "MACHINE",
            "CIRCUIT",
            "COMPONENT",
            "CAPACITOR",
            "PROCESSOR",
            "MOTOR",
            "GEAR",
            "PLATING",
            "GENERATOR",
            "REACTOR",
            "ENERGY",
            "SOLAR",
            "BATTERY",
            "ANDROID",
            "GPS",
            "CARGO",
            "NETWORK",
            "AUTO",
            "ELECTRIC",
            "ENHANCED"
        );
    }

    private static boolean isToolOrWeaponItem(@Nonnull ItemStack stack, @Nullable String sfId, @Nonnull Material material) {
        if (sfId != null) {
            return containsAny(sfId,
                "PICKAXE",
                "_AXE",
                "SHOVEL",
                "HOE",
                "DRILL",
                "SAW",
                "WRENCH",
                "CUTTER",
                "HAMMER",
                "TOOL",
                "SWORD",
                "BLADE",
                "BOW",
                "GUN",
                "SABER",
                "SCYTHE",
                "DAGGER",
                "STAFF",
                "TRIDENT",
                "HELMET",
                "CHESTPLATE",
                "LEGGINGS",
                "BOOTS",
                "ARMOR",
                "SHIELD"
            );
        }

        return material.name().endsWith("_PICKAXE")
            || material.name().endsWith("_AXE")
            || material.name().endsWith("_SHOVEL")
            || material.name().endsWith("_HOE")
            || material.name().endsWith("_SWORD")
            || material.name().endsWith("_HELMET")
            || material.name().endsWith("_CHESTPLATE")
            || material.name().endsWith("_LEGGINGS")
            || material.name().endsWith("_BOOTS")
            || material == Material.BOW
            || material == Material.CROSSBOW
            || material == Material.TRIDENT
            || material == Material.SHIELD;
    }

    private static boolean containsAny(@Nonnull String value, @Nonnull String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }

        return false;
    }

    private final Map<Location, MonitorState> states = new ConcurrentHashMap<>();
    private final Map<Location, Object> transactionLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> openMonitors = new ConcurrentHashMap<>();
    private final Set<Location> queuedRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<Location> queuedLoadingRefreshes = ConcurrentHashMap.newKeySet();
    private final Set<Location> interactionInProgress = ConcurrentHashMap.newKeySet();
    private BukkitTask liveRefreshTask;
    private boolean listenerRegistered;

    /**
     * Registers this monitor's Bukkit listeners and starts the lightweight live refresh task.
     *
     * @param plugin
     *            The Slimefun plugin instance
     */
    public void registerListeners(@Nonnull Slimefun plugin) {
        if (!listenerRegistered) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }

        if (liveRefreshTask == null) {
            liveRefreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMonitors, LIVE_REFRESH_INTERVAL_TICKS, LIVE_REFRESH_INTERVAL_TICKS);
        }
    }

    /**
     * Initializes the shared {@link BlockMenuPreset} layout used by every cargo manager monitor.
     *
     * @param preset
     *            The preset being registered by {@link CargoManager}
     */
    public void initPreset(@Nonnull BlockMenuPreset preset) {
        preset.setSize(54);
        preset.setEmptySlotsClickable(false);
        preset.setPlayerInventoryClickable(true);

        for (int slot = 0; slot < 54; slot++) {
            preset.addItem(slot, null);
        }

        ItemStack filler = SF4Items.create(Material.CYAN_STAINED_GLASS_PANE, " ");
        for (int slot : FILLER_SLOTS) {
            preset.addItem(slot, filler, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    /**
     * Opens a freshly-scanned monitor for a player.
     *
     * @param menu
     *            The cargo manager's block menu
     * @param managerBlock
     *            The cargo manager block
     * @param player
     *            The player opening the monitor
     */
    public void open(@Nonnull BlockMenu menu, @Nonnull Block managerBlock, @Nonnull Player player) {
        Location key = key(managerBlock);
        states.remove(key);
        refreshMonitor(menu, managerBlock);
        openMonitors.put(player.getUniqueId(), key);
        menu.open(player);
    }

    /**
     * Clears all cached state for a cargo manager, normally after the block is broken.
     *
     * @param managerBlock
     *            The cargo manager block
     */
    public void clear(@Nonnull Block managerBlock) {
        Location key = key(managerBlock);
        states.remove(key);
        transactionLocks.remove(key);
        queuedRefreshes.remove(key);
        queuedLoadingRefreshes.remove(key);
        interactionInProgress.remove(key);
        openMonitors.entrySet().removeIf(entry -> entry.getValue().equals(key));
    }

    /**
     * Re-scans a cargo network and renders the monitor while preserving page/category state.
     *
     * @param menu
     *            The cargo manager menu to render into
     * @param managerBlock
     *            The cargo manager block
     */
    public void refreshMonitor(@Nonnull BlockMenu menu, @Nonnull Block managerBlock) {
        Location key = key(managerBlock);
        MonitorState state;
        CargoNet network;

        synchronized (lock(key)) {
            network = CargoNet.getNetworkFromLocation(managerBlock.getLocation());
            List<AggregatedEntry> entries = network == null ? List.of() : scanNetwork(network);
            state = MonitorState.create(entries, states.get(key));
            states.put(key, state);
        }

        updateStatusPanel(menu, network, state);
        renderGrid(menu, managerBlock);

        if (isLoading(network) && queuedLoadingRefreshes.add(key)) {
            Slimefun.runSync(() -> {
                queuedLoadingRefreshes.remove(key);
                refreshOpenMonitor(managerBlock);
            }, 40L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@Nonnull InventoryClickEvent event) {
        Location manager = openMonitors.get(event.getWhoClicked().getUniqueId());

        if (manager == null) {
            return;
        }

        ClickType click = event.getClick();
        boolean dangerousClick = click == ClickType.DOUBLE_CLICK
            || click == ClickType.NUMBER_KEY
            || click == ClickType.SWAP_OFFHAND
            || click == ClickType.SHIFT_LEFT
            || click == ClickType.SHIFT_RIGHT
            || click == ClickType.DROP
            || click == ClickType.CONTROL_DROP
            || click == ClickType.MIDDLE;

        if (dangerousClick) {
            event.setCancelled(true);
            scheduleRefresh(manager.getBlock());
        }
    }

    @EventHandler
    public void onInventoryClose(@Nonnull InventoryCloseEvent event) {
        openMonitors.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryDrag(@Nonnull InventoryDragEvent event) {
        Location manager = openMonitors.get(event.getWhoClicked().getUniqueId());

        if (manager == null) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesMonitor = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);

        if (touchesMonitor) {
            event.setCancelled(true);
            BlockMenu menu = BlockStorage.getInventory(manager);
            if (menu != null) {
                scheduleRefresh(menu.getBlock());
            }
        }
    }

    private @Nonnull List<AggregatedEntry> scanNetwork(@Nonnull CargoNet network) {
        Map<String, List<AggregatedEntry>> buckets = new LinkedHashMap<>();

        for (Block block : collectAttachedBlocks(network)) {
            DirtyChestMenu menu = CargoUtils.getChestMenu(block);
            StorageSnapshot customStorage = readCustomStorage(block, menu);

            if (customStorage != null) {
                add(buckets, customStorage.item(), customStorage.amount());
                continue;
            }

            if (menu != null) {
                for (int slot : getMonitorSlots(block, menu)) {
                    if (!isReadableMenuSlot(menu, slot)) {
                        continue;
                    }

                    add(buckets, menu.getItemInSlot(slot));
                }
            } else if (CargoUtils.hasInventory(block)) {
                BlockState state = block.getState();

                if (state instanceof InventoryHolder holder) {
                    Inventory inventory = holder.getInventory();

                    for (int slot = 0; slot < inventory.getSize(); slot++) {
                        add(buckets, inventory.getItem(slot));
                    }
                }
            }
        }

        return buckets.values().stream()
            .flatMap(List::stream)
            .toList();
    }

    private void updateStatusPanel(@Nonnull BlockMenu menu, @Nullable CargoNet network, @Nonnull MonitorState state) {
        boolean online = network != null && (!network.getInputNodes().isEmpty() || !network.getOutputNodes().isEmpty());
        boolean loading = isLoading(network);
        int inputs = network == null ? 0 : network.getInputNodes().size();
        int advancedOutputs = network == null ? 0 : countAdvancedOutputs(network);
        ItemCategory category = state.currentCategory();

        menu.replaceExistingItem(STATUS_SLOT, SF4Items.create(
            loading ? Material.YELLOW_STAINED_GLASS_PANE : online ? Material.GREEN_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
            loading ? "&eStatus: &6LOADING..." : online ? "&aStatus: &2ONLINE" : "&cStatus: &4OFFLINE",
            loading ? "&7Network is still being discovered." : online ? "&7CargoNet is connected and visible." : "&7No cargo network data is currently available.",
            loading ? "&7Counts may be incomplete. Refreshing..." : ""
        ));
        menu.replaceExistingItem(INPUT_SLOT, SF4Items.create(
            Material.LIME_STAINED_GLASS_PANE,
            "&a⬇ Quick Deposit",
            "&7Deposit items from your inventory",
            "&7into the cargo network.",
            "",
            "&eLeft click: &7deposit matching items",
            "&eRight click: &7deposit ALL items",
            "",
            "&7Inputs connected: &f" + inputs
        ));
        menu.replaceExistingItem(OUTPUT_SLOT, SF4Items.create(
            Material.COMPARATOR,
            "&6Advanced Outputs Connected: &f" + advancedOutputs,
            "&7Advanced Cargo Output Nodes on this network"
        ));

        // Net throughput formatting
        double net = state.netThroughput();
        String throughputLine;
        if (net > 0.01) {
            throughputLine = "&a▲ Net: +" + formatThroughput(net) + "/tick";
        } else if (net < -0.01) {
            throughputLine = "&c▼ Net: " + formatThroughput(net) + "/tick";
        } else {
            throughputLine = "&7— Net: 0/tick (balanced)";
        }

        menu.replaceExistingItem(SUMMARY_SLOT, SF4Items.create(
            Material.CHEST,
            "&eNetwork Storage Snapshot",
            "&7Total: &f" + String.format(Locale.ROOT, "%,d", state.totalItems()) + " &7items",
            "&7Types: &f" + state.uniqueTypes(),
            "&7Category: &f" + category.displayName(),
            "&7Category total: &f" + String.format(Locale.ROOT, "%,d", state.totalInCategory(category)),
            "",
            throughputLine
        ));

        menu.addMenuClickHandler(STATUS_SLOT, ChestMenuUtils.getEmptyClickHandler());
        menu.addMenuClickHandler(INPUT_SLOT, (player, slot, item, action) -> {
            depositPlayerInventory(player, menu.getBlock(), action.isRightClicked());
            scheduleRefresh(menu.getBlock());
            return false;
        });
        menu.addMenuClickHandler(OUTPUT_SLOT, ChestMenuUtils.getEmptyClickHandler());
        menu.addMenuClickHandler(SUMMARY_SLOT, ChestMenuUtils.getEmptyClickHandler());
    }

    private void renderGrid(@Nonnull BlockMenu menu, @Nonnull Block managerBlock) {
        Location key = key(managerBlock);
        MonitorState state = states.getOrDefault(key, MonitorState.empty());
        List<AggregatedEntry> entries = state.currentPageEntries();

        for (int i = 0; i < GRID_SLOTS.length; i++) {
            int slot = GRID_SLOTS[i];
            AggregatedEntry entry = i < entries.size() ? entries.get(i) : null;

            menu.replaceExistingItem(slot, entry == null ? null : createDisplayItem(entry));
            menu.addMenuClickHandler(slot, new GridClickHandler(managerBlock, i));
        }

        updatePagination(menu, managerBlock, state);
    }

    private void updatePagination(@Nonnull BlockMenu menu, @Nonnull Block managerBlock, @Nonnull MonitorState state) {
        boolean hasPrevious = state.categoryIndex() > 0;
        boolean hasNext = state.categoryIndex() + 1 < state.activeCategoryCount();
        boolean hasInnerPrevious = state.innerPage() > 0;
        boolean hasInnerNext = state.innerPage() + 1 < state.innerPageCount();
        ItemCategory category = state.currentCategory();

        menu.replaceExistingItem(PREVIOUS_SLOT, hasPrevious
            ? SF4Items.create(HeadTexture.CARGO_ARROW_LEFT.getAsItemStack(), "&ePrevious Category")
            : SF4Items.create(Material.CYAN_STAINED_GLASS_PANE, " "));
        menu.replaceExistingItem(PAGE_SLOT, SF4Items.create(
            Material.BOOK,
            "&b" + category.displayName(),
            "&7Category &f" + (state.categoryIndex() + 1) + " &7/ &f" + state.activeCategoryCount(),
            "&7Inner page &f" + (state.innerPage() + 1) + " &7/ &f" + state.innerPageCount(),
            "&7Visible: &f" + state.currentPageEntries().size() + " &7type(s)",
            "&7Category total: &f" + String.format(Locale.ROOT, "%,d", state.totalInCategory(category)),
            "&7Search: &f" + (state.hasSearch() ? state.searchQuery() : "None"),
            "",
            "&eLeft click item: &71",
            "&eRight click item: &7stack"
        ));
        menu.replaceExistingItem(NEXT_SLOT, hasNext
            ? SF4Items.create(HeadTexture.CARGO_ARROW_RIGHT.getAsItemStack(), "&eNext Category")
            : SF4Items.create(Material.CYAN_STAINED_GLASS_PANE, " "));
        menu.replaceExistingItem(INNER_PREVIOUS_SLOT, hasInnerPrevious
            ? SF4Items.create(HeadTexture.CARGO_ARROW_LEFT.getAsItemStack(), "&7Previous Items")
            : SF4Items.create(Material.CYAN_STAINED_GLASS_PANE, " "));
        menu.replaceExistingItem(INNER_NEXT_SLOT, hasInnerNext
            ? SF4Items.create(HeadTexture.CARGO_ARROW_RIGHT.getAsItemStack(), "&7Next Items")
            : SF4Items.create(Material.CYAN_STAINED_GLASS_PANE, " "));
        menu.replaceExistingItem(SEARCH_SLOT, state.hasSearch()
            ? SF4Items.create(
                Material.NAME_TAG,
                "&aSearch: &f" + state.searchQuery(),
                "&7Left click: change search",
                "&7Right click: clear search"
            )
            : SF4Items.create(
                Material.NAME_TAG,
                "&aSearch Items",
                "&7Left click: type a search in chat",
                "&7Right click: clear search"
            ));

        menu.addMenuClickHandler(PREVIOUS_SLOT, (player, slot, item, action) -> {
            if (hasPrevious) {
                states.getOrDefault(key(managerBlock), MonitorState.empty()).previousCategory();
                renderAndStatus(menu, managerBlock);
            }

            return false;
        });
        menu.addMenuClickHandler(PAGE_SLOT, ChestMenuUtils.getEmptyClickHandler());
        menu.addMenuClickHandler(NEXT_SLOT, (player, slot, item, action) -> {
            if (hasNext) {
                states.getOrDefault(key(managerBlock), MonitorState.empty()).nextCategory();
                renderAndStatus(menu, managerBlock);
            }

            return false;
        });
        menu.addMenuClickHandler(INNER_PREVIOUS_SLOT, (player, slot, item, action) -> {
            if (hasInnerPrevious) {
                states.getOrDefault(key(managerBlock), MonitorState.empty()).previousInnerPage();
                renderAndStatus(menu, managerBlock);
            }

            return false;
        });
        menu.addMenuClickHandler(INNER_NEXT_SLOT, (player, slot, item, action) -> {
            if (hasInnerNext) {
                states.getOrDefault(key(managerBlock), MonitorState.empty()).nextInnerPage();
                renderAndStatus(menu, managerBlock);
            }

            return false;
        });
        menu.addMenuClickHandler(SEARCH_SLOT, (player, slot, item, action) -> {
            if (action.isRightClicked()) {
                setSearchQuery(key(managerBlock), "");
                refreshMonitor(menu, managerBlock);
                return false;
            }

            player.closeInventory();
            player.sendMessage(SF4Colors.color("&aType your Cargo Grid search in chat. Type &eclear &ato reset."));
            SF4ChatInput.waitForPlayer(Slimefun.instance(), player, input -> {
                String query = normalizeSearchInput(input);
                Slimefun.runSync(() -> {
                    Location key = key(managerBlock);
                    BlockMenu latestMenu = BlockStorage.getInventory(managerBlock);

                    if (latestMenu != null) {
                        setSearchQuery(key, query);
                        openMonitors.put(player.getUniqueId(), key);
                        refreshMonitor(latestMenu, managerBlock);
                        latestMenu.open(player);
                    }
                });
            });

            return false;
        });
    }

    private boolean handleGridClick(@Nonnull InventoryClickEvent event, @Nonnull Player player, @Nonnull Block managerBlock, int gridIndex, @Nullable ItemStack cursor, @Nonnull ClickAction action) {
        event.setCancelled(true);

        if (!isSafeGridClick(event.getClick()) || action.isShiftClicked()) {
            scheduleRefresh(managerBlock);
            return false;
        }

        Location key = key(managerBlock);
        interactionInProgress.add(key);

        try {
            synchronized (lock(key)) {
                List<AggregatedEntry> entries = states.getOrDefault(key, MonitorState.empty()).currentPageEntries();
                AggregatedEntry entry = gridIndex < entries.size() ? entries.get(gridIndex) : null;

                if (entry == null) {
                    depositCursor(event, player, managerBlock, cursor);
                } else {
                    extractToPlayer(player, managerBlock, entry.representative(), action.isRightClicked() ? entry.representative().getMaxStackSize() : 1);
                }
            }
        } finally {
            interactionInProgress.remove(key);
            scheduleRefresh(managerBlock);
        }

        return false;
    }

    private void extractToPlayer(@Nonnull Player player, @Nonnull Block managerBlock, @Nonnull ItemStack template, int requestedAmount) {
        CargoNet network = CargoNet.getNetworkFromLocation(managerBlock.getLocation());
        if (network == null) {
            return;
        }

        ExtractionPlan plan = planExtraction(network, template, requestedAmount);
        if (plan.available() < requestedAmount) {
            player.sendMessage(SF4Colors.color("&cNot enough items are available in the network."));
            return;
        }

        if (!canFit(player, template, requestedAmount)) {
            player.sendMessage(SF4Colors.color("&cYour inventory is full!"));
            return;
        }

        if (plan.plannedAmount() < requestedAmount || !validateTransactions(plan.transactions(), template)) {
            player.sendMessage(SF4Colors.color("&cNetwork storage changed, extraction cancelled."));
            return;
        }

        applyTransactions(plan.transactions());

        ItemStack extracted = template.clone();
        extracted.setAmount(requestedAmount);
        player.getInventory().addItem(extracted);
        scheduleRefresh(managerBlock);
    }

    private @Nonnull ExtractionPlan planExtraction(@Nonnull CargoNet network, @Nonnull ItemStack template, int requestedAmount) {
        List<SlotTransaction> transactions = new ArrayList<>();
        long available = 0L;
        int remaining = requestedAmount;

        for (Block block : collectAttachedBlocks(network)) {
            DirtyChestMenu menu = CargoUtils.getChestMenu(block);

            if (menu != null) {
                for (int slot : getMonitorSlots(block, menu)) {
                    ItemStack item = menu.getItemInSlot(slot);
                    if (!isSimilar(item, template)) {
                        continue;
                    }

                    available += item.getAmount();
                    if (remaining > 0) {
                        int taken = Math.min(remaining, item.getAmount());
                        transactions.add(SlotTransaction.menu(menu, slot, taken));
                        remaining -= taken;
                    }
                }
            } else if (CargoUtils.hasInventory(block)) {
                BlockState state = block.getState();

                if (state instanceof InventoryHolder holder) {
                    Inventory inventory = holder.getInventory();

                    for (int slot = 0; slot < inventory.getSize(); slot++) {
                        ItemStack item = inventory.getItem(slot);
                        if (!isSimilar(item, template)) {
                            continue;
                        }

                        available += item.getAmount();
                        if (remaining > 0) {
                            int taken = Math.min(remaining, item.getAmount());
                            transactions.add(SlotTransaction.inventory(inventory, slot, taken));
                            remaining -= taken;
                        }
                    }
                }
            }
        }

        return new ExtractionPlan(available, requestedAmount - remaining, transactions);
    }

    private boolean validateTransactions(@Nonnull List<SlotTransaction> transactions, @Nonnull ItemStack template) {
        for (SlotTransaction transaction : transactions) {
            ItemStack item = transaction.currentItem();
            if (!isSimilar(item, template) || item.getAmount() < transaction.amount()) {
                return false;
            }
        }

        return true;
    }

    private void applyTransactions(@Nonnull List<SlotTransaction> transactions) {
        for (SlotTransaction transaction : transactions) {
            ItemStack item = transaction.currentItem();
            ItemStack updated = item.clone();
            updated.setAmount(item.getAmount() - transaction.amount());
            transaction.setItem(updated.getAmount() > 0 ? updated : null);
        }
    }

    private void depositCursor(@Nonnull InventoryClickEvent event, @Nonnull Player player, @Nonnull Block managerBlock, @Nullable ItemStack cursor) {
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }

        CargoNet network = CargoNet.getNetworkFromLocation(managerBlock.getLocation());
        if (network == null) {
            return;
        }

        ItemStack toDeposit = cursor.clone();
        int originalAmount = toDeposit.getAmount();
        ItemStack remaining = insertIntoNetwork(network, toDeposit);
        int remainingAmount = remaining == null ? 0 : remaining.getAmount();
        int deposited = originalAmount - remainingAmount;

        if (deposited <= 0) {
            player.sendMessage(SF4Colors.color("\u0026cNo attached inventory has space for that item."));
            return;
        }

        event.setCursor(remainingAmount > 0 ? remaining : null);
        player.sendMessage(SF4Colors.color("\u0026aDeposited \u0026f" + deposited + "x " + SF4ItemUtils.getItemName(toDeposit) + " \u0026ainto the network."));
        scheduleRefresh(managerBlock);
    }

    /**
     * Deposits items from a player's inventory into the cargo network.
     *
     * @param player       The player whose inventory to deposit from
     * @param managerBlock The cargo manager block
     * @param depositAll   If true, deposit ALL items. If false, only deposit items that already exist in the network.
     */
    private void depositPlayerInventory(@Nonnull Player player, @Nonnull Block managerBlock, boolean depositAll) {
        CargoNet network = CargoNet.getNetworkFromLocation(managerBlock.getLocation());
        if (network == null) {
            player.sendMessage(SF4Colors.color("\u0026cNo cargo network found."));
            return;
        }

        Location key = key(managerBlock);
        interactionInProgress.add(key);

        try {
            synchronized (lock(key)) {
                // Build a set of item keys already in the network for matching mode
                Set<String> networkItemKeys = new HashSet<>();
                if (!depositAll) {
                    MonitorState state = states.get(key);
                    if (state != null) {
                        for (List<AggregatedEntry> entries : state.grouped.values()) {
                            for (AggregatedEntry entry : entries) {
                                networkItemKeys.add(bucketKey(entry.representative()));
                            }
                        }
                    }
                }

                int totalDeposited = 0;
                int typesDeposited = 0;
                ItemStack[] contents = player.getInventory().getStorageContents();

                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item == null || item.getType().isAir()) {
                        continue;
                    }

                    // In matching mode, skip items not already in the network
                    if (!depositAll && !networkItemKeys.contains(bucketKey(item))) {
                        continue;
                    }

                    ItemStack toDeposit = item.clone();
                    int originalAmount = toDeposit.getAmount();
                    ItemStack remaining = insertIntoNetwork(network, toDeposit);
                    int remainingAmount = remaining == null ? 0 : remaining.getAmount();
                    int deposited = originalAmount - remainingAmount;

                    if (deposited > 0) {
                        totalDeposited += deposited;
                        typesDeposited++;

                        if (remainingAmount > 0) {
                            player.getInventory().setItem(i, remaining);
                        } else {
                            player.getInventory().setItem(i, null);
                        }
                    }
                }

                if (totalDeposited > 0) {
                    player.sendMessage(SF4Colors.color("\u0026a\u2B07 Deposited \u0026f" + totalDeposited + " \u0026aitems (\u0026f" + typesDeposited + " \u0026atypes) into the network."));
                } else {
                    player.sendMessage(SF4Colors.color("\u0026cNo items could be deposited. Network storage may be full."));
                }
            }
        } finally {
            interactionInProgress.remove(key);
        }
    }

    private @Nullable ItemStack insertIntoNetwork(@Nonnull CargoNet network, @Nonnull ItemStack stack) {
        ItemStack remaining = stack.clone();
        Map<Location, Inventory> inventories = new HashMap<>();

        // First pass: smart fill (only fill existing stacks)
        for (Location outputLoc : network.getOutputNodes()) {
            if (isEmpty(remaining)) {
                return null;
            }

            Optional<Block> targetBlock = network.getAttachedBlock(outputLoc);
            if (targetBlock.isPresent()) {
                ItemStackWrapper wrapper = ItemStackWrapper.wrap(remaining);
                remaining = CargoUtils.insert(network, inventories, outputLoc.getBlock(), targetBlock.get(), true, remaining, wrapper);
            }
        }

        // Second pass: normal fill (use empty slots)
        for (Location outputLoc : network.getOutputNodes()) {
            if (isEmpty(remaining)) {
                return null;
            }

            Optional<Block> targetBlock = network.getAttachedBlock(outputLoc);
            if (targetBlock.isPresent()) {
                ItemStackWrapper wrapper = ItemStackWrapper.wrap(remaining);
                remaining = CargoUtils.insert(network, inventories, outputLoc.getBlock(), targetBlock.get(), false, remaining, wrapper);
            }
        }

        return remaining;
    }

    private void renderAndStatus(@Nonnull BlockMenu menu, @Nonnull Block managerBlock) {
        MonitorState state = states.getOrDefault(key(managerBlock), MonitorState.empty());
        updateStatusPanel(menu, CargoNet.getNetworkFromLocation(managerBlock.getLocation()), state);
        renderGrid(menu, managerBlock);
    }

    private void setSearchQuery(@Nonnull Location key, @Nonnull String searchQuery) {
        states.compute(key, (ignored, state) -> {
            MonitorState currentState = state == null ? MonitorState.empty() : state;
            currentState.setSearchQuery(searchQuery);
            return currentState;
        });
    }

    private void refreshOpenMonitor(@Nonnull Block managerBlock) {
        BlockMenu managerMenu = BlockStorage.getInventory(managerBlock);
        if (managerMenu != null) {
            refreshMonitor(managerMenu, managerBlock);
        }
    }

    private void refreshOpenMonitors() {
        Set<Location> locations = new HashSet<>(openMonitors.values());

        for (Location location : locations) {
            if (interactionInProgress.contains(location)) {
                continue;
            }

            BlockMenu menu = BlockStorage.getInventory(location);

            if (menu == null || !menu.hasViewer()) {
                openMonitors.entrySet().removeIf(entry -> entry.getValue().equals(location));
                continue;
            }

            refreshMonitor(menu, location.getBlock());
        }
    }

    private void scheduleRefresh(@Nonnull Block managerBlock) {
        Location key = key(managerBlock);

        if (!queuedRefreshes.add(key)) {
            return;
        }

        Slimefun.runSync(() -> {
            queuedRefreshes.remove(key);

            if (!interactionInProgress.contains(key)) {
                refreshOpenMonitor(managerBlock);
            }
        }, 1L);
    }

    private boolean isSafeGridClick(@Nonnull ClickType clickType) {
        return clickType == ClickType.LEFT || clickType == ClickType.RIGHT;
    }

    private @Nonnull Object lock(@Nonnull Location key) {
        return transactionLocks.computeIfAbsent(key, ignored -> new Object());
    }

    private @Nonnull Set<Block> collectAttachedBlocks(@Nonnull CargoNet network) {
        Set<Block> blocks = new HashSet<>();

        for (Location location : network.getInputNodes()) {
            network.getAttachedBlock(location).ifPresent(blocks::add);
        }

        for (Location location : network.getOutputNodes()) {
            network.getAttachedBlock(location).ifPresent(blocks::add);
        }

        return blocks;
    }

    private boolean isLoading(@Nullable CargoNet network) {
        if (network == null) {
            return false;
        }

        for (Location location : network.getInputNodes()) {
            if (network.getAttachedBlock(location).isEmpty()) {
                return true;
            }
        }

        for (Location location : network.getOutputNodes()) {
            if (network.getAttachedBlock(location).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private int countAdvancedOutputs(@Nonnull CargoNet network) {
        int count = 0;

        for (Location location : network.getOutputNodes()) {
            if ("CARGO_NODE_OUTPUT_ADVANCED".equals(BlockStorage.checkID(location))) {
                count++;
            }
        }

        return count;
    }

    private void add(@Nonnull Map<String, List<AggregatedEntry>> buckets, @Nullable ItemStack item) {
        add(buckets, item, item == null ? 0L : item.getAmount());
    }

    private void add(@Nonnull Map<String, List<AggregatedEntry>> buckets, @Nullable ItemStack item, long amount) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        if (amount <= 0L) {
            return;
        }

        List<AggregatedEntry> entries = buckets.computeIfAbsent(bucketKey(item), ignored -> new ArrayList<>());
        for (AggregatedEntry entry : entries) {
            if (isSimilar(entry.representative(), item)) {
                entry.add(amount);
                return;
            }
        }

        entries.add(new AggregatedEntry(item.clone(), amount));
    }

    private @Nonnull String bucketKey(@Nonnull ItemStack item) {
        SlimefunItem slimefunItem = SlimefunItem.getByItem(item);
        return slimefunItem != null ? "sf:" + slimefunItem.getId() : "mc:" + item.getType().name();
    }

    private @Nullable StorageSnapshot readCustomStorage(@Nonnull Block block, @Nullable DirtyChestMenu menu) {
        SlimefunItem slimefunItem = BlockStorage.check(block);

        if (slimefunItem == null) {
            return null;
        }

        OptionalLong reflectedAmount = invokeLongStorageMethod(slimefunItem, block, menu,
            "getStored",
            "getStoredAmount",
            "getStoredItems",
            "getStoredCount",
            "getAmount",
            "getItemAmount"
        );
        OptionalLong blockStorageAmount = readBlockStorageAmount(block);
        long amount = reflectedAmount.orElseGet(() -> blockStorageAmount.orElse(0L));

        ItemStack storedItem = invokeItemStorageMethod(slimefunItem, block, menu,
            "getStoredItem",
            "getStoredItemStack",
            "getStoredStack",
            "getItem",
            "getStoredDisplayItem"
        );

        boolean handled = reflectedAmount.isPresent() || blockStorageAmount.isPresent() || storedItem != null;
        if (!handled) {
            return null;
        }

        return new StorageSnapshot(storedItem, amount);
    }

    private @Nonnull OptionalLong invokeLongStorageMethod(
        @Nonnull Object target,
        @Nonnull Block block,
        @Nullable DirtyChestMenu menu,
        @Nonnull String... names
    ) {
        for (String name : names) {
            Object value = invokeStorageMethod(target, name, block, menu);
            Long parsed = toLong(value);

            if (parsed != null) {
                return OptionalLong.of(parsed);
            }
        }

        return OptionalLong.empty();
    }

    private @Nullable ItemStack invokeItemStorageMethod(
        @Nonnull Object target,
        @Nonnull Block block,
        @Nullable DirtyChestMenu menu,
        @Nonnull String... names
    ) {
        for (String name : names) {
            Object value = invokeStorageMethod(target, name, block, menu);

            if (value instanceof ItemStack item && !item.getType().isAir()) {
                return item.clone();
            }
        }

        return null;
    }

    private @Nullable Object invokeStorageMethod(@Nonnull Object target, @Nonnull String name, @Nonnull Block block, @Nullable DirtyChestMenu menu) {
        Class<?> type = target.getClass();

        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }

                Object[] arguments = storageArguments(method, block, menu);
                if (arguments == null) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    return method.invoke(target, arguments);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private @Nullable Object[] storageArguments(@Nonnull Method method, @Nonnull Block block, @Nullable DirtyChestMenu menu) {
        Class<?>[] parameterTypes = method.getParameterTypes();

        if (parameterTypes.length == 0) {
            return new Object[0];
        }

        if (parameterTypes.length != 1) {
            return null;
        }

        Class<?> parameter = parameterTypes[0];
        if (parameter.isAssignableFrom(Block.class)) {
            return new Object[] { block };
        }

        if (parameter.isAssignableFrom(Location.class)) {
            return new Object[] { block.getLocation() };
        }

        if (menu != null && parameter.isAssignableFrom(menu.getClass())) {
            return new Object[] { menu };
        }

        return null;
    }

    private @Nonnull OptionalLong readBlockStorageAmount(@Nonnull Block block) {
        String[] keys = {
            "stored",
            "stored-amount",
            "stored_amount",
            "stored-items",
            "stored_items",
            "stored-count",
            "stored_count",
            "amount",
            "item-amount",
            "item_amount",
            "item-count",
            "item_count",
            "count"
        };

        for (String key : keys) {
            Long parsed = toLong(BlockStorage.getLocationInfo(block.getLocation(), key));

            if (parsed != null) {
                return OptionalLong.of(parsed);
            }
        }

        return OptionalLong.empty();
    }

    private @Nullable Long toLong(@Nullable Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }

        if (value instanceof String string) {
            String normalized = string.trim().replace(",", "");
            if (!normalized.isBlank()) {
                try {
                    return Math.max(0L, Long.parseLong(normalized));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }

        return null;
    }

    private @Nonnull ItemStack createDisplayItem(@Nonnull AggregatedEntry entry) {
        ItemStack item = entry.representative().clone();
        item.setAmount(1);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            long total = entry.totalAmount();
            long fullStacks = total / 64L;
            long remainder = total % 64L;

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Stored across network inventories");

            if (fullStacks > 0L && remainder > 0L) {
                lore.add(ChatColor.WHITE
                    + formatAmount(total)
                    + ChatColor.GRAY
                    + " items ("
                    + fullStacks
                    + " stacks + "
                    + remainder
                    + ")");
            } else if (fullStacks > 0L) {
                lore.add(ChatColor.WHITE
                    + formatAmount(total)
                    + ChatColor.GRAY
                    + " items ("
                    + fullStacks
                    + " full stacks)");
            } else {
                lore.add(ChatColor.WHITE
                    + formatAmount(total)
                    + ChatColor.GRAY
                    + " items");
            }

            lore.add(ChatColor.GRAY
                + "Across "
                + ChatColor.WHITE
                + entry.sourceSlots()
                + ChatColor.GRAY
                + " storage slot(s)");

            // Throughput per tick
            double throughput = entry.throughputPerTick();
            if (throughput > 0.01) {
                lore.add(ChatColor.GREEN + "\u25B2 +" + formatThroughput(throughput) + "/tick " + ChatColor.GRAY + "(gaining)");
            } else if (throughput < -0.01) {
                lore.add(ChatColor.RED + "\u25BC " + formatThroughput(throughput) + "/tick " + ChatColor.GRAY + "(consuming)");
            } else {
                lore.add(ChatColor.GRAY + "\u2014 0/tick (stable)");
            }

            lore.add("");
            lore.add(ChatColor.GREEN + "Left click: " + ChatColor.GRAY + "withdraw 1");
            lore.add(ChatColor.GREEN + "Right click: " + ChatColor.GRAY + "withdraw stack");

            meta.setDisplayName(displayName(entry.representative(), meta));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        return item;
    }

    private @Nonnull String displayName(@Nonnull ItemStack item, @Nonnull ItemMeta meta) {
        if (meta.hasDisplayName()) {
            return meta.getDisplayName();
        }

        return ChatColor.WHITE + WordUtils.capitalize(item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private @Nonnull int[] getMonitorSlots(@Nonnull Block block, @Nonnull DirtyChestMenu menu) {
        int[] withdrawSlots = menu.getPreset().getSlotsAccessedByItemTransport(menu, ItemTransportFlow.WITHDRAW, null);

        if (isMachineBlock(block)) {
            return withdrawSlots.length > 0 ? sortedUnique(withdrawSlots) : new int[0];
        }

        if (withdrawSlots.length > 0) {
            return sortedUnique(withdrawSlots);
        }

        int[] inventorySlots = menu.getPreset().getInventorySlots().stream()
            .mapToInt(Integer::intValue)
            .sorted()
            .toArray();

        if (inventorySlots.length > 0) {
            return inventorySlots;
        }

        return sortedUnique(withdrawSlots);
    }

    private boolean isReadableMenuSlot(@Nonnull DirtyChestMenu menu, int slot) {
        return menu.getMenuClickHandler(slot) == null;
    }

    private @Nonnull int[] sortedUnique(@Nonnull int[] slots) {
        Set<Integer> uniqueSlots = new HashSet<>();
        for (int slot : slots) {
            uniqueSlots.add(slot);
        }

        return uniqueSlots.stream()
            .mapToInt(Integer::intValue)
            .sorted()
            .toArray();
    }

    private boolean isMachineBlock(@Nonnull Block block) {
        String id = BlockStorage.checkID(block);
        if (id == null) {
            return false;
        }

        String upper = id.toUpperCase(Locale.ROOT);
        return upper.contains("MACHINE")
            || upper.contains("FURNACE")
            || upper.contains("SMELTER")
            || upper.contains("SMELTERY")
            || upper.contains("CRUSHER")
            || upper.contains("GRINDER")
            || upper.contains("COMPRESSOR")
            || upper.contains("PRESS")
            || upper.contains("REACTOR")
            || upper.contains("GENERATOR")
            || upper.contains("ASSEMBLER")
            || upper.contains("AUTO_")
            || upper.endsWith("_MILL")
            || upper.endsWith("_FACTORY");
    }

    private boolean canFit(@Nonnull Player player, @Nonnull ItemStack template, int amount) {
        int remaining = amount;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isSimilar(item, template)) {
                int space = Math.max(0, Math.min(item.getMaxStackSize(), player.getInventory().getMaxStackSize()) - item.getAmount());
                remaining -= Math.min(space, remaining);

                if (remaining <= 0) {
                    return true;
                }
            }
        }

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                remaining -= Math.min(template.getMaxStackSize(), remaining);

                if (remaining <= 0) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isSimilar(@Nullable ItemStack item, @Nonnull ItemStack template) {
        return item != null && !item.getType().isAir() && SlimefunUtils.isItemSimilar(item, template, true, false);
    }

    private boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getAmount() <= 0 || item.getType().isAir();
    }

    private static @Nonnull String sortName(@Nonnull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String stripped = ChatColor.stripColor(meta.getDisplayName());
            if (stripped != null && !stripped.isBlank()) {
                return stripped;
            }
        }

        return WordUtils.capitalize(item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static @Nonnull String normalizeSearchInput(@Nullable String input) {
        if (input == null) {
            return "";
        }

        String stripped = ChatColor.stripColor(input.trim());
        if (stripped == null || stripped.isBlank()
            || stripped.equalsIgnoreCase("clear")
            || stripped.equalsIgnoreCase("reset")
            || stripped.equalsIgnoreCase("none")) {
            return "";
        }

        return stripped.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesSearch(@Nonnull AggregatedEntry entry, @Nonnull String query) {
        if (query.isBlank()) {
            return true;
        }

        ItemStack representative = entry.representative();
        SlimefunItem slimefunItem = SlimefunItem.getByItem(representative);
        String sfId = slimefunItem == null ? "" : slimefunItem.getId().toLowerCase(Locale.ROOT);
        String displayName = sortName(representative).toLowerCase(Locale.ROOT);
        String materialName = representative.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String materialId = representative.getType().name().toLowerCase(Locale.ROOT);

        return displayName.contains(query)
            || materialName.contains(query)
            || materialId.contains(query)
            || sfId.contains(query);
    }

    private static @Nonnull String formatAmount(long amount) {
        if (amount >= 1_000_000L) {
            return formatCompact(amount, 1_000_000L, "m");
        }

        if (amount >= 1_000L) {
            return formatCompact(amount, 1_000L, "k");
        }

        return String.format(Locale.ROOT, "%,d", amount);
    }

    private static @Nonnull String formatCompact(long amount, long unit, @Nonnull String suffix) {
        long tenths = Math.round(amount * 10.0D / unit);
        long whole = tenths / 10L;
        long decimal = tenths % 10L;

        if (decimal == 0L) {
            return whole + suffix;
        }

        return whole + "." + decimal + suffix;
    }

    private static @Nonnull String formatThroughput(double throughput) {
        double abs = Math.abs(throughput);
        if (abs >= 1.0) {
            return String.format(Locale.ROOT, "%.1f", throughput);
        }
        return String.format(Locale.ROOT, "%.2f", throughput);
    }

    private @Nonnull Location key(@Nonnull Block block) {
        return block.getLocation().clone();
    }

    @FunctionalInterface
    private interface CategoryMatcher {

        boolean matches(@Nonnull ItemStack stack, @Nullable String sfId, @Nonnull Material material);
    }

    private record ItemCategory(@Nonnull String displayName, @Nonnull CategoryMatcher matcher) {}

    private record ExtractionPlan(long available, int plannedAmount, @Nonnull List<SlotTransaction> transactions) {}

    private record StorageSnapshot(@Nullable ItemStack item, long amount) {}

    private static final class MonitorState {

        private final Map<ItemCategory, List<AggregatedEntry>> grouped;
        private final List<ItemCategory> activeCategories;
        private final Map<String, Long> previousAmounts;
        private String searchQuery;
        private int categoryIndex;
        private int innerPage;
        private double netThroughput;

        private MonitorState(@Nonnull Map<ItemCategory, List<AggregatedEntry>> grouped, @Nonnull List<ItemCategory> activeCategories, @Nonnull String searchQuery, int categoryIndex, int innerPage, @Nonnull Map<String, Long> previousAmounts, double netThroughput) {
            this.grouped = grouped;
            this.activeCategories = activeCategories;
            this.searchQuery = searchQuery;
            this.categoryIndex = Math.max(0, Math.min(categoryIndex, activeCategories.size() - 1));
            this.innerPage = Math.max(0, Math.min(innerPage, innerPageCount() - 1));
            this.previousAmounts = previousAmounts;
            this.netThroughput = netThroughput;
        }

        private static @Nonnull MonitorState create(@Nonnull List<AggregatedEntry> entries, @Nullable MonitorState previous) {
            Map<ItemCategory, List<AggregatedEntry>> grouped = new LinkedHashMap<>();
            String searchQuery = previous == null ? "" : previous.searchQuery();
            Map<String, Long> oldAmounts = previous == null ? Map.of() : previous.snapshotAmounts();
            Map<String, Long> newAmounts = new LinkedHashMap<>();
            double totalNet = 0.0;

            for (AggregatedEntry entry : entries) {
                String key = entryKey(entry);
                newAmounts.merge(key, entry.totalAmount(), Long::sum);

                // Calculate throughput delta
                Long oldAmount = oldAmounts.get(key);
                if (oldAmount != null) {
                    double delta = (entry.totalAmount() - oldAmount) / (double) LIVE_REFRESH_INTERVAL_TICKS;
                    entry.setThroughputPerTick(delta);
                    totalNet += delta;
                }

                if (!matchesSearch(entry, searchQuery)) {
                    continue;
                }

                ItemStack representative = entry.representative();
                SlimefunItem slimefunItem = SlimefunItem.getByItem(representative);
                String sfId = slimefunItem == null ? null : slimefunItem.getId().toUpperCase(Locale.ROOT);
                Material material = representative.getType();
                ItemCategory category = CATEGORIES.stream()
                    .filter(itemCategory -> itemCategory.matcher().matches(representative, sfId, material))
                    .findFirst()
                    .orElse(MISC);
                grouped.computeIfAbsent(category, ignored -> new ArrayList<>()).add(entry);
            }

            for (List<AggregatedEntry> categoryEntries : grouped.values()) {
                categoryEntries.sort(Comparator
                    .comparing((AggregatedEntry entry) -> sortName(entry.representative()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.representative().getType().name())
                    .thenComparingLong(AggregatedEntry::totalAmount));
            }

            List<ItemCategory> activeCategories = CATEGORIES.stream()
                .filter(grouped::containsKey)
                .toList();

            if (activeCategories.isEmpty()) {
                grouped.put(MISC, List.of());
                activeCategories = List.of(MISC);
            }

            return new MonitorState(grouped, activeCategories, searchQuery, previous == null ? 0 : previous.categoryIndex(), previous == null ? 0 : previous.innerPage(), newAmounts, totalNet);
        }

        private static @Nonnull MonitorState empty() {
            return new MonitorState(Map.of(MISC, List.of()), List.of(MISC), "", 0, 0, Map.of(), 0.0);
        }

        private static @Nonnull String entryKey(@Nonnull AggregatedEntry entry) {
            SlimefunItem sfItem = SlimefunItem.getByItem(entry.representative());
            return sfItem != null ? "sf:" + sfItem.getId() : "mc:" + entry.representative().getType().name();
        }

        private @Nonnull Map<String, Long> snapshotAmounts() {
            Map<String, Long> snapshot = new LinkedHashMap<>();
            for (List<AggregatedEntry> entries : grouped.values()) {
                for (AggregatedEntry entry : entries) {
                    String key = entryKey(entry);
                    snapshot.merge(key, entry.totalAmount(), Long::sum);
                }
            }
            // Merge with previousAmounts for items that disappeared
            for (Map.Entry<String, Long> prev : previousAmounts.entrySet()) {
                snapshot.putIfAbsent(prev.getKey(), prev.getValue());
            }
            return snapshot;
        }

        private double netThroughput() {
            return netThroughput;
        }

        private @Nonnull ItemCategory currentCategory() {
            return activeCategories.get(categoryIndex);
        }

        private @Nonnull List<AggregatedEntry> currentCategoryEntries() {
            return grouped.getOrDefault(currentCategory(), List.of());
        }

        private @Nonnull List<AggregatedEntry> currentPageEntries() {
            List<AggregatedEntry> entries = currentCategoryEntries();
            int start = Math.min(innerPage * PAGE_SIZE, entries.size());
            int end = Math.min(start + PAGE_SIZE, entries.size());
            return entries.subList(start, end);
        }

        private int activeCategoryCount() {
            return activeCategories.size();
        }

        private @Nonnull String searchQuery() {
            return searchQuery;
        }

        private boolean hasSearch() {
            return !searchQuery.isBlank();
        }

        private void setSearchQuery(@Nonnull String searchQuery) {
            this.searchQuery = searchQuery;
            categoryIndex = 0;
            innerPage = 0;
        }

        private int categoryIndex() {
            return categoryIndex;
        }

        private int innerPage() {
            return innerPage;
        }

        private int innerPageCount() {
            return Math.max(1, (int) Math.ceil(currentCategoryEntries().size() / (double) PAGE_SIZE));
        }

        private long totalInCategory(@Nonnull ItemCategory category) {
            return grouped.getOrDefault(category, List.of()).stream()
                .mapToLong(AggregatedEntry::totalAmount)
                .sum();
        }

        private long totalItems() {
            return grouped.values().stream()
                .flatMap(List::stream)
                .mapToLong(AggregatedEntry::totalAmount)
                .sum();
        }

        private int uniqueTypes() {
            return grouped.values().stream()
                .mapToInt(List::size)
                .sum();
        }

        private void previousCategory() {
            categoryIndex = Math.max(0, categoryIndex - 1);
            innerPage = 0;
        }

        private void nextCategory() {
            categoryIndex = Math.min(activeCategories.size() - 1, categoryIndex + 1);
            innerPage = 0;
        }

        private void previousInnerPage() {
            innerPage = Math.max(0, innerPage - 1);
        }

        private void nextInnerPage() {
            innerPage = Math.min(innerPageCount() - 1, innerPage + 1);
        }
    }

    private final class GridClickHandler implements ChestMenu.AdvancedMenuClickHandler {

        private final Block managerBlock;
        private final int gridIndex;

        private GridClickHandler(@Nonnull Block managerBlock, int gridIndex) {
            this.managerBlock = managerBlock;
            this.gridIndex = gridIndex;
        }

        @Override
        public boolean onClick(Player player, int slot, ItemStack item, ClickAction action) {
            return false;
        }

        @Override
        public boolean onClick(InventoryClickEvent event, Player player, int slot, ItemStack cursor, ClickAction action) {
            return handleGridClick(event, player, managerBlock, gridIndex, cursor, action);
        }
    }

    private record SlotTransaction(@Nullable DirtyChestMenu menu, @Nullable Inventory inventory, int slot, int amount) {

        private static @Nonnull SlotTransaction menu(@Nonnull DirtyChestMenu menu, int slot, int amount) {
            return new SlotTransaction(menu, null, slot, amount);
        }

        private static @Nonnull SlotTransaction inventory(@Nonnull Inventory inventory, int slot, int amount) {
            return new SlotTransaction(null, inventory, slot, amount);
        }

        private @Nullable ItemStack currentItem() {
            if (menu != null) {
                return menu.getItemInSlot(slot);
            }

            return inventory == null ? null : inventory.getItem(slot);
        }

        private void setItem(@Nullable ItemStack item) {
            if (menu != null) {
                menu.replaceExistingItem(slot, item);
            } else if (inventory != null) {
                inventory.setItem(slot, item);
            }
        }
    }

    private static final class AggregatedEntry {

        private final ItemStack representative;
        private long totalAmount;
        private long sourceSlots = 1L;
        private double throughputPerTick;

        private AggregatedEntry(@Nonnull ItemStack representative, long totalAmount) {
            this.representative = representative;
            this.totalAmount = totalAmount;
        }

        private @Nonnull ItemStack representative() {
            return representative;
        }

        private long totalAmount() {
            return totalAmount;
        }

        private long sourceSlots() {
            return sourceSlots;
        }

        private double throughputPerTick() {
            return throughputPerTick;
        }

        private void setThroughputPerTick(double throughput) {
            this.throughputPerTick = throughput;
        }

        private void add(long amount) {
            totalAmount += amount;
            sourceSlots++;
        }
    }
}
