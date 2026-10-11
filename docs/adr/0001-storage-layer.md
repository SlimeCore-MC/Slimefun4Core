# 1. The Storage Layer

**Date:** 2023-11-15 (upstream)
**Last Update:** 2026-09-06 (SlimefunCore4)

## Status

**Implemented for player data. Marked `@Beta`.** 
Block data was never moved to the new system and we don't plan to move it here. 

Note: `@Beta` means the API might change between builds, not that the code is unstable. Addon authors shouldn't depend on these classes just yet.

## Context

Slimefun historically saved everything in massive YAML files (often with a JSON object per line). While easy to read, it's terrible for performance: files get huge, data corrupts, and everything is loaded into memory at once.

Upstream Slimefun planned to fix this in phases. Meanwhile, servers running large worlds on this fork needed a real database for player data *now*, plus a way to back it up without shutting down the server.

## The Decision

We built a new storage system with interchangeable backends, but **only for player data**. We also built backup, restore, migration, and checkpoint systems on top of it.

Block data stays in YAML. Moving `BlockStorage` is risky and massive, so we aren't touching it. However, the new backup system still captures it by copying the legacy files exactly.

### Quick Overview
- **Interchangeable Backends:** Legacy YAML, SQLite, MySQL, MariaDB, and PostgreSQL.
- **Schema Management:** Auto-generates and verifies tables based on the database dialect.
- **Async I/O:** Caching and an async save queue so the server doesn't lag when saving.
- **Backups & Restores:** Checksummed archives that match the active backend.
- **Migration:** Move player data between any two backends on the fly.
- **Checkpoints:** Captures the whole Slimefun state (database + files) as a single restorable unit.
- **Commands:** `/sf storage` and `/sf checkpoint`, plus an in-game GUI panel.

## How It Works Under the Hood

The main interface is `Storage`. It's deliberately kept simple: `loadPlayerData(UUID)` and `savePlayerData(UUID, PlayerData)`, along with async versions and lifecycle hooks.

Under that is `StorageProvider`, which represents the actual backend. It provides methods like `supportsSql()` and `borrowConnection()` so we don't have to write messy special cases for non-SQL backends.

Key classes to know:
- **Core Abstractions:** `Storage`, `StorageProvider`
- **Backends:** `LegacyStorage` (YAML), `SQLiteStorage`, `MySQLProvider`, `MariaDBProvider`, `PostgreSQLProvider`
- **Schema & DDL:** `SchemaManager`, `SchemaRepair`
- **I/O & Performance:** `StorageCache`, `StorageSaveQueue`
- **Backups:** `DatabaseBackupService`, `LegacyFileBackup`, `ZipTreeWriter`
- **Migrations:** `StorageMigrator`, `SqlTableCopier`
- **Checkpoints:** `CheckpointService`

### The Tables
For SQL backends, we use five tables:
- `sf_schema_version`: Tracks the current database schema.
- `player_researches`: Unlocked researches.
- `player_backpacks`: Backpack IDs and their sizes.
- `player_backpack_items`: Serialized item data (`BLOB` / `BYTEA`).
- `player_waypoints`: Waypoint names and coordinates.

*Note: `player_backpack_items.item_data` is the only nullable binary column. Because of this, restores bind parameters strictly by type, not as strings.*

### Backups and Restores

The backup format is written into the backup file itself, making it impossible to accidentally restore a file dump into a SQL database. 

We support four backup formats:
1. `SQL_JSON`: Every row of the five SQL tables exported as JSON.
2. `LEGACY_FILES`: A byte-for-byte ZIP of the YAML data tree (player data + block data).
3. `BLOCK_FILES`: A ZIP of just the block sections. Used automatically before migrations or via `/sf storage backup blocks`.
4. `PLAYER_FILES`: A ZIP of just player data and waypoints. Acts as the database half of a checkpoint when running on Legacy YAML.

**Safety First:**
- All backups include a SHA-256 checksum.
- SQL restores run in a single transaction. If it fails, nothing changes.
- File/block restores take a snapshot *before* extracting, stage the files safely, and abort if any file path tries to escape the target directory. 
- Block restores actually dry-run parsing every extracted file and fail if anything is broken.

### Migrating Between Backends

Driven by `/sf storage migrate`. See [docs/storage/migration-guide.md](../storage/migration-guide.md) for the runbook.

Important design decisions:
- **We skip `Storage.loadPlayerData` during migration.** If we used the standard loader, it would silently drop invalid items, missing worlds, or bad backpack sizes. Instead, the migration reads and writes the raw database records directly. If it hits something it can't convert, it aborts.
- **SQL-to-SQL is a direct table copy.** It streams `ResultSet` straight to a `PreparedStatement`. No JSON strings or hex conversions in the middle.

*Reminder: Block data is not migrated. It always lives in YAML.*

### Checkpoints

Driven by `/sf checkpoint`. See [docs/storage/checkpoints.md](../storage/checkpoints.md) for the runbook.

A backup only covers one type of data. A checkpoint is a full snapshot of the entire plugin state: a "database half" and a "storage half", bundled together in a `checkpoint-NNN` folder.

Important design decisions:
- **Two halves, not one archive.** If you use MySQL, your player data is in SQL and your block data is in files. We have to snapshot both separately.
- **Creation is all-or-nothing.** If either half fails to save or fails its checksum verification, the whole checkpoint is deleted.
- **Retention keeps the newest.** We always write and verify the new checkpoint *before* deleting old ones to enforce the retention limit. Restores don't overwrite history, they just roll back the active data.
- **Restores are highly restricted.** You can only run them from the console, and only when no players are online. It takes a safety snapshot before doing anything, allowing a full rollback if the restore crashes halfway through.

## Addons

Addons still can't implement their own backends or use this system to save their own custom data. We prioritized getting the core server admin tools stable first.

## Upstream's Old Plan (For History)

Upstream originally planned to move to a custom binary format for everything, including blocks. This fork abandoned that idea, opting for standard SQL for player data and leaving blocks in YAML.

## Known Quirks

- `pool.leak-detection-threshold: 0` is supposed to disable leak detection, but the getter treats `<= 0` as the default value instead.
- The `StorageConsistencyChecker.startScheduler()` method exists but is never actually called. The block-parsing check inside it is used manually by restores and migrations instead.
- Backpacks aren't covered by automated tests for the legacy backend due to how `ItemStack` serialization requires a live Bukkit server.

## Test Coverage

We have over 100 tests covering schemas, backups, checksums, and migrations. They run completely Bukkit-free using `mockbukkit-v1.21`.

**Important:** You must run builds with `-DskipTests` (or `-Djacoco.skip=true`) because JaCoCo 0.8.12 crashes when trying to read Java 25 classes.
