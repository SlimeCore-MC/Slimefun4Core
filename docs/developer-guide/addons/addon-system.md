# Slimefun Addon System & Plugin Architecture Guide

> **Target Version:** Paper 26.x / SlimefunCore 26.x  
> **Java Version:** Java 25  
> **Last Updated:** September 2026

Slimefun is built to be an extensible platform. Third-party developers can create standalone Bukkit/Paper plugins that hook into SlimefunCore to register custom machines, resources, weapons, magic systems, and researches.

This guide explains the Slimefun addon architecture from two perspectives:
1. **The Addon Plugin Architecture:** How to build, structure, and code an addon from scratch.
2. **The Core Addon Subsystem (`AddonManager`):** How SlimefunCore discovers, downloads, syncs, and manages addons at runtime.

---

## 1. High-Level Addon Architecture

```text
+-----------------------------------------------------------------------------+
|                          YOUR CUSTOM ADDON PLUGIN                           |
|  (e.g., SlimyTreeTaps, InfinityExpansion, Networks, FluffyMachines)        |
+--------------------------------------+--------------------------------------+
                                       |
                   implements SlimefunAddon
                   registers Items, Categories, Researches
                                       |
                                       v
+-----------------------------------------------------------------------------+
|                               SLIMEFUN CORE                                 |
|                                                                             |
|  [SlimefunRegistry] <--- item.register(addon)                               |
|         |                                                                   |
|         +---> Attaches Handlers (ItemUseHandler, BlockBreakHandler, etc.)   |
|         +---> Binds to EnergyNet / CargoNet (if EnergyNetComponent)         |
|         +---> Injects into Guide GUI (ItemGroup & RecipeType)               |
|         +---> Links to Tech Progression (Research)                          |
|                                                                             |
|  [AddonManager] <--- Manages addon jars, cloud sync, and update checks      |
+-----------------------------------------------------------------------------+
```

When your addon loads, it acts as a content provider. You define item blueprints and register them into Slimefun's central `SlimefunRegistry`. Once registered, SlimefunCore handles the block ticking, machine operations, inventory clicks, protection checks, and persistence automatically.

---

## 2. Building an Addon Plugin (Developer Guide)

### Step 1: Project Setup (`pom.xml`)
Your addon must target Java 25 and depend on Paper API 26.x and SlimefunCore:

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<dependencies>
    <!-- Paper API 26.x -->
    <dependency>
        <groupId>io.papermc.paper</groupId>
        <artifactId>paper-api</artifactId>
        <version>26.1.2.build.72-stable</version>
        <scope>provided</scope>
    </dependency>

    <!-- SlimefunCore -->
    <dependency>
        <groupId>com.github.SlimefunCoreV4.0</groupId>
        <artifactId>SlimefunCoreV4.0</artifactId>
        <version>4.0-UNOFFICIAL</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Step 2: Plugin Descriptor (`plugin.yml`)
Your addon **must** declare `Slimefun` as a hard dependency and set the modern `api-version`:

```yaml
name: MyCustomAddon
version: 1.0.0
main: com.example.myaddon.MyCustomAddon
api-version: '26.1'
author: YourName
depend:
  - Slimefun
```

### Step 3: Implement `SlimefunAddon`
Your main class extends `JavaPlugin` and implements `SlimefunAddon`:

```java
package com.example.myaddon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;

public class MyCustomAddon extends JavaPlugin implements SlimefunAddon {

    private static MyCustomAddon instance;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Enabling MyCustomAddon...");

        // Content registration happens here
        MyAddonSetup.init(this);
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    @Nonnull
    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Nullable
    @Override
    public String getBugTrackerURL() {
        // Shown in Slimefun auto-generated crash and bug reports
        return "https://github.com/YourName/MyCustomAddon/issues";
    }

    public static MyCustomAddon getInstance() {
        return instance;
    }
}
```

---

## 3. Registering Content

### 1. Creating an ItemGroup (Guide Category)
Every item in Slimefun belongs to an `ItemGroup`. This determines where it appears inside `/sf guide`:

```java
NamespacedKey categoryId = new NamespacedKey(MyCustomAddon.getInstance(), "addon_category");
CustomItemStack categoryIcon = new CustomItemStack(
    Material.DIAMOND_PICKAXE, 
    "&bMy Custom Addon", 
    "", 
    "&7Explore custom machines and tools"
);

ItemGroup itemGroup = new ItemGroup(categoryId, categoryIcon);
```

### 2. Creating a Custom Item
Slimefun items consist of:
- A `SlimefunItemStack` (the visual ItemStack).
- A `RecipeType` (how it is crafted).
- A 9-slot crafting recipe array (`ItemStack[]`).

```java
// 1. The visual item representation
SlimefunItemStack myItem = new SlimefunItemStack(
    "MY_CUSTOM_ITEM",
    Material.NETHERITE_INGOT,
    "&6Super Alloy",
    "&7A highly refined metallic compound",
    "&7used in advanced machinery."
);

// 2. The recipe ingredients (9-slot grid)
ItemStack[] recipe = {
    new ItemStack(Material.IRON_INGOT),      new ItemStack(Material.DIAMOND), new ItemStack(Material.IRON_INGOT),
    new ItemStack(Material.NETHERITE_SCRAP), new ItemStack(Material.NETHER_STAR), new ItemStack(Material.NETHERITE_SCRAP),
    new ItemStack(Material.IRON_INGOT),      new ItemStack(Material.DIAMOND), new ItemStack(Material.IRON_INGOT)
};

// 3. Instantiate the SlimefunItem
SlimefunItem slimefunItem = new SlimefunItem(
    itemGroup, 
    myItem, 
    RecipeType.ENHANCED_CRAFTING_TABLE, 
    recipe
);

// 4. Register with your SlimefunAddon instance!
slimefunItem.register(addon);
```

### 3. Adding Item Handlers (Click & Interaction Logic)
To make your items do things, attach item handlers:

```java
slimefunItem.addItemHandler(new ItemUseHandler() {
    @Override
    public void onRightClick(ItemUseEvent event) {
        Player player = event.getPlayer();
        player.sendMessage("You activated the Super Alloy!");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }
});
```

### 4. Creating an Energy Machine
To create a block that consumes electricity from an Energy Network, implement `EnergyNetComponent`:

```java
public class ElectricGrinder extends SlimefunItem implements EnergyNetComponent {

    private final int energyPerTick = 16;

    public ElectricGrinder(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Nonnull
    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return 256; // Max stored energy buffer in Joules
    }
}
```

### 5. Adding Research / Tech Nodes
Researches lock items in the guide until unlocked using XP levels:

```java
NamespacedKey researchKey = new NamespacedKey(MyCustomAddon.getInstance(), "super_alloy_research");
Research research = new Research(researchKey, 12345, "Alloy Engineering", 15);

// Add items unlocked by this research node
research.addItems(slimefunItem);

// Register the research into Slimefun
research.register();
```

---

## 4. Modern 26.x Changes & Pitfalls for Addons

If you are updating older Slimefun addons to modern 26.x, be aware of these major changes:

### 1. `SlimefunItemStack` No Longer Extends `ItemStack`
- **Old Behavior:** `SlimefunItemStack` extended `ItemStack`. You could pass it directly to Bukkit methods.
- **26.x Behavior:** `SlimefunItemStack` is a dedicated wrapper.  
  - When Bukkit requires an `ItemStack`, call **`.item()`** or use `CustomItemStack.create(...)`.
  - Example: `player.getInventory().addItem(slimefunItemStack.item());`

### 2. `Research.addItems()` Takes `SlimefunItem` Instances
- In older builds, `research.addItems()` accepted `SlimefunItemStack`.
- In 26.x, you should pass the registered `SlimefunItem` instance (or call `.getItem()` on the item stack).

### 3. Dough Library Packaging
- Dough classes are relocated under Slimefun's internal package:
  `io.github.thebusybiscuit.slimefun4.libraries.dough`
- Do not shade an outdated external `io.github.bakedlibs.dough` into your addon jar if it conflicts with the server runtime.

### 4. Java 25 Bytecode
- Compile your addon targeting release **25**. Older Java runtimes (Java 17/21) will be rejected by Paper 26.x servers.

---

## 5. SlimefunCore's Internal Addon System (`AddonManager`)

SlimefunCore includes a built-in addon management subsystem (`io.github.thebusybiscuit.slimefun4.core.addons.AddonManager`).

```text
AddonManager
├── ConfigManager          # Reads/writes addonmanager.yml
├── GitHubSyncService      # Syncs addon index from remote repository
├── AddonService           # Coordinates enabled/disabled/installed states
├── DownloadService        # Fetches remote jar files securely
├── AddonLoader            # Inspects plugin.yml in downloaded jars
└── CommandManager         # Powers /addon and /sf addon commands
```

### Configuration (`plugins/Slimefun/addonmanager.yml`)
Controls how Slimefun interacts with the official compatible addons repository:

```yaml
cloud-sync:
  enabled: true
  url: "https://raw.githubusercontent.com/.../addonmanager.yml"
  interval-minutes: 10
  fallback-to-local-on-empty: true
  fallback-to-local-on-failure: true

addons:
  SlimyTreeTaps:
    name: "SlimyTreeTaps"
    version: "latest"
    enabled: true
    download: true
    auto-update: false
```

### Key Subsystems:
1. **Cloud Sync (`GitHubSyncService`):** Periodically fetches the compatibility manifest from GitHub to discover new addons and updates without requiring manual server jar downloads.
2. **Version Tag Resolver (`VersionTagResolver`):** Automatically maps the server's current Minecraft/Paper build (`MINECRAFT_26`) to the compatible addon release tag.
3. **Download & Verification (`DownloadService`):** Downloads jar files into `plugins/Slimefun/addons/repository/`, verifies SHA-256 hashes if specified, and copies them to `plugins/` for the next server boot.
4. **Lifecycle Management (`CommandManager`):**
   - `/addon list`: Displays installed, enabled, and available managed addons.
   - `/addon sync`: Forces an immediate remote index fetch.
   - `/addon download <name>`: Downloads the appropriate jar.
   - `/addon update <name>`: Updates an existing addon.
   - `/addon enable <name>` / `/addon disable <name>`: Toggles addon loading on the next restart.
