# Dev changelog — builds 2836 + 2860 (combined)

Branch `update/paper-26.0.x`. This is a combined view of the dev-branch work in builds **2836**
and **2860**, grouped by theme rather than by build. For the per-build breakdown see
[CHANGELOG.md](../CHANGELOG.md).

- **Build 2836 (2026-09-06)** — `/sf checkpoint`: one command that captures, verifies and
  restores the whole Slimefun state as a single coherent unit.
- **Build 2860 (2026-09-19)** — in-game reporting to Discord, automatic error detection, and an
  interactive addon manager inside the guide.

---

## New features

### Reporting & automatic error detection (2860)

- **`/sf report`** — a guided, five-step in-game bug report (category → description → optional
  mclo.gs link → optional Discord username → player/owner role), with a confirmation summary and a
  `cancel` escape at every step. Chat input is captured, never broadcast.
- **Discord delivery** — reports post to the report webhook as a rich embed in forum-thread mode,
  carrying the reporter, Discord handle, role, description, mclo.gs link, an auto-uploaded server
  log, and a diagnostics block (server version, build/branch, Java, RAM, players, installed addons).
- **Automatic error reports** — `AutoErrorReporter` attaches to the root logger and posts a report
  whenever Slimefun core or an installed addon logs a `SEVERE` error with a stack trace. No player
  action needed. Origin is resolved live against the installed-addon list.
- **Flood/loop protection** — per-signature dedup (1h window), a global cap (20 per 10 min),
  expired-signature pruning, and a re-entrancy guard so the reporter never reports itself.
- **Join broadcast** — a boxed community Discord invite plus a `/sf report` reminder on join, gated
  behind `monitor.invite.broadcast-on-join`, with an extra staff line for ops.

### Interactive Addon Manager in the guide (2860)

- **Guide → settings → slot 49** now opens a paged Addon Manager mirroring `/sf addon`: per-addon
  state icons and status, a detail screen with Download / Check-for-update / Enable / Disable /
  Remove and the configured version "folders", and a **Sync from GitHub** button.
- Visible only to players holding `addon.admin`, `addon.update` or `addon.manage` (ops included);
  everyone else sees blank background. Buttons re-check their node at click time. No new privileges,
  no new remote code path.

### Checkpoints (2836)

- **`/sf checkpoint`** — a checkpoint is one complete Slimefun backup state: a storage half (a
  byte-exact ZIP of the file-based data tree) and a database half (database-owned player data only)
  under one `checkpoint-NNN` directory, always created, verified, kept and restored together.
  **Neither half ever contains Minecraft world data.**
- **Six forms:** `create`, `list`, `info <id>`, `status`, `restore <id>` (preview) and
  `restore <id> confirm`; two permission nodes (`slimefun.command.checkpoint`,
  `slimefun.admin.checkpoint`).
- **Independent restore scopes** — restore only storage (`restore storage <id>`), only the database
  (`restore database <id>`), or both (`restore <id> confirm`). Each refuses while players are online,
  verifies only its component, takes a scoped safety copy and rolls back only what it changed.
- **All-or-nothing creation** — profiles and block data are flushed, both halves captured,
  `metadata.json` written and re-parsed, every half checksum-verified; a failed step deletes the
  incomplete directory and keeps every existing checkpoint.
- **Safe retention** — the oldest is deleted only after the new checkpoint is written, checksummed
  and re-parsed; one checkpoint is always kept.
- **Console-only restore** in ten ordered, reported stages, with a pre-restore safety copy under
  `slimefun-checkpoints/safety/`; both halves go back together or neither does, and restoring an old
  checkpoint never deletes newer ones.
- **`BackupFormat.PLAYER_FILES`**, the `checkpoint` block in `database.yml` (eight keys), change
  detection surfaced through `/sf checkpoint status` and the GUI, and checkpoint screens inside the
  existing `/sf storage gui` panel and the in-game guide.

---

## Changed

- **Reporters are contacted by Discord, not IP** (2860). The flow collects a Discord username;
  `ReportTicket` no longer carries an IP field and the masking helper was removed. No player IP is
  recorded anywhere in the pipeline.
- **Project links repointed** from `Slimefun/Slimefun4` to `SlimeCore-MC/Slimefun4Core` across
  source, guide and docs (2860). `dev-1.21.11/` left untouched.
- **Readable report notices** in console and in-game (2860) — `id [category] from name`, not a
  `toString()` dump.
- **Guide slot 49** now hosts the Addon Manager instead of the official-only bug-tracker tile
  (2860); on this fork that slot was previously blank, so nothing is lost.
- **`CheckpointService` reuses `StorageConsistencyChecker.scanBlockFiles`** as stage 9 of a restore
  (2836), catching a damaged storage half at restore time.

---

## Bug fixes

- **Console mojibake in the startup path** (2860) — decorative/Unicode characters in `Slimefun.java`
  logging that rendered as `âœ¦`-style garbage were corrected or stripped.
- **Literal `\n` in the Discord addons field** (2860) — the report embed's addon list interpolated
  an escaped newline; it now uses a real line break per addon.
- **Dead join-broadcast config key** (2860) — `monitor.invite.broadcast-on-join` is now actually
  read by the announce sink.

---

## Tests

- **2836:** three new Bukkit-free JUnit 5 classes, 26 tests (`TestCheckpointMetadata`,
  `TestCheckpointLayout`, `TestPlayerFileBackup`); the storage suite is 103 tests, all passing with
  `TestLegacyBackend` excluded as the known MockBukkit-vs-Paper-26 casualty.
- **2860:** no automated tests were added for the reporting, auto-error or addon-GUI work; it is
  verified by clean compile and by tracing each API call against real signatures (see Known issues).

---

## Known issues

- **The addon manager GUI is unverified on a live server** (2860) — compiles and API-traced, but the
  in-game render, click-through, sync and permission gating are untested; no Paper 26.x runtime was
  available here.
- **Report / auto-report delivery is unverified end-to-end** (2860) — the webhook post is
  fire-and-forget (`sendAsync`, discarded response), so a failed delivery only produces a warning log
  and success is not confirmed against Discord.
- The report webhook URL and Discord invite are hardcoded in `ReportSessionManager`; only the report
  webhook is overridable (`monitor.discord.report-webhook-url`).
- **Checkpoint caveats** (2836): the storage half is only as fresh as the last flush; a checkpoint
  roughly doubles the space its data occupies; backpack item contents pass through Bukkit's
  `ItemStack` serialization, outside the Bukkit-free test scope; restoring across a different
  Slimefun/server version is untested.
- Addon cloud-sync and the Modrinth updater still fetch and stage jars without signature
  verification — a pre-existing gap unchanged by these builds.
