# Updating the Fork to a New Minecraft Version

**Date:** 2024-01-15 (upstream)
**Last updated:** 2026-09-04 (SlimefunCore4)

This guide explains how to update this Slimefun fork to a new Minecraft version. Even minor releases can break the API, so read this carefully.

## Before You Start

- **Cross-platform build:** Maven uses the Java build-number helper on Windows, Linux, and macOS.
- **Build numbers commit on success:** `package` prepares the next artifact number, while `verify` atomically persists it only after all earlier phases succeed.
- **MockBukkit limitation:** The available MockBukkit release targets Minecraft 1.21 and cannot initialize against Paper 26.x. Use `-DskipTests` for the full Paper 26 build and run Bukkit-facing behavior on a Paper 26 test server.

## The Update Process

### 1. Update the API Version
Update these two files to match your target version (e.g., `26.1.2` for Paper, `26.1` for Bukkit API):
- `pom.xml`: Change the `paper.version` property.
- `src/main/resources/plugin.yml`: Change the `api-version`.

If the required Java version changed, update `maven.compiler.release` in the `pom.xml` too.

Test it:
```shell
mvn -B clean verify -DskipTests
```

### 2. Add the Version Constant
Slimefun uses its own `MinecraftVersion` enum. It uses a year-based system (e.g., `MINECRAFT_26`). 

If we've hit a new year (like 27), add `MINECRAFT_27` to `MinecraftVersion.java`. 
**Crucial:** You must add it *before* `UNKNOWN` and `UNIT_TEST`. The order matters! If you put it at the very end, tests will silently fail to run version-gated code.

### 3. Check for Breaking Changes
Read the Paper/Purpur changelogs. Pay special attention to:
- **Damage sources:** Bukkit frequently changes how damage sources are built. 
- **Reflection:** Methods like `getDeclaredMethod` don't see inherited methods. Watch out for database provider inheritance.
- **Registries:** Material and EntityType renames or removals.
- **NMS:** Anything touching `net.minecraft` or `org.bukkit.craftbukkit`.

### 4. Build and Test
Build the Paper 26 artifact:
```shell
mvn -B clean verify -DskipTests
```
JaCoCo 0.8.14 supports Java 25. MockBukkit remains limited to 1.21, so verify Bukkit-facing behavior on a Paper 26 test server.

### 5. In-Game Testing Checklist
Start a local server on the target version and test everything manually:

**The Basics:**
- Server boots without errors.
- `/sf guide` opens and items render properly.
- Machines work (try an Electric Furnace and Coal Generator).
- Multiblocks work (Enhanced Crafting Table, Magic Workbench).
- Cargo networks work (place an input, output, and Cargo Manager).
- Weapons deal damage correctly (especially `Explosive Bow` and `Seismic Axe`).
- Researches save across rejoins.
- Backpacks keep their items across restarts.

**Storage & Checkpoints:**
- `/sf storage gui` opens and shows health stats.
- Create a backup from the GUI, then restore it.
- Take a block backup (`/sf storage backup blocks`) and restore it.
- Try a dry-run migration (`/sf storage migrate check sqlite`).
- Create a checkpoint (`/sf checkpoint create`) and verify both halves saved.
- Restore a checkpoint from the console and confirm nothing broke.

### 6. Update Documentation
Don't forget to update:
- `README.md` (Version badges).
- `CHANGELOG.md` (Add a new entry for the build).
- This very file, if the update process changed.

### 7. Release It
Run `mvn clean package` one last time. This bumps the build number and produces `target/SlimefunCore4-<build>.jar`.
Upload it to Modrinth under the `slimefuncore` project. 

*Note: The `UNOFFICIAL` tag in the pom version tells the plugin to check Modrinth for updates. Do not remove it!*

## Troubleshooting
If something breaks on this fork, report it to our tracker, not the official Slimefun tracker:
https://github.com/rutwok-labs/Slimefun4Core/issues
