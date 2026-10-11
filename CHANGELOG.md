# Changelog

Release history of **SlimefunCore4**, the unofficial fork. Upstream Slimefun4's own
changelog is not carried here â€” for history before the fork point see
[SlimeCore-MC/Slimefun4Core](https://github.com/SlimeCore-MC/Slimefun4Core/blob/master/CHANGELOG.md).

Versions are identified by the four-digit build number embedded in the jar name
(`SlimefunCore4-<build>.jar`), generated from `.mvn/build-number.properties` as
`YYNN` â€” the build year followed by a per-year counter.

Entries before build 2834 are reconstructed from the repository's commit history and
build metadata; no changelog was maintained at the time, so they are summaries rather
than exhaustive lists.

## Build 2879 — 2026-09-24

Branch `update/paper-26.0.x`. Strengthens the mid-apply rollback added in 2878 so it can never itself
fail; no behaviour change for a successful restore.

### Changed

- **Rename-based transactional storage-restore apply.** `applyPendingStorageRestore` no longer copies
  a full pre-apply snapshot. Instead it renames every live block section aside onto the same filesystem
  as the data root (a cheap rename, no extra disk), unpacks the staged archive into the now-empty root,
  and on any failure deletes the half-written sections and renames the originals back. Because the
  rollback is rename-only it cannot fail for want of disk, so a mid-apply failure (e.g. disk full during
  the per-section replace) can never leave a half-applied block tree. If a move-back still fails, the
  untouched original is preserved in the scratch directory and its exact path is logged for manual
  recovery. Replaces the snapshot-zip rollback from 2878.

## Build 2878 — 2026-09-24

Branch `update/paper-26.0.x`. Hardening from a review of builds 2875–2876; no behaviour change for a
successful restore or a healthy schedule.

### Changed

- **Automatic rollback when a staged storage restore fails mid-apply.** `applyPendingStorageRestore`
  now snapshots the current block tree before the destructive per-section replace and, if the apply
  throws partway (e.g. disk full after some sections are replaced), restores that snapshot instead of
  leaving a half-applied tree. The staging directory is also cleaned up on the failure path, so a
  failed apply no longer orphans `storage-restore-pending/storage.zip`.
- **Lag-proof fixed-time scheduling.** `DailyBackupSchedule` fires on a `(lastCheck, now]` window
  instead of an exact-minute match, so a tick-driven poll stretched by low TPS can no longer skip a
  scheduled UTC time. A large forward clock jump is clamped so it fires once, not once per missed day.
- The storage-engine task fields are now `volatile` for clean cross-thread visibility between the
  async schedulers and shutdown.

## Build 2876 — 2026-09-24

Branch `update/paper-26.0.x`. Lets admins schedule automatic checkpoints and world backups at fixed
UTC clock times instead of only a fixed hour interval.

### Added

- **Fixed-time schedules (`times`) for checkpoints and world backups.** A new `times` list under both
  `checkpoint:` and `world-backup:` in `database.yml` takes `"HH:mm"` entries in **UTC**. When the list
  is non-empty it replaces `interval-hours`: a backup fires as each listed time comes round — e.g.
  `["00:00", "06:00", "12:00", "18:00"]` runs four times a day. An empty list falls back to
  `interval-hours` (unchanged behaviour). Times are polled once a minute and each configured minute
  fires at most once. UTC is used throughout so a server's local zone or DST never shifts the schedule.
- The startup storage banner now shows the active schedule as either `every Nh` or `at HH:mm, … UTC`.

### Notes

- Retention is unchanged: set `checkpoint.max-checkpoints` (checkpoints) or `world-backup.keep` (worlds)
  to the number to keep — e.g. `2`. A new backup is always written and verified before the oldest is
  pruned.



Branch `update/paper-26.0.x`. Fixes a critical defect in the checkpoint **storage** restore: a restore
reported success and asked for a restart, but after the restart the restored block data was gone and the
current data remained — the restore effectively did nothing.

### Fixed

- **Checkpoint storage restore is now applied on startup instead of live.** The storage half was
  previously unpacked over `data-storage/Slimefun` while the server was running. Slimefun holds all
  block data in memory and re-saves it on shutdown (`onDisable` → `BlockStorage.saveAndRemove`), so the
  very restart the restore asked for overwrote the just-restored files with the current in-memory
  state. The restore now **stages** the verified archive into `<dataFolder>/storage-restore-pending/`
  with a marker and defers the write to `CheckpointService.applyPendingStorageRestore(...)`, called
  from `Slimefun.onLoad()` before Slimefun loads block data into memory — the same safe pattern the
  world backup already uses. This is the one point where overwriting the block tree cannot be clobbered
  by a live save. A restart is required to apply a storage restore (as the command already instructed).
- **Failed combined restores no longer partially apply.** When a `both`-scope restore stages the
  storage half and the database half then fails, the staged storage restore is discarded so it does not
  apply on the next startup, and the database half is rolled back on its own.



Branch `update/paper-26.0.x`. Adds an optional **world backup** pool to the checkpoint system, so the
actual Minecraft world folders (overworld, nether, end) can be archived and restored through the same
`/sf checkpoint` command. This is a separate, opt-in pool with its own retention and directory — world
data is never mixed into a checkpoint, so a multi-gigabyte world archive can never bloat the tight
all-or-nothing checkpoint verify. Default **off**: no behaviour changes until an admin enables it.

### Added

- **World backups (`/sf checkpoint worlds`).** A parallel snapshot pool under
  `<checkpoint directory>/worlds/world-NNN/`, each holding one `worlds.zip` plus a `metadata.json`
  (checksum, per-world file counts, capture versions). Capture saves each target world on the main
  thread, then zips the region files off-thread; volatile lock files (`session.lock`, `*.lock`) are
  excluded so a restored world never carries a stale lock. Sub-commands: `worlds` / `worlds list`,
  `worlds create` (admin), `worlds info <id>`, and `worlds restore <id> confirm` (console-only).
- **Safe world restore.** Restore never touches a loaded world: it verifies the archive's checksum,
  refuses to run while any players are online, unpacks the files (zip-slip guarded) into a fixed
  `<dataFolder>/world-restore-pending/` staging directory and writes a marker. The staged worlds are
  applied on the next startup in `Slimefun.onLoad()` — before the server loads its worlds — the one
  point where overwriting world files is safe. A restart is required to apply a world restore.
- **`world-backup` block in `database.yml`.** `enabled`/`automatic` (both default false),
  `interval-hours` (default 6 — four times per day), `keep` (own retention, default 3), and `worlds`
  (empty = auto-detect the primary world and its `_nether` / `_the_end` companions).



Branch `update/paper-26.0.x`. A performance and stability pass driven by spark profiles from a busy
production server (cargo networks and androids ticking heavily, heap under pressure), plus a crash fix
for servers upgrading from an older Slimefun with an existing `sounds.yml`. Every performance change
below does strictly less work for an identical result — no cargo/android behaviour was altered.

### Fixed

- **Guide page-turn (and any other) sound no longer crashes on newer server versions.** A `sounds.yml`
  written by an older Slimefun stored the Bukkit `Sound` **enum name** (e.g. `ITEM_BOOK_PAGE_TURN`)
  instead of a namespaced key. Because `setDefaultValue` never overwrites an existing value, that
  legacy string survived every restart and was handed straight to `playSound(Location, String, ...)`,
  which on MC 26.x rejects uppercase/underscore ids and threw an `IdentifierException` on every guide
  page turn (during `PlayerRightClickEvent`). `SoundService` now validates each id at load time and
  translates a legacy enum name back to its registry key (`ITEM_BOOK_PAGE_TURN` → `item.book.page_turn`),
  falling back to the built-in default if it cannot be resolved. Valid modern configs are unchanged.
- **`deferredInventories` no longer retains entries for destroyed or moved blocks.** A stored
  inventory whose `BlockMenuPreset` was not yet registered is parked in `deferredInventories` until a
  later reconciliation. When such a block was broken it was removed from block storage but its
  deferred entry lingered; the entry is now dropped on destroy and carried across on move.
- **The automatic error reporter no longer floods Discord with addon-compatibility noise.** Linkage
  errors (`NoClassDefFoundError` / `NoSuchMethodError` / `ClassNotFoundException` — an addon built
  against a different Slimefun API) and plugin enable/disable lifecycle failures are now skipped:
  they recur on every restart, are already printed to the console, and are not fixable in the core.
  Genuine runtime `SEVERE` errors (ticking, storage, listeners) still report.
- **A hard daily quota bounds the auto-reporter.** In addition to the existing 1-hour per-signature
  dedup and 20-per-10-minute burst cap, a server now posts **at most 2 automatic reports per rolling
  24 hours**. The counter is in-memory, so a restart opens a fresh day.

### Performance

- **`BlockStorage.getRawStorage()` no longer rebuilds a full world snapshot on every call.** It was
  the single largest server-thread slice (~16%): each call walked every stored block, allocated a
  fresh `Location` per entry, filled a `HashMap`, then wrapped it in a Guava `ImmutableMap`. A
  per-tick caller (an addon) turned that into an allocation storm. The result is now cached and
  rebuilt lazily only when the block set actually changes (a key is added, removed, or its `Config`
  object is swapped); in-place edits to an existing block reuse the same snapshot. Verified against
  all four mutation sites (load, `setBlockInfo`, delete, move).
- **Cargo stops building a tile-entity snapshot for every vanilla container touch.** The shaded
  PaperLib resolves to a "before snapshots" environment on this server, so `getBlockState(block,
  false)` ignored the flag and always deep-copied the container (`CraftBlock.getState()` was ~5% of
  the cargo tick). The three cargo hot paths now call `Block.getState(false)` directly for a
  live-backed state and read only its `InventoryHolder` inventory — identical behaviour, no copy.
- **Cargo filter tests no longer allocate a debug-argument array on every item.** `ItemFilter.test`
  and `CargoUtils.insert` built a varargs `Object[]` for a debug message once per item per node per
  tick, even with debug logging off. Both are now guarded behind a cheap `Debug.isEnabled()` check
  (which mirrors exactly what the logger checks internally), removing the allocation while debug is
  off and behaving identically when it is on.
- **Cargo distribution reuses the `ItemStackWrapper` across output nodes.** `distributeItem` built a
  fresh wrapper (which clones the `ItemMeta` — the expensive `CraftItemStack#getItemMeta`) for every
  destination, even when an output rejected the item by filter or was full and left the stack
  untouched. The wrapper is now rebuilt only when the stack's amount actually changes since it was last
  wrapped, so it still reflects the current amount that presets like `HeatedPressureChamber` read during
  slot selection — routing behaviour is byte-for-byte identical, with far fewer meta clones.

### Investigated (no change)

- **Cargo item routing (`matchesFilter` / `isItemSimilar`) and the Woodcutter/Programmable androids
  (`chopTree` → `Level.setBlock`) are legitimate tick work, not leaks or waste.** The cargo filter is
  already cached per node and does a cheap material pre-check before touching `ItemMeta`; the android
  cost is real vanilla block placement. There is no safe code change that reduces these without
  altering behaviour — they scale with node/android count and are tuned via `cargo-ticker-delay` and
  server load, not code.
- **A `distributeItem` wrapper hoist was prototyped and reverted.** Wrapping the moving item once
  outside the output loop would have frozen its stack amount; some machine presets (e.g.
  `HeatedPressureChamber`) read that amount through an amount-checking `isItemSimilar` during slot
  selection, so the hoist could change slot selection after a partial insert. The safe amount-tracked
  reuse above was implemented instead.

## Build 2860 — 2026-09-19

Branch `update/paper-26.0.x`. An in-game reporting pipeline that reaches Discord, automatic
error detection, and an interactive addon manager inside the guide. Everything here is
dev-branch work layered on top of build 2836.

### Added

- **`/sf report` — a guided, five-step in-game bug report that posts to Discord.** A player
  runs the command, picks a category (`Bug`, `Addon Conflict`, `Griefing`, `Performance`,
  `Other`), then answers in chat: what happened, an optional mclo.gs log link, an optional
  Discord username so staff can reach them, and whether they are a player or the owner/staff.
  The chat input is captured and never broadcast. A confirmation summary is shown before
  anything is sent, and `cancel` at any step aborts. Backed by `ReportSession`,
  `ReportSessionManager`, `ReportTicket`, `ReportCategory` and `DiagnosticsCollector`.
- **Reports are delivered as a rich Discord embed** through the report webhook, in forum-thread
  mode. The embed carries the reporter, Discord handle, role, description, the mclo.gs link, an
  auto-uploaded server log, and a diagnostics block (server version, build and branch, Java
  version, RAM, online players and the installed addon list). Colours, emoji field labels, a
  timestamp and a footer are applied so a report is readable at a glance.
- **Automatic error reporting — no manual action required.** `AutoErrorReporter` attaches a
  `SEVERE`-level handler to the root logger. When Slimefun core (`io.github.thebusybiscuit.slimefun4`,
  `me.mrCookieSlime`) or any installed addon logs an error carrying a stack trace, an automatic
  report is built from server diagnostics and the throwable and posted to the same webhook under
  the `Automatic Error Report` category. Addon origin is resolved live against
  `Slimefun.getInstalledAddons()`, so newly loaded addons are covered.
- **The auto-reporter cannot flood Discord or loop on itself.** Identical errors are de-duplicated
  by a stable `exception|class#method:line` signature within a one-hour window, a global cap of 20
  reports per ten-minute window bounds a crash loop, expired signatures are pruned, and a
  thread-local re-entrancy guard means the reporter never reports its own failures. It never throws
  out of a logging call.
- **An interactive Addon Manager inside the guide settings.** Opening the guide → settings (gear)
  now shows an **Addon Manager** tile in slot 49 for any player holding `addon.admin`,
  `addon.update` or `addon.manage` (OPs included); players with none see blank background. It opens
  a paged `ChestMenu` ([AddonGuideMenu](src/main/java/io/github/thebusybiscuit/slimefun4/core/guide/options/AddonGuideMenu.java))
  mirroring `/sf addon`: each managed addon is listed with a state icon (loaded, jar present,
  missing, incompatible) and status lore, a detail screen exposes Download / Check-for-update /
  Enable / Disable / Remove plus the configured version "folders", and a **Sync from GitHub** button
  runs `GitHubSyncService.syncNow()` on demand.
- **A community Discord invite and `/sf report` reminder on join**, boxed and colourised, gated
  behind `monitor.invite.broadcast-on-join`. Staff (op or `slimefun.monitor.notify`) get an extra
  support line. This also fixes the previously dead config key — the sink now actually reads it.

### Changed

- **The report system contacts reporters by Discord. ** The session flow collects a Discord
- **Project links repointed to the fork.** `Slimefun/Slimefun4` references across the source,
- **Console and in-game report notices are readable**, not raw object dumps: the console sink and
  the in-game announce sink render a report as `id [category] from name` rather than a `toString()`.


### Fixed

  logging that rendered as `âœ¦`-style garbage were corrected or stripped; log lines no longer emit
  broken glyphs. (The historical CHANGELOG entries below still carry the old mojibake and are a
  separate cleanup.)
- **Literal `\n` in the Discord addons field.** The installed-addon list in the report embed
  interpolated an escaped newline that showed up verbatim; it now uses a real line break per addon.

### Security notes

- The Addon Manager GUI adds **no new privileges**. Every action button re-checks the same
  `addon.*` node the `/sf addon` command enforces, at click time, so a stale open menu cannot
  escalate. It introduces no new remote code path — it only calls the already-shipped
  `AddonService` operations and `GitHubSyncService.syncNow()`.
- Addon cloud-sync and the Modrinth updater still fetch and stage jars without signature
  verification; that pre-existing gap is unchanged by this build.

### Known issues

- **Report and auto-report delivery is unverified end-to-end.** The webhook post is fire-and-forget
  (`sendAsync` with a discarded response body), so a failed delivery is not surfaced beyond a warning
  log; success is not confirmed against Discord.
- The report webhook URL and Discord invite are hardcoded in `ReportSessionManager`; only the
  report webhook is overridable via `monitor.discord.report-webhook-url`.

## Build 2836 | 2026-09-06

Branch `update/paper-26.0.x`. Checkpoints: one command that captures, verifies and restores the
whole Slimefun state as a single coherent unit.

### Added

- **`/sf checkpoint` â€” a checkpoint is one complete Slimefun backup state.** It holds two
  independent halves under one `checkpoint-NNN` directory and they are always created,
  verified, kept and restored together:

  ```
  plugins/Slimefun/slimefun-checkpoints/
      checkpoint-001/
          storage/storage.zip
          database/database-backup.zip
          metadata.json
  ```

  The storage half is a byte-exact ZIP of the block and machine sections under
  `data-storage/Slimefun`; the database half is database-owned player data only. **Neither half
  ever contains Minecraft world data** â€” no `region/*.mca`, no `level.dat`, no `entities/`, no
  `playerdata/`, no other plugin's files. The captured section list comes from the storage
  implementation itself, not from a hand-written guess.
  Full runbook: [docs/storage/checkpoints.md](docs/storage/checkpoints.md).
- **Independent checkpoint restore scopes.** Console administrators can restore only the
  file-based storage half with `restore storage <id>`, only player data with
  `restore database <id>`, or both halves with `restore <id> confirm`. Every scope refuses while
  players are online, verifies only its requested component, takes a scoped safety copy, and
  rolls back only the component it changed. Checkpoint history remains untouched.
- **Six forms:** `create`, `list`, `info <id>`, `status`, `restore <id>` (preview) and
  `restore <id> confirm`. `<id>` accepts `3`, `003` or `checkpoint-003`. Two new permission
  nodes in `plugin.yml`: `slimefun.command.checkpoint` for `list`, `info` and `status`, and
  `slimefun.admin.checkpoint` for `create` and `restore`.
- **Creation is all-or-nothing.** Dirty player profiles and block data are flushed first, both
  halves are captured, `metadata.json` is written and then **re-parsed**, and every captured
  half is checksum-verified. If any step fails the incomplete directory is deleted before the
  command returns, so `list` can never show a half-valid state â€” and no old checkpoint is
  deleted. A half may legitimately come back `SKIPPED` (an empty tree, a provider with no
  player data); a `FAILED` half is fatal.
- **Retention deletes the oldest last, never first.** On the eleventh checkpoint with
  `max-checkpoints: 10`, the new one is written, checksummed and re-parsed *before* the oldest
  is removed. One checkpoint is always kept whatever the limit says, and a deletion failure is
  logged with the id instead of being swallowed.
- **`restore` is console-only.** It replaces every Slimefun file and every database-owned
  record at once, so a player is refused even with `slimefun.admin.checkpoint`, and it is not
  reachable from a command block or the GUI. It also refuses to run while any player is online.
  Ten ordered stages, each reported as `n/10`: locate, validate metadata, verify the storage
  checksum, verify the database half, confirm the server state, take a safety copy of the
  current state, restore storage, restore the database, re-read every block file, report.
  There is no silent partial restore.
- **Both halves go back together, or neither does.** A failure after the storage half was
  written rolls both halves back from the safety copy under
  `slimefun-checkpoints/safety/pre-restore-<timestamp>/`; if a rollback itself fails the report
  names the copy so it can be restored by hand. `keep-safety-backups` defaults to `3`.
- **Restoring an old checkpoint never deletes newer ones.** Only the active Slimefun data
  changes; the report prints how many checkpoints are still on disk afterwards.
- **`BackupFormat.PLAYER_FILES`** â€” a byte-exact ZIP of `Players` and `waypoints` only, the
  database half of a checkpoint on Legacy YAML. It is the mirror image of `BLOCK_FILES`, so the
  two halves of a checkpoint never overlap and a player restore cannot disturb block data.
  `BackupMetadata` is unchanged and `fromName` was already lenient, so existing `.meta.json`
  files still parse.
- **The `checkpoint` block in `database.yml`**, eight keys: `enabled`, `max-checkpoints`,
  `directory`, `automatic`, `interval-hours`, `only-when-changed`, `compress` and
  `keep-safety-backups`. Documented in
  [docs/configuration.md](docs/configuration.md).
- **Change detection** marks the storage and database halves dirty and reports
  `Storage changed / Database changed / Checkpoint required` through `/sf checkpoint status`
  and the GUI. It never creates a checkpoint on its own.
- **The checkpoint screens live inside the existing storage panel**, not in a second GUI:
  `/sf storage gui` â†’ `Checkpoints`, with a paged browser, per-checkpoint detail, the change
  status, one-click create and a `Checkpoints` page in the in-game guide. Restore is
  deliberately absent from the GUI. The database password is never rendered.

### Changed

- `CheckpointService` reuses `StorageConsistencyChecker.scanBlockFiles(File)` as stage 9 of a
  restore, so a damaged storage half is caught at restore time rather than surfacing later as
  a missing machine. The scan is unconditional there and does not depend on
  `storage.consistency-check-scan-blocks`, which governs the periodic checker.
- `TestBlockBackupRetention.everyFormatIsADistinctBucket` now seeds `PLAYER_FILES` as well. It
  asserts across `BackupFormat.values()`, so a new constant with no seeded backups failed the
  test; retention itself was correct and is unchanged.
- `docs/storage/storage-knowledge.md` gains `PLAYER_FILES` in the format table and a
  checkpoint section contrasting backups with checkpoints; `docs/commands.md` gains the
  `/sf checkpoint` tree and its two permission nodes.

### Tests

- Three new Bukkit-free JUnit 5 classes, 26 tests: `TestCheckpointMetadata` (14) on the
  document that is the sole authority on restorability, `TestCheckpointLayout` (4) on the
  on-disk shape against real temp files, and `TestPlayerFileBackup` (8) on the
  storage-versus-database boundary. The storage suite is now 103 tests, all passing with
  `TestLegacyBackend` excluded as the known MockBukkit-versus-Paper-26 casualty.

### Known issues

- **The storage half is only as fresh as the last flush.** Creation flushes dirty profiles and
  block data first, but if the block flush fails the step list says so and the half may be
  slightly older than the moment the command ran.
- **A checkpoint roughly doubles the space its data occupies**, so ten of them are roughly
  eleven-fold. Check free space before raising `max-checkpoints`.
- **Backpack item contents pass through Bukkit's `ItemStack` serialization**, which this
  repository's Bukkit-free test scope cannot exercise. Verify by hand after a restore.
- **No cross-version guarantee.** Metadata records the Slimefun and server version a checkpoint
  came from; restoring onto a different one is untested.

## Build 2835 â€” 2026-09-04

Branch `update/paper-26.0.x`. The storage migration layer, which existed as dead code, now
works.

### Added

- **`/sf storage migrate` â€” data actually moves between providers.** Legacy YAML to any SQL
  provider, any SQL provider to any other, and SQL back to Legacy YAML. Six forms:
  `targets`, `check <target>`, `start <target> confirm [overwrite]`, `status` and
  `apply <target>`. Reading needs `slimefun.command.storage`; everything that writes needs
  `slimefun.admin.storage`. No new permission node and no `plugin.yml` change.
  Full runbook: [docs/storage/migration-guide.md](docs/storage/migration-guide.md).
- **A real run is refused unless the server is empty**, and the online count is read a
  second time after the source has been read and archived, so a player who joins mid-run
  stops the copy before the destination is ever opened. A dry run (`check`) is deliberately
  allowed with players online â€” it opens no destination and writes nothing â€” and the report
  then carries a warning that its counts are a live reading.
- **The migration never routes through `PlayerData` or `loadPlayerData`.** Both backends
  silently drop an unknown research id, a backpack size outside `{9,18,27,36,45,54}`, an
  item that will not deserialize and a waypoint in an unloaded world while loading. A
  migration built on that would have shrunk player data and still verified, because both
  sides drop the same rows. Records are read and written directly as raw ints, raw bytes and
  raw world names by `PlayerRecords`, `SqlRecordStore`, `LegacyRecordStore` and
  `SqlTableCopier`. `SQL â†’ SQL` copies `item_data` byte-for-byte with no string round trip.
- **Two verified backups before the first write:** the source's player data and a
  `BLOCK_FILES` block archive, both checksum-verified, both taken with `keepLast = 0` so
  retention can never prune them. A failed backup aborts the migration.
- **`BackupFormat.BLOCK_FILES`** â€” block data is finally backed up on SQL providers, where
  `createBackup` previously only ever chose `SQL_JSON` and left `data-storage/Slimefun`
  untouched. Available on demand as `/sf storage backup blocks`, listable, verifiable,
  restorable and deletable through the existing `backup` subcommands and the backup browser.
  `BackupMetadata` is unchanged and `fromName` was already lenient, so every existing
  `.meta.json` still parses.
- **A block restore is now checked.** After extraction every block file is re-read, and the
  restore reports failure with the offending paths if any of them no longer parses.
- `PlayerIndex` â€” player enumeration over both backend kinds, the primitive whose absence
  was the actual reason `StorageMigrator` had no caller. A legacy filename that is not a
  UUID is reported, never silently ignored.
- `MigrationPreconditions`, `MigrationTargets`, `MigrationReport` and `MigrationException`,
  the last carrying uuid, section and column or path so a failure names the record it broke
  on rather than printing a bare stack trace.
- `storage.consistency-check-scan-blocks` in `config.yml`, the key
  `StorageConsistencyChecker` already read but which was never documented or shipped.
- A `Migrate storage` tile on `/sf storage gui` opening a migration screen, and a
  `Moving your data` page in the in-game guide. The database password is never rendered;
  credentials show as presence only.

### Changed

- `SqlDialect` extracted from `DatabaseRestoreService`'s private helpers â€” identifier
  quoting, typed value binding and the column classifiers â€” so restore and migration cannot
  drift apart. Behaviour is unchanged; the restore path delegates.
- `AtomicFiles` extracted from `LegacyStorage.atomicSave` for the same reason. Semantics are
  unchanged; `LegacyStorage` delegates.
- `StorageConsistencyChecker` no longer starts a repeating task as a constructor side
  effect. `startScheduler()` is an explicit call, and the block parse check is exposed as
  the static `scanBlockFiles(File)` used after a migration and after a block restore.
- `docs/configuration.md` no longer states that switching `provider` has no migration path.

### Removed

- `StorageFactory`. It was superseded, not merely unused: it read the deprecated
  `storage.backend` key that `StorageEngineService` already migrates away from, knew only
  legacy and SQLite, and logged *"MySQL storage is not implemented yet"*. Reviving it would
  have put a second, wrong provider factory next to `StorageProviderFactory`. It had no
  callers.

### Known issues

- Block data is still not migrated, because there is nothing to migrate: `BlockStorage` was
  never moved to the storage layer and lives in YAML under `data-storage/Slimefun` on every
  provider, MySQL included. Migration flushes and archives it, never converts it. Owners
  who expect switching to MySQL to relocate their machines are told so by the command, the
  GUI and the guide.
- `/sf storage migrate targets` reports configuration readiness from `database.yml`, not
  reachability. Opening a provider commits DDL, so nothing is opened until a real run.
- The legacy item leg â€” YAML `ItemStack` to `serializeAsBytes()` and back â€” needs Bukkit and
  cannot be covered by this repository's Bukkit-free test scope. It is verified by hand;
  see step 4 of the guide's verification section.

## Build 2834 â€” 2026-09-04

Branch `update/paper-26.0.x`. Storage and backup repair, plus a control panel.

### Fixed

- **Backups no longer report success after failing.** On the shipped default
  `provider: legacy` the backup task could not obtain a JDBC connection, swallowed the
  failure, wrote an empty `{"tables": {}}` dump, checksummed it, and logged success.
  The failure now propagates and no file is written.
- **Retention no longer destroys real backups.** The purge pass previously ran even after
  a failed or empty backup and deleted a genuine archive to stay under `keep-last`. It now
  skips a failed or empty run, orders by recorded creation time rather than file
  modification time, ignores unparseable metadata, and never reduces the store to zero
  valid backups.
- **Legacy YAML can be backed up at all.** New byte-exact ZIP archive of the live data
  tree covering `Players`, `waypoints`, `stored-blocks`, `stored-inventories`,
  `universal-inventories` and `stored-chunks/chunks.sfc` â€” player data and block data.
- **Restore no longer corrupts what it restores.** Identifier quoting is now dialect
  correct (backticks on MySQL and MariaDB, where the previous double quotes were parsed as
  string literals); values are bound by declared column type, so the `item_data` blob
  holding serialized backpack items is written with `setBytes` instead of `setString`; the
  missing JSON unescape is implemented, so names and lore containing `"` or a newline
  survive; the brace and bracket scanners are string-literal aware, so a `}` or `]` inside
  a value no longer truncates the parse; the column list is built from the union of all
  rows rather than the first row alone; and the cache is invalidated after a successful
  commit.
- **MariaDB no longer reports a false integrity failure on every boot.** The reflective
  lookup used `getDeclaredMethod("getConnection")`, which misses a method inherited from
  `MySQLProvider`.
- **Schema verification actually runs.** `SchemaManager.verify` was fully implemented but
  unreachable; the old code path reflected for a method it never called and then set
  `integrityPassed = true` unconditionally.

- **`EXPLOSIVE_BOW` threw on every arrow hit** on Paper 26.2.
  `CraftDamageSourceBuilder.build()` now rejects a causing entity with no direct entity.
  The arrow is the direct entity and its shooter the causing entity.
- **`SEISMIC_AXE` carried the same defect**, latent because the damage source was only
  built when the axe was swung. Fixed alongside it.
- **`Remote addonmanager.yml sync failed: null`.** The log line interpolated
  `getMessage()`, which is null for the exception being thrown. It now reports the
  exception class, the root cause and the HTTP status where there is one.
- The crash reporter linked to upstream's issue tracker for fork-only faults.
- Four `sendMessage` calls in the storage command ran off the main thread.

### Added

- `/sf storage gui` â€” a 54-slot control panel: live provider health, a paged backup
  browser with inspect, verify, restore and delete, a database setup wizard, and an
  in-game guide. Opening needs `slimefun.command.storage`; every destructive button needs
  `slimefun.admin.storage`, re-checked at click time. No new permission node or
  `plugin.yml` change was required.
- `StorageProvider.supportsSql()` and `borrowConnection()`, replacing three copy-pasted
  reflective walks of a private `dataSource` field.
- `BackupFormat` (`SQL_JSON`, `LEGACY_FILES`), recorded in `BackupMetadata` so a backup can
  never be restored into the wrong kind of backend. `BackupMetadata` keeps a 7-argument
  constructor defaulting to `SQL_JSON`, so metadata already written to disk still parses.
- `StorageCache.invalidateAll()`, which also repairs `/sf storage cache clear` â€” it
  previously only evicted players who happened to be online.
- Zip-slip rejection and a staged extract on the legacy restore path, plus an automatic
  pre-restore snapshot.
- First tests for the storage layer: 18 JUnit 5 tests under
  `src/test/java/.../storage/backup/`, covering the failed-backup-does-not-purge sequence,
  legacy archive completeness, a real SQLite round trip with a blob and a value containing
  quotes, newlines and braces, zip-slip rejection, and retention behaviour. All are
  Bukkit-free, because the test scope still resolves a 1.21 MockBukkit against a 26.x API.

### Changed

- The startup banner tells the truth on file storage: `Tables: n/a (file storage)` and
  `Integrity: Not applicable (Legacy YAML)` instead of `Passed` beside `0 verified`.
- The scheduled backup task records its real outcome and stops logging
  `Automatic backup completed successfully.` when it did not succeed.
- Documentation rewritten to match the 26.x target: `README`, this changelog,
  `docs/adr/0001-storage-layer.md`, `docs/sop/update.md`, and new command, configuration
  and storage references under `docs/`.

### Known issues

- `pool.leak-detection-threshold: 0` is documented as "disabled" but is read through a
  getter that maps `<= 0` to the default, leaving the guard dead on every SQL backend.
- `StorageMigrator`, `StorageConsistencyChecker` and `StorageFactory` are never invoked.
  (Resolved in build 2835: the migrator is live, the checker's block scan is used by the
  migration and restore paths, and `StorageFactory` was removed.)
- Backpack persistence is still untested through the legacy backend.
- The POM targets Paper 26.1.2 while 26.2 servers are in use.

## Build 2800 series â€” 2026-06-19

The 26.x port. Branch `update/paper-26.0.x` was cut from `main` on this date.

- `paper.version` raised to 26.1.2, `plugin.yml` `api-version` to `26.1`, Java release to 25.
- `MinecraftVersion.MINECRAFT_26` added for Minecraft's year-based `YY.D[.H]` scheme,
  deliberately matching any drop under year 26.
- Addon manager caching improved.
- Builds for 1.21.x remain on their own branch.

## 2026-05-19 â€” SlimefunCore4 improvements

General improvements carried into the 2800 line.

## 2026-05-05 â€” Bug fixes and new features

Includes the multi-backend storage engine, the addon manager and the Cargo Grid Monitor.
None of this work was recorded in a changelog at the time; the entry is reconstructed from
commit messages.

## 2026-04-28 â€” Storage layer ADR

`docs/adr/0001-storage-layer.md` carried over from upstream.

## 2026-04-07 â€” Unofficial updater

- Working update check for unofficial builds, with improved startup update logging.
- Crucible and Composter guide recipes fixed for Paper and Purpur 1.21.1 through 1.21.11
  on Java 21.

## Build 2605 â€” 2026-04-06

- Jar names switched to the four-digit `SlimefunCore4-YYNN.jar` build code, auto-incremented
  and persisted per year.
- bStats kept embedded and correctly relocated in the shaded jar.
- Unofficial builds began checking the GitHub release page and logging a console notice
  when a newer release exists. This later became the Modrinth check on project
  `slimefuncore`.
- Duplicate `dough-api` dependency declaration removed from `pom.xml`.

## 2026-04-04 â€” Initial fork

Forked from Slimefun4 at a 1.19.4 base and brought up to a Purpur and Paper 1.21.11 build
on Java 21, published as `4.0-UNOFFICIAL`.


