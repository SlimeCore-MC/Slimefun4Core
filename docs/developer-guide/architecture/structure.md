# SlimefunCore — Codebase Architecture & Structure Guide

> **Target Version:** Paper 26.x / Minecraft 1.26.x  
> **Java Version:** Java 25  
> **Repository:** SlimefunCore (26.x branch)  
> **Last Updated:** September 2026

Welcome to the comprehensive developer architectural guide for **SlimefunCore**. This document breaks down the entire project structure, module responsibilities, core abstractions, execution lifecycles, and developer extension points.

---

## 1. High-Level Architecture Overview

SlimefunCore is a massive modular Minecraft technology plugin engineered around an extensible, object-oriented component model. It adds hundreds of custom items, multiblock structures, automated energy and cargo networks, tech trees, and a multi-backend storage engine.

```text
+-----------------------------------------------------------------------+
|                               API LAYER                               |
|                     (io.github...slimefun4.api)                       |
|           Items, Researches, GEO Resources, GPS, Custom Events        |
+-----------------------------------+-----------------------------------+
                                    |
                                    v
+-----------------------------------------------------------------------+
|                           CORE ENGINE LAYER                           |
|                    (io.github...slimefun4.core)                       |
|       Registries, Machine Processing, Energy/Cargo Networks, Services |
+------------------+---------------------------------+------------------+
                   |                                 |
                   v                                 v
+------------------------------------+  +-------------------------------+
|     STORAGE & PERSISTENCE LAYER    |  |     IMPLEMENTATION LAYER      |
|    (io.github...slimefun4.storage) |  | (io.github...slimefun4.impl)  |
| - Block/Machine Data: YAML/chunks  |  | - Concrete Items & Machines   |
| - Player Data: SQL & Flatfile      |  | - 40+ Bukkit Event Listeners  |
| - Schemas, Checkpoints & Migrations|  | - Ticker Tasks & Setup Engine |
+------------------+-----------------+  +---------------+---------------+
                   |                                    |
                   +-----------------+------------------+
                                     |
                                     v
+-----------------------------------------------------------------------+
|                        INTEGRATIONS & UTILITIES                       |
|         External Plugin Hooks (PAPI, WorldEdit), Bridge & Utils       |
+-----------------------------------------------------------------------+
```

### Key Design Tenets
1. **Strict Decoupling of Block Data vs. Player Data:**
   - **Block/Machine Data:** Stored in file-based YAML/binary chunks (`data-storage/Slimefun/`) across all backends.
   - **Player Data (Researches, Backpacks, Waypoints):** Handled via an interchangeable storage layer supporting Legacy YAML, SQLite, MySQL, MariaDB, and PostgreSQL.
2. **Performance-First Asynchronous I/O:**
   - Player data uses an in-memory LRU cache (`StorageCache`) paired with a dirty-tracking batch queue (`StorageSaveQueue`).
   - Heavy disk operations (checkpoints, exports, backups) run on Bukkit asynchronous scheduler threads.
3. **Trait / Capability-Driven Items:**
   - Items define behaviors by implementing interfaces (`EnergyNetComponent`, `Rechargeable`, `DamageableItem`, `NotPlaceable`, etc.) rather than deep inheritance chains.

---

## 2. Directory Hierarchy

```text
SlimefunCore - 26.x/
├── .mvn/                       # Maven wrapper configuration & build-number.properties
├── build-tools/                # Cross-platform Java build number automation
├── docs/                       # Human-friendly documentation and runbooks
│   ├── adr/                    # Architecture Decision Records
│   ├── developer-guide/        # Developer architecture and structure guides
│   │   └── structure.md        # <-- THIS GUIDE
│   ├── sop/                    # Standard Operating Procedures (Update guide)
│   ├── storage/                # Storage, Checkpoints, and Migration documentation
│   ├── commands.md             # Complete commands and permissions index
│   └── configuration.md        # Comprehensive configuration reference
├── src/
│   ├── main/
│   │   ├── java/io/github/thebusybiscuit/slimefun4/
│   │   │   ├── api/            # Public API, Item abstractions, Events, Researches
│   │   │   ├── core/           # Registries, Services, Machine processor, Networks
│   │   │   ├── implementation/ # Plugin bootstrap, Concrete items, Listeners, Tasks
│   │   │   ├── integrations/   # Third-party plugin hooks (PAPI, WorldEdit, etc.)
│   │   │   ├── libraries/      # Internal bridge classes (SF4Items, SF4InvUtils, etc.)
│   │   │   ├── storage/        # Storage backends, Schemas, Checkpoints, Migrators
│   │   │   └── utils/          # General helper toolkits (Chat, Lore, Math, Items)
│   │   └── resources/          # plugin.yml, default configs, localization, wiki data
│   └── test/                   # Comprehensive unit tests (MockBukkit, Schemas, Backups)
├── pom.xml                     # Maven build configuration (Java 25, Paper 26.x)
└── build.ps1                   # Local Windows build script
```

---

## 3. Detailed Package Architecture

### 3.1. `api` — The Public Programming Interface
The `api` package provides contracts and base classes intended for plugin internals and external Slimefun addons.

| Package | Key Classes | Responsibility |
|---|---|---|
| `api` | `SlimefunAddon`, `MinecraftVersion`, `ErrorReport`, `SlimefunBranch` | Addon registration contracts, Minecraft year-based version enumeration (`MINECRAFT_26`), error reporters. |
| `api.items` | `SlimefunItem`, `SlimefunItemStack`, `ItemGroup`, `ItemSetting`, `ItemHandler` | The core item model. `SlimefunItem` represents a registered item type; `SlimefunItemStack` wraps the Bukkit display representation; `ItemSetting` handles per-item YAML configurations. |
| `api.researches` | `Research`, `PlayerResearchTask` | Tech progression nodes, unlock costs, level requirements, and async particle display tasks. |
| `api.geo` | `GEOResource`, `ResourceManager` | Biome-based resource generation (e.g., oil, ores, nether resources) mapped to world coordinates. |
| `api.gps` | `GPSNetwork`, `Waypoint` | GPS satellite tracking and player coordinate waypoints. |
| `api.player` | `PlayerProfile`, `PlayerBackpack`, `StatusEffect` | Player state representation: cached researches, active backpacks, and temporary player status effects. |
| `api.recipes` | `RecipeType` | Defines crafting mechanics (Enhanced Crafting Table, Ore Crusher, Smeltery, Magic Workbench, etc.). |
| `api.network` | `Network`, `NetworkComponent`, `NetworkVisualizer` | Abstract node graph representing interconnected blocks in energy and cargo lines. |
| `api.events` | `SlimefunBlockBreakEvent`, `SlimefunItemDropEvent`, etc. | Custom Bukkit events emitted during item creation, interaction, and block lifecycles. |

---

### 3.2. `core` — Engine Services, Systems & Registries
The `core` package houses the primary execution engines, service brokers, and runtime registries.

#### A. Registries & Services (`core.services`)
- `SlimefunRegistry`: Global central repository for all registered items, item groups, researches, tickers, and recipes.
- `StorageEngineService`: Core coordinator for database initialization, connection pools, schema repairs, backups, and checkpoints.
- `AutoSavingService`: Background daemon that triggers periodic player and block persistence.
- `BlockDataService`: Handles low-level block state persistence and chunk association.
- `ThreadService`: Manages asynchronous thread pools and Bukkit scheduler synchronization.
- `UpdaterService`: Checks for updates against Modrinth when running unofficial builds.
- `LocalizationService`: Translates messages and item lores into configured languages.

#### B. Machine & Network Processing (`core.machines`, `core.networks`)
- `MachineProcessor`: Manages active machine processing cycles, progress bars, and inventory item consumption.
- `MachineOperation`: Represents a recipe undergoing processing (duration, required input, produced output).
- `NetworkManager`: Ticks energy and cargo systems, resolving power loops and cargo path routing.
- `CargoNet` & `EnergyNet`: Concrete node graphs calculating energy supply/demand and item routing.

#### C. Component Attributes & Traits (`core.attributes`)
Slimefun uses interface-based capabilities:
- `EnergyNetComponent` / `EnergyNetProvider`: Identifies blocks that consume, store, or generate electricity.
- `Rechargeable`: Items that store internal electricity (e.g., Jetpacks, Battery items).
- `DamageableItem`: Custom items that have custom durability mechanics.
- `Radioactive` / `ProtectiveArmor`: Governs hazardous materials and hazmat suit protection.
- `WitherProof`: Blocks immune to Wither and explosion destruction.
- `Soulbound`: Items that persist in inventory upon player death.

#### D. Command Subsystem (`core.commands`)
- Central dispatcher: `SlimefunCommand` (`/slimefun`, alias `/sf`).
- Tab completion: `SlimefunTabCompleter`.
- 19 specialized subcommands under `core.commands.subcommands`:
  - `StorageCommand`: Full administration of database health, migrations, and backups.
  - `CheckpointCommand`: Handles `/sf checkpoint` (create, list, info, status, scoped restore).
  - `GiveCommand`, `CheatCommand`, `ResearchCommand`, `TeleporterCommand`, `BackpackCommand`, `TimingsCommand`, etc.

#### E. SFC Loader & Exporter (`core.sfcloader`)
Provides modular import/export capabilities for Slimefun configuration states and environments:
- `ExportService` & `ImportService`: Serializes and deserializes configurations.
- `LoaderValidator`: Validates manifests before application.
- `RollbackService`: Reverts failed imports safely.

---

### 3.3. `storage` — Multi-Backend Persistence & Checkpoint Architecture
The storage subsystem decouples physical storage from gameplay logic.

```text
+--------------------------------------------------------------------+
|                         STORAGE INTERFACE                          |
|             loadPlayerData(UUID) / savePlayerData(UUID)            |
+---------------------------------+----------------------------------+
                                  |
                                  v
+--------------------------------------------------------------------+
|                      STORAGE PROVIDER INTERFACE                    |
|                supportsSql() / borrowConnection()                  |
+---------------------------------+----------------------------------+
                                  |
         +------------------------+------------------------+
         |                        |                        |
         v                        v                        v
+------------------+    +-------------------+    +-------------------+
|  LegacyStorage   |    |   SQLiteStorage   |    |   SQL Providers   |
|  (YAML / Files)  |    |    (Local DB)     |    | MySQL / PG / Maria|
+------------------+    +-------------------+    +-------------------+
         |                        |                        |
+--------+------------------------+------------------------+---------+
|                          SUPPORTING SUBSYSTEMS                     |
| - SchemaManager & SchemaRepair (Dialect-aware DDL)                 |
| - StorageCache & StorageSaveQueue (Async LRU & Batch Write)        |
| - CheckpointService (Storage Half + Database Half)                 |
| - StorageMigrator & SqlTableCopier (Cross-Backend Migration)       |
+--------------------------------------------------------------------+
```

#### Managed Tables (SQL Providers)
1. `sf_schema_version`: Tracks applied migration version.
2. `player_researches`: UUID to unlocked research string IDs.
3. `player_backpacks`: UUID to backpack IDs and inventory slot capacities.
4. `player_backpack_items`: Serialized binary item data (`BLOB`/`BYTEA`) per slot.
5. `player_waypoints`: GPS waypoints (name, world, X, Y, Z).

#### Checkpoint Engine (`storage.checkpoint`)
A checkpoint represents a complete server save state bundled under `plugins/Slimefun/slimefun-checkpoints/checkpoint-NNN/`:
- **Storage Half (`storage/storage.zip`):** File-based machine data (`stored-blocks`, `stored-inventories`, `chunks.sfc`).
- **Database Half (`database/database-backup.*`):** Player progression tables exported as SQL/JSON or player files.
- **`metadata.json`:** Cryptographic SHA-256 checksums, timestamps, and entry counts.
- **Independent Restore:** Supports restoring `STORAGE_ONLY`, `DATABASE_ONLY`, or `BOTH` halves, with automatic rollback from pre-restore safety copies on failure.

#### Migration Pipeline (`storage.migration`)
Allows live server operators to migrate player data between any two backends (e.g. Legacy YAML to MySQL):
- Validates preconditions (requires 0 players online for execution).
- Directly streams raw table records to bypass deserialization loss.
- Compares cryptographic content digests between source and target before committing.

---

### 3.4. `implementation` — Concrete Content & Runtime Execution
The `implementation` package brings the plugin to life.

- **Bootstrap (`Slimefun.java`):** Main Bukkit `JavaPlugin`. Initializes services, registers listeners, starts schedulers, and cleans up connections on disable.
- **Catalogues (`implementation.setup`):**
  - `SlimefunItems.java`: Declares static references to every item stack.
  - `SlimefunItemSetup.java`: Initializes recipes, item groups, and registers items into the registry.
  - `ResearchSetup.java`: Sets up tech branches and unlocks.
- **Tasks & Tickers (`implementation.tasks`):**
  - `TickerTask`: Central heartbeat running every tick (or configured delay) to tick machines, update energy grids, and process cargo.
  - `ArmorTask`: Periodic task applying custom armor buffs and hazard damage.
  - `AncientAltarTask`: Manages ritual animations and item crafting on Ancient Altars.
- **Listeners (`implementation.listeners`):**
  - Over 40 listeners handling block clicks, machine placement, backpack opening, talisman triggers, entity interactions, and damage sources.

---

### 3.5. `integrations`, `libraries`, and `utils`
- **`integrations`:** Detects and bridges third-party plugins:
  - `PlaceholderAPIIntegration`: Custom placeholders for player stats and researches.
  - `WorldEditIntegration`: Protects Slimefun blocks during clipboard operations.
  - `ClearLagIntegration`: Prevents ClearLag from clearing critical Slimefun entities.
  - `McMMOIntegration`: Prevents double drops or skill exploits on Slimefun machines.
- **`libraries.bridge`:** Internal bridge isolating cross-version Bukkit changes (`SF4Items`, `SF4InvUtils`, `SF4ChatInput`, `SF4Colors`).
- **`utils`:** High-performance utilities:
  - `SlimefunUtils`: Item comparison, custom head textures, and recipe validation.
  - `ChestMenuUtils`: UI icons and menu layout templates.
  - `RadiationUtils`: Radiation checks and hazard effects.
  - `LoreBuilder`: Standardized lore text formatting for machines, power, and capacitors.

---

## 4. Key Execution Lifecycles

### 4.1. Server Startup Lifecycle
```text
1. JavaPlugin.onLoad()
   └── Load localizations and identify server version.
2. JavaPlugin.onEnable()
   ├── Step 1: Initialize ThreadService and thread pools.
   ├── Step 2: Initialize StorageEngineService -> Connect to DB -> Verify/Repair Schema.
   ├── Step 3: Register all ItemGroups, Categories, and Researches.
   ├── Step 4: Register all SlimefunItems and assign recipes.
   ├── Step 5: Initialize BlockStorage and scan world data.
   ├── Step 6: Register 40+ Bukkit Event Listeners.
   ├── Step 7: Register Commands (/sf, /addon).
   ├── Step 8: Start scheduled tasks:
   │           ├── TickerTask (Heartbeat)
   │           ├── AutoSavingService (Periodic flush)
   │           └── ArmorTask (Buffs & Radiation)
   └── Step 9: Check for updates (if unofficial branch).
```

### 4.2. The Machine Tick Cycle (`TickerTask`)
```text
Every N Ticks:
├── 1. Process Energy Networks
│      ├── Collect available energy from all Generators/Capacitors.
│      └── Distribute power to hungry Machine consumers.
├── 2. Process Cargo Networks
│      ├── Input Nodes scan adjacent container inventories.
│      └── Route matched items to Output Nodes via shortest path.
├── 3. Process Active Machines
│      ├── Query MachineProcessor for active MachineOperations.
│      ├── Advance operation progress timers.
│      └── On completion: deposit output item into machine inventory.
└── 4. Update Hologram Displays (if enabled).
```

---

## 5. Developer Guide: Extending Slimefun

### Creating a Simple Custom Slimefun Item
```java
public class SuperBattery extends SlimefunItem implements Rechargeable {

    private final float maxEnergy;

    public SuperBattery(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe, float maxEnergy) {
        super(itemGroup, item, recipeType, recipe);
        this.maxEnergy = maxEnergy;
    }

    @Override
    public float getMaxEnergy() {
        return maxEnergy;
    }

    @Override
    public void preRegister() {
        // Register custom item click or interact handlers here
        addItemHandler(new ItemUseHandler() {
            @Override
            public void onRightClick(ItemUseEvent event) {
                event.getPlayer().sendMessage("Current Energy: " + getItemCharge(event.getItem()));
            }
        });
    }
}
```

### Creating an Electric Machine
```java
public class ElectricSmelter extends SlimefunItem implements EnergyNetComponent {

    private final int energyConsumption = 25;

    public ElectricSmelter(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Nonnull
    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return 500;
    }
}
```

---

## 6. Build and Verification Guidelines

- **Cross-platform Java 25 build:**
  ```shell
  mvn -B clean verify -DskipTests
  ```
- **Java 25 coverage:**
  JaCoCo `0.8.14` supports Java 25 class files. Bukkit-free test classes can run with the agent enabled.
- **Build numbers:**
  Maven prepares the next number during `initialize` and atomically commits it during `verify`. Failed builds leave `.mvn/build-number.properties` unchanged.
