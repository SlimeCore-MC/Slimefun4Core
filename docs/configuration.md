# Configuration Files

Slimefun generates three main configuration files in `plugins/Slimefun/` on its first boot:

| File | What it controls |
|---|---|
| `config.yml` | Gameplay mechanics, furnaces, updates, and general settings. |
| `database.yml` | Database choice, connection pooling, caches, backups, and checkpoints. |
| `addonmanager.yml` | Automatic addon downloads and syncing. |

*Warning: Slimefun rarely supports hot-reloading. You should assume that changing ANY of these settings requires a full server restart to take effect.*

---

## 1. `config.yml`

This file controls the actual gameplay of Slimefun. 

### General Options (`options`)
| Key | Default | What it does |
|---|---|---|
| `auto-update` | `true` | Checks for plugin updates on startup. |
| `auto-download-update` | `true` | Automatically downloads the latest jar to `plugins/update/` for the next restart. |
| `modrinth-project` | `slimefuncore` | Modrinth project slug used by unofficial builds for update checks. |
| `enable-armor-effects` | `true` | Enables special effects on Slimefun armor. |
| `enable-radiation` | `true` | Radioactive items will hurt players who don't have protection. |
| `drop-block-creative` | `true` | Slimefun machines will drop as items when broken in Creative mode. |
| `legacy-ore-grinder` | `true` | Reverts the Ore Grinder to its old behavior. |

### Guide Book (`guide`)
- `show-vanilla-recipes`: Set to `false` if you don't want the guide to show normal Minecraft crafting recipes.
- `receive-on-first-join`: Automatically gives new players a Slimefun guide.

### Research (`researches`)
- `free-in-creative-mode`: OPs in creative mode don't pay XP for researches.
- `enable-fireworks`: Shoots a firework when a player unlocks something.
- `disable-learning-animation`: Skips the little pop-up UI animation when unlocking things.

### Machines & Tickers (`URID`)
Controls how often machines process items.
- `info-delay`: Milliseconds between hologram updates above machines.
- `custom-ticker-delay`: Ticks between machine processing cycles (Default `10`). Lowering this makes machines process faster, but increases server lag.
- `enable-tickers`: Master switch. Setting this to `false` disables all Slimefun machines entirely.

### Furnace Multipliers (`furnaces`)
You can globally change how fast furnaces smelt and how much output they give!
```yaml
furnaces:
  speeds:
    enhanced-furnace-1: 1
    reinforced-furnace: 40
  multipliers:
    enhanced-furnace-1: 1
    reinforced-furnace: 15
```
- **`speeds`**: How many times faster the furnace cooks.
- **`multipliers`**: Output yield (e.g. `2` means smelting 1 iron ore gives 2 iron ingots).

### Monitor & Report System (`monitor`)
Controls Discord notifications, the in-game Discord invite broadcast, and automatic error reporting. All links (GitHub, Modrinth, Discord invite) are integrated inside the build.
```yaml
monitor:
  discord:
    broadcasts: true
  invite:
    broadcast-on-join: true
  auto-report:
    enabled: true
```
- **`discord.broadcasts`**: Broadcast Discord messages for core updates and addon events to the report webhook.
- **`invite.broadcast-on-join`**: Show the Discord invite (and the `/sf report` reminder) to players when they join.
- **`auto-report.enabled`**: Automatically post `SEVERE` Slimefun/addon errors to the report Discord webhook. Identical errors are cached and not re-sent within the dedup window, so a crash loop cannot flood the channel.

---

## 2. `database.yml`

This is the brain of the new storage system. It handles databases, backups, and performance caches.
*(For a full breakdown of how to use these systems, read [storage-knowledge.md](storage/storage-knowledge.md)).*

### The Provider Block
This is where you tell Slimefun what database to use.
```yaml
provider: legacy            # Options: legacy, sqlite, mysql, mariadb, postgresql
auto-create-tables: true    # Automatically creates tables if they are missing
```
If you pick `mysql` (or mariadb/postgresql), you must fill in your credentials below it:
```yaml
mysql:
  host: localhost
  port: 3306
  database: slimefuncore
  username: root
  password: "your_password" # <-- Fill this out!
  ssl: false
```
*Note: We never print or log this password anywhere for security.*

### Automated Backups
```yaml
backup:
  enabled: true
  automatic: true
  interval-hours: 6
  keep-last: 20
```
Every 6 hours, the plugin will take a backup and keep the last 20. It's smart enough to never prune your backups down to zero, even if the database goes offline.

### Automated Checkpoints
Checkpoints take a snapshot of both your SQL player data AND your file-based block data.
```yaml
checkpoint:
  enabled: true
  automatic: false
  max-checkpoints: 10
```

---

## 3. `addonmanager.yml`

This controls the built-in Addon Manager (`/sf addon`), which automatically downloads third-party addons directly from GitHub.

```yaml
cloud-sync:
  enabled: true
  url: "https://raw.githubusercontent.com/.../addonmanager.yml"
  interval-minutes: 10
```

- If `enabled` is true, the plugin will periodically ping the remote index to look for new addons or updates. 
- All downloaded jars are placed in `plugins/Slimefun/addons/`.
- If you don't trust automated downloads, set `enabled: false` and manage the `addons` block manually!
