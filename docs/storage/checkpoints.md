# Slimefun Checkpoints — Backup & Restore Guide

A **checkpoint** is a point-in-time snapshot of your Slimefun server state. Each checkpoint contains two independent halves captured together in a single folder:

| Component | What it Contains | Source Location |
|---|---|---|
| **Storage Half** | Machine blocks, inventories, and chunk data (YAML/binary) | `data-storage/Slimefun/` |
| **Database Half** | Player researches, backpacks, items, and waypoints | Active database provider (SQL or Flatfile) |

```text
plugins/Slimefun/slimefun-checkpoints/
    checkpoint-001/
        storage/storage.zip
        database/database-backup.zip (or .sql/.json)
        metadata.json
    checkpoint-002/
        ...
    safety/
        pre-restore-20260906-075400/
```

> **Important Boundary:** Checkpoints backup **Slimefun data only**. They do **not** replace full Minecraft server/world backups. Minecraft region files (`.mca`), `level.dat`, entities, and vanilla `playerdata` remain managed by Paper/Minecraft.

---

## Quick Reference (Cheat Sheet)

```bash
# Check if data changed since the last checkpoint
/sf checkpoint status

# Create and verify a new checkpoint immediately
/sf checkpoint create

# View all available checkpoints
/sf checkpoint list

# Inspect details and checksums for a specific checkpoint
/sf checkpoint info 3

# Restore ONLY file-based machine/block/inventory data (Console only)
/sf checkpoint restore storage 3

# Restore ONLY player database data (researches/backpacks/waypoints) (Console only)
/sf checkpoint restore database 3

# Restore BOTH halves together (Console only; explicit confirmation required)
/sf checkpoint restore 3 confirm
```

*(Note: `<id>` accepts `3`, `003`, or `checkpoint-003`)*

---

## What Is Stored in Each Half

### 1. Storage Half (`storage.zip`)
Captures all file-based Slimefun data owned by the plugin:
* `stored-blocks/` — Placed Slimefun machines, tickers, and block data
* `stored-inventories/` — Machine container inventories
* `universal-inventories/` — Shared machine buffers
* `stored-chunks/chunks.sfc` — Chunk-level Slimefun data

> **Note for MySQL/PostgreSQL Users:** Slimefun block and machine data lives in YAML files on disk under `data-storage/Slimefun/` on **all** storage providers. Only player data is stored in SQL tables. Therefore, machine data is always saved in the **storage half**, never the database half.

### 2. Database Half (`database-backup.*`)
Captures Slimefun player progression tables from your active database:
* `player_researches` — Unlocked Slimefun tech tree researches
* `player_backpacks` & `player_backpack_items` — Backpack contents & slot sizes
* `player_waypoints` — GPS waypoints and coordinates

---

## Creating a Checkpoint

Run:
```bash
/sf checkpoint create
```
*(Requires permission `slimefun.admin.checkpoint`)*

### Execution Workflow:
1. **Flushes memory to disk:** Forces any dirty player profiles and pending block saves to disk.
2. **Compresses storage:** Archives the block-storage sections under `data-storage/Slimefun/` into `storage/storage.zip`.
3. **Exports database:** Dumps Slimefun SQL tables (or player files) into `database/database-backup.*`.
4. **Calculates SHA-256:** Writes and validates cryptographic hashes in `metadata.json`.
5. **Self-verifies:** Re-parses metadata and checks hashes. If any step fails, the partial checkpoint is automatically deleted.
6. **Purges oldest:** Enforces retention limits **only after** the new checkpoint is confirmed healthy.

---

## Retention Policy

Configured via `checkpoint.max-checkpoints` in `database.yml` (default: `10`).

When the limit is reached and a new checkpoint is created:
1. The new checkpoint is created and fully verified.
2. Only after validation succeeds is the oldest checkpoint deleted.
3. If creation fails, your existing checkpoints are untouched.

---

## Restoring Checkpoints

Restoration is **strictly console-only** and requires an **empty server (0 players online)**.

### Independent Restores
You can restore either component individually or both together:

* **Restore Storage Only:**
  ```bash
  /sf checkpoint restore storage <id>
  ```
  Overwrites block and machine data from the snapshot. Leaves player tech progression and backpacks completely untouched. Useful if someone blew up a machine line or a chunk corrupted.

* **Restore Database Only:**
  ```bash
  /sf checkpoint restore database <id>
  ```
  Overwrites player researches, backpacks, and waypoints from the snapshot. Leaves placed world machines and inventories untouched. Useful for fixing player progression issues or inventory loss without rolling back the physical world.

* **Restore Both Halves:**
  ```bash
  /sf checkpoint restore <id> confirm
  ```
  Restores both file storage and player database tables together. The literal `confirm` is required because this is the broadest restore scope.

### Safety Guarantee (Automatic Rollback)
Before touching any live files or database tables:
1. Slimefun automatically generates a pre-restore safety copy in `slimefun-checkpoints/safety/pre-restore-<timestamp>/`.
2. If any file extraction or SQL import fails, the system **automatically rolls back the selected scope** to your pre-restore state.
3. Restoring an older checkpoint (e.g. `checkpoint-003`) does **not** delete newer checkpoints.

> **Always restart your server** after a restore finishes so that in-memory caches re-load cleanly from disk and database.

---

## Change Detection (`/sf checkpoint status`)

Run `/sf checkpoint status` to check if data has changed since your last checkpoint:

```text
Last checkpoint    : checkpoint-007
Storage changed    : YES
Database changed   : NO
Checkpoint required: YES
```

Slimefun tracks dirty states internally. It does not spam new backups on every file touch — it marks the state dirty so you (or an automated schedule) know when a backup is actually needed.

---

## Configuration (`database.yml`)

```yaml
checkpoint:
  # Enable or disable the checkpoint system
  enabled: true

  # Maximum number of checkpoints to retain
  max-checkpoints: 10

  # Root directory for checkpoint storage (relative to plugins/Slimefun)
  directory: slimefun-checkpoints

  # Enable automatic background checkpoint creation
  automatic: false

  # Interval in hours between automatic checkpoints
  interval-hours: 12

  # Skip automatic checkpoint if no data has changed
  only-when-changed: true

  # Compress database backup files
  compress: true

  # Number of pre-restore safety backups to retain (0 to keep all)
  keep-safety-backups: 3
```

---

## Troubleshooting

| Error / Message | Cause | Solution |
|---|---|---|
| `/sf checkpoint restore is console-only` | Command attempted from in-game chat | Run the command directly from the server console |
| `Refusing to restore while N player(s) are online` | Server is not empty | Kick players or run maintenance mode before restoring |
| `No checkpoint matches: <id>` | Invalid or mistyped ID | Run `/sf checkpoint list` to see valid IDs |
| `Another checkpoint operation is already running` | A backup or restore task is already active | Wait a moment for the previous task to complete |
| `The checkpoint system is disabled in database.yml` | `checkpoint.enabled` is false | Set `enabled: true` in `database.yml` and restart |
| `Restorable : NO` | Metadata or backup archive corrupted | Choose another checkpoint from `/sf checkpoint list` |
