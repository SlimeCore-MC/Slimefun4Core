# Migration Guide

Want to move your player data to a new database without losing anything? You're in the right place.
*(For general info on backups, restores, or the `/sf storage` menu, check out [storage-knowledge.md](storage-knowledge.md)).*

## The TL;DR (Four Commands)
Run these in order. You can use the console or in-game chat.

```text
/sf storage migrate targets              # 1. Check if my new database is configured
/sf storage migrate check sqlite         # 2. Dry run (doesn't change anything)
/sf storage migrate start sqlite confirm # 3. The actual migration (server MUST be empty)
/sf storage migrate apply sqlite         # 4. Tell the plugin to use the new database
```
*(Swap `sqlite` with `mysql`, `mariadb`, `postgresql`, or `legacy` as needed).*

**Restart your server** after step 4, and you're done! Read on for the detailed explanation.

---

## 3 Things to Know Before You Start

1. **It's a copy, not a move.** We never delete your old data. If something breaks, your old database is completely untouched. You can always switch back.
2. **Machines and chests are NOT migrated.** Slimefun stores block data in standard YAML files (`data-storage/Slimefun/`), no matter what database you use. There's nothing to migrate for blocks! The plugin will simply back them up during the migration.
3. **Space usage will double temporarily.** Because we keep the old data, make the new copy, and take safety backups, make sure you have enough disk space.

*What actually moves:* Researches, backpacks + contents, and GPS waypoints.

---

## Step 1: Configure Your New Database
Open `plugins/Slimefun/database.yml` and fill in the details for the database you are migrating **to**.
*Do not change the `provider:` line at the top yet!*

```yaml
mysql:
  host: localhost
  port: 3306
  database: slimefuncore
  username: slimefun
  password: "your-password-here"
  ssl: false
```
*Tip: You must create the database (e.g., `slimefuncore`) and the user yourself. Slimefun will automatically create the tables inside it.*

## Step 2: Check Your Config
```text
/sf storage migrate targets
```
This tells you if your `database.yml` has all the required fields filled out. It does *not* actually test the connection yet.

## Step 3: Dry Run
```text
/sf storage migrate check sqlite
```
This is a safe practice run. It reads your current data and pretends to convert it. It will report any corrupted or unconvertible records. 
**You can run this while players are online!** Fix any reported issues before doing the real run, or the real run will abort.

## Step 4: The Real Run
**KICK EVERYONE OFF THE SERVER FIRST!** The migration will abort if someone joins.

```text
/sf storage migrate start sqlite confirm
```
*(You must type the word `confirm`!)*

Here's exactly what happens:
1. It flushes your blocks and takes a `Block archive` backup.
2. It takes a backup of your player data.
3. It copies all player data to the new database in one massive transaction.
4. It double-checks every single copied record against the original.

If anything fails, it rolls back entirely. You'll never be left with a half-migrated database.
*Note: If your target database already has data in it, you'll need to append `overwrite` to the command to clear it first.*

## Step 5: Read the Report
```text
/sf storage migrate status
```
Double check the results. Make sure there are no scary warnings.

## Step 6: Apply the Switch
```text
/sf storage migrate apply sqlite
```
This simply updates the `provider:` line in your `database.yml` to point to the new database. You could also do this by hand in a text editor.

## Step 7: Restart the Server!
Storage engines can't be hot-swapped. Restart your server. Watch the console startup banner to verify it's using the new provider!

## Step 8: Verify In-Game
Log in and check:
- **Researches:** Run `/sf stats` and check your unlocked count.
- **Waypoints:** Open the GPS `/sf teleporter`.
- **Backpacks:** Open one and make sure your items (especially custom items) are intact!
- **Machines:** Open any machine to verify block data is working.

---

## How to Rollback

Since we never modified your original data, rolling back is incredibly easy:
1. Open `database.yml`.
2. Change `provider:` back to your old database (e.g., `legacy`).
3. Restart the server.

You are now back on your old data!

## Troubleshooting

- **`player(s) are online`**: Empty the server.
- **`destination is not empty`**: You already have data in the new database. Add `overwrite` to the end of the `start` command to wipe it.
- **`Machines gone after migrating`**: Machines aren't migrated, they live in files. Make sure you didn't accidentally delete your `data-storage/Slimefun/` folder!
- **`Migrated, but the game shows old data`**: You either forgot Step 6 (apply) or Step 7 (restart).

*(You can also run all of these steps from the `/sf storage gui` using the Minecart icon!)*
