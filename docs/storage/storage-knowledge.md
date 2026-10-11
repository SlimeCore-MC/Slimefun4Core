# Storage Knowledge

Welcome to the Slimefun storage guide! This covers everything under the `/sf storage` command tree: checking database health, taking backups, restoring data, and configuring your database. 

Everything here can be done via console commands or through a handy in-game GUI.

## Quick Start Cheat Sheet

| I want to... | Use this command |
|---|---|
| Open the control panel | `/sf storage gui` (In-game only) |
| Check database health | `/sf storage info` |
| Take a backup immediately | `/sf storage backup create` |
| Back up machines/chests | `/sf storage backup blocks` |
| Take a full snapshot | `/sf checkpoint create` *(See [checkpoints.md](checkpoints.md))* |
| Move to a new database | `/sf storage migrate ...` *(See [migration-guide.md](migration-guide.md))* |
| Change databases (empty) | Use the GUI Database setup, or edit `database.yml` and restart |

---

## Permissions
- `slimefun.command.storage`: Lets you view stats and open the GUI.
- `slimefun.admin.storage`: Lets you actually break things (backups, restores, migrations, repairs).

*Note: Admin rights are checked the moment you click a button in the GUI, not when you open it. If you revoke an admin's perms, they immediately lose access to destructive buttons.*

---

## The Control Panel (`/sf storage gui`)

Type `/sf storage gui` in-game to open a 54-slot dashboard. 

**Top Row (Read-Only Health Stats):**
- **Slot 4:** Your active database provider.
- **Slot 10-12:** Connection status, ping, and active connection pool usage.
- **Slot 13:** Cache hit rates.
- **Slot 14:** Schema integrity (are your SQL tables healthy?).
- **Slot 15-16:** Backup history and disk usage.

**Bottom Row (Actions):**
- 🛒 **Minecart (27):** Migrate data to a new provider.
- 📦 **Chest Minecart (28):** Take a new backup right now.
- 📖 **Book (29):** Browse, restore, or delete backups.
- 🔨 **Anvil (30):** Database setup wizard.
- 🔮 **Enchanted Book (31):** Open the built-in guide.
- 🔭 **Spyglass (32):** Verify SQL schema integrity.
- 🪣 **Bucket (33):** Clear the memory cache.
- 🧭 **Compass (34):** Refresh the menu stats.

---

## Backups

Backups are strictly typed. You can never accidentally restore a SQL backup into a Legacy YAML system.

### The 4 Backup Formats
1. **`SQL_JSON`**: A full JSON export of all 5 Slimefun SQL tables. Taken automatically on SQL backends.
2. **`LEGACY_FILES`**: A raw ZIP of the YAML data tree. Taken automatically on the Legacy backend.
3. **`BLOCK_FILES`**: A raw ZIP of block data only (machines/chests). Can be requested on any backend via `/sf storage backup blocks`.
4. **`PLAYER_FILES`**: A ZIP of player data only. Used exclusively during checkpoints on Legacy systems.

**Important Note on Block Data:**
Even if you use MySQL, your machines and chests are STILL saved as YAML files in `data-storage/Slimefun/`. The automated `SQL_JSON` backup **does not back them up**. You should periodically run `/sf storage backup blocks` to secure your block data!

### Automatic Backups
Configure these in `plugins/Slimefun/database.yml`:
```yaml
backup:
  enabled: true
  automatic: true
  interval-hours: 6
  keep-last: 20
  compress: true
  directory: data-storage/Slimefun/db-backups
```
The plugin is smart about retention (`keep-last`). It will never delete old backups if a new backup fails to generate, and it will never prune your backups down to zero.

---

## Restoring Data

**WARNING: Restoring overwrites live data. Kick all players off the server before doing this!**

You can restore via the GUI (Shift + Left Click a backup) or via console:
```text
/sf storage backup list
/sf storage backup restore <id>
```

**How it protects you:**
- **SQL Restores:** Runs in a single transaction. If it crashes halfway, nothing is changed.
- **File Restores (Legacy/Blocks):** Takes a pre-restore snapshot of your files first. It unpacks the ZIP safely and aborts if it detects malicious paths (Zip-Slip protection). Block restores will actually dry-run parse the restored files before applying them, meaning corrupted backups are caught before they break your server!

---

## Checkpoints vs. Backups

- **Backups** save one piece of data (e.g., just SQL, or just Blocks).
- **Checkpoints** take a snapshot of your *entire server state* (SQL data + Block files) as a single matched pair. 

Checkpoints are perfect for taking a "save state" before you do a risky plugin update or data migration.
Read the full runbook in [checkpoints.md](checkpoints.md).

---

## Database Setup

You can set up a new database via the GUI (The Anvil icon) or by editing `database.yml`. 

*Note: For security, your database password is NEVER shown in the GUI, NEVER sent in chat, and NEVER logged. You MUST edit `database.yml` manually to set the password.*

### Example Config Block
```yaml
provider: legacy            # Options: legacy, sqlite, mysql, mariadb, postgresql

mysql:
  host: localhost
  port: 3306
  database: slimefuncore
  username: root
  password: "your_password" # <-- Edit this!
  ssl: false
```

**To apply a new database:**
1. Edit the config or use the GUI wizard.
2. Restart the server. Storage engines cannot be hot-swapped!

*(Reminder: Changing your database provider does NOT migrate your data! It just starts a fresh, empty database. Use the [Migration Guide](migration-guide.md) if you want to keep your data.)*

---

## Troubleshooting FAQ

**"I see `Integrity: n issue(s) found` in the GUI!"**
Your SQL tables are missing columns. Run `/sf storage repair` then `/sf storage verify`.

**"My backup says `CHECKSUM MISMATCH`!"**
The backup file was modified or corrupted after it was created. Do not attempt to restore it.

**"I get `SQL dump` or `file archive` rejected when restoring!"**
You are trying to restore a SQL backup into a Legacy system, or vice versa. This is blocked to prevent catastrophic data corruption.

**"I migrated, but my machines are gone!"**
Machines are never migrated because they are always stored as files! Make sure you didn't accidentally delete the `data-storage/Slimefun/` folder on your server. Restore a `Block archive` backup if you lost them.
