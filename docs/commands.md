# Commands & Permissions

This document lists every command and permission node registered by this fork. 
The two main base commands are `/slimefun` (alias `/sf`) and `/addon`.

Typing `/sf` by itself opens an interactive help menu tailored to the permissions you have.

---

## `/sf` Commands

*(Note: `debug` and `debug_fish` are hidden and won't show in help menus).*

| Command | Permission required | Who can use it? | Description |
|---|---|---|---|
| `/sf help` | — | Everyone | Prints the help menu |
| `/sf guide` | `slimefun.command.guide` | Everyone | Gives you the Slimefun Guide book |
| `/sf search <term>` | `slimefun.command.search` | Everyone | Searches the guide |
| `/sf report <category>` | `slimefun.report.use` | Everyone | Opens the interactive bug/report wizard in chat |
| `/sf stats [player]` | `slimefun.stats.others` | OP | View stats. (Looking at others requires the perm) |
| `/sf open_guide` | `slimefun.command.open_guide` | OP | Opens the guide GUI directly without the book item |
| `/sf cheat` | `slimefun.cheat.items` | OP | Opens the creative cheat menu |
| `/sf give <player> <item> [amount]` | `slimefun.cheat.items` | OP | Gives a Slimefun item directly by its ID |
| `/sf research <player> <all\|reset\|research>` | `slimefun.cheat.researches`| OP | Unlocks or resets a player's researches |
| `/sf charge` | `slimefun.command.charge` | OP | Fully charges whatever item you are holding |
| `/sf backpack <player> <id>` | `slimefun.command.backpack` | OP | Spawns a clone of an existing backpack |
| `/sf teleporter [player]` | `slimefun.command.teleporter` | OP | Opens the GPS waypoint menu |
| `/sf versions` | `slimefun.command.versions` | OP | Lists the server software and all installed addons |
| `/sf update` | `slimefun.command.update` | OP | Forces a check for plugin updates |
| `/sf timings [--avg\|--low] [--verbose]` | `slimefun.command.timings` | OP | Prints a performance summary |
| `/sf debug [test\|disable]` | `slimefun.command.debug` | OP | *Hidden.* Toggles internal debugging tests |
| `/sf debug_fish` | `slimefun.debugging` | OP | *Hidden.* Gives you the Debug Fish |
| `/sf addon ...` | *(See Addons section)* | OP | Manage your installed addons |
| `/sf storage ...` | *(See Storage section)* | OP | Database management, backups, and migrations |
| `/sf checkpoint ...` | *(See Checkpoints section)*| OP | Whole-state snapshot management |

**Player vs. Console:**
- *Player only:* `guide`, `search`, `open_guide`, `cheat`, `charge`, `backpack`, `teleporter`, `debug_fish`, and `/sf storage gui`.
- *Console & Player:* `versions`, `update`, `timings`, `stats`, `give`, `research`, and the rest of the storage tree.
- *Console exclusively:* `/sf checkpoint restore <id>` (This replaces all active data, so we don't let you do it from in-game chat for safety).

---

## `/sf storage` Commands
Manage your database, backups, and migrations. *(See [storage-knowledge.md](storage/storage-knowledge.md) for full details).*

| Command | Needs Admin | Description |
|---|---|---|
| `/sf storage` | No | Alias for `/sf storage info` |
| `/sf storage gui` | No | Opens the massive control panel (Player only) |
| `/sf storage info` | No | Shows database health, ping, and pool usage |
| `/sf storage stats` | No | Shows cache hit rates and save queue depth |
| `/sf storage backup create` | **Yes** | Takes an immediate backup of your player data |
| `/sf storage backup blocks` | **Yes** | Takes an immediate backup of your machines/chests |
| `/sf storage backup restore <id>`| **Yes** | Restores a backup over live data |
| `/sf storage verify` | **Yes** | Checks SQL tables for missing columns |
| `/sf storage repair` | **Yes** | Fixes missing SQL columns |
| `/sf storage migrate ...` | **Yes** | Move data between databases *(See [migration-guide.md](storage/migration-guide.md))* |

*Permissions:* `slimefun.command.storage` (View data), `slimefun.admin.storage` (Run destructive actions).

---

## `/sf checkpoint` Commands
Manage complete server-state snapshots (Database + Files). *(See [checkpoints.md](storage/checkpoints.md) for full details).*

| Command | Needs Admin | Description |
|---|---|---|
| `/sf checkpoint list` | No | Lists all valid checkpoints |
| `/sf checkpoint info <id>` | No | Deep inspection of a specific checkpoint |
| `/sf checkpoint create` | **Yes** | Takes a full database + file snapshot |
| `/sf checkpoint restore <id> confirm`| **Yes** | **Console Only.** Restores the full snapshot |

*Permissions:* `slimefun.command.checkpoint` (View), `slimefun.admin.checkpoint` (Create/Restore).

---

## `/addon` Commands
Manage third-party Slimefun addons without touching your `plugins` folder manually.

Available as both `/addon` and `/sf addon`.

| Command | Permission | Description |
|---|---|---|
| `/addon help` | *None* | Shows the permission-aware addon help menu |
| `/addon list` | *Any addon node* | List installed addons |
| `/addon status` | *Any addon node* | Show cloud-sync status and loaded addons |
| `/addon sync` | `addon.admin` / `addon.update` | Sync the remote index from GitHub |
| `/addon download <name>` | `addon.admin` / `addon.update` | Downloads an addon jar |
| `/addon update <name>` | `addon.admin` / `addon.update` | Updates an installed addon to the latest version |
| `/addon enable <name>` | `addon.admin` / `addon.manage` | Activates a downloaded addon (Requires restart) |
| `/addon disable <name>` | `addon.admin` / `addon.manage` | Disables an addon (Requires restart) |
| `/addon remove <name>` | `addon.admin` / `addon.manage` | Removes a managed addon jar (Requires restart) |

---

## Special Bypass Permissions
These aren't commands, but they let admins bypass standard Slimefun restrictions:

| Permission Node | Default | What it lets you do |
|---|---|---|
| `slimefun.inventory.bypass` | OP | Open any Slimefun machine, even if someone else locked it |
| `slimefun.cargo.bypass` | OP | Edit other players' cargo networks |
| `slimefun.android.bypass` | OP | Edit other players' Programmable Androids |
| `slimefun.gps.bypass` | OP | Open any GPS transmitter |

---

## Secret/Unlisted Features
- **Cargo Grid Monitor:** Right-click a Cargo Manager. It opens a 54-slot dashboard of the entire cargo network. (Sneak + Right Click toggles the old particle visualizer).
- **Auto-Updates:** If `auto-update` is true in `config.yml`, the plugin checks Modrinth for updates on startup. No commands needed.
