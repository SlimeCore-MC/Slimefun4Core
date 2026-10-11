# SlimefunCore4

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.x-62b47a?style=for-the-badge&logo=minecraft)
![Java](https://img.shields.io/badge/Java-25-007396?style=for-the-badge&logo=java)
![License](https://img.shields.io/badge/License-GNU%20GPLv3-green?style=for-the-badge)
![Build](https://img.shields.io/badge/Channel-UNOFFICIAL-orange?style=for-the-badge)

Slimefun turns a Minecraft server into a modpack **without installing a single mod** â€”
advanced technology, magic, machines, electricity grids and full automation, in over 500
custom items.

This is **SlimefunCore4**, an unofficial fork of [Slimefun4](https://github.com/SlimeCore-MC/Slimefun4Core)
maintained by **R2Ksanu** under the rutwok-labs organisation. It is not affiliated with the
Slimefun Team.

## Version support

| Build | Minecraft | Loader | Java |
|---|---|---|---|
| 2710 | 1.21.x â†’ 1.21.11 | Paper / Purpur | 21 |
| 2800+ | 26.1 and up | Paper / Purpur | 25 |

This branch (`update/paper-26.0.x`) targets the 26.x line only:

| Setting | Value |
|---|---|
| `paper.version` | `26.1.2` |
| `plugin.yml` `api-version` | `26.1` |
| Java release | 25 |
| Maven coordinates | `SlimefunCoreV4.0` : `4.0-UNOFFICIAL` |
| Jar name | `SlimefunCore4-<build>.jar` |
| Update channel | `UNOFFICIAL` (Modrinth) |

The literal string `UNOFFICIAL` in the version is load-bearing: it selects the
`SlimefunBranch.UNOFFICIAL` branch, which switches update checks from the upstream
build-number path to the Modrinth path.

Builds for 1.21.x are kept on their own branch and are not maintained here.

## Download

[Modrinth â€” SlimefunCore](https://modrinth.com/plugin/slimefuncore) Â· GPL-3.0-only

Always test on a scratch server and take a backup before deploying to production.

## What this fork adds

Beyond upstream Slimefun4, this build ships several subsystems of its own.

### Multi-backend storage engine

Player and profile data can be stored in SQLite, MySQL, MariaDB, PostgreSQL or the
original Legacy YAML tree. HikariCP pooling, schema management and repair, an LRU+TTL
cache, an asynchronous save queue, and checksummed backup and restore.

Configured in `database.yml`. Driven by `/sf storage`, including a 54-slot in-game
control panel at `/sf storage gui`.

See [docs/storage/storage-knowledge.md](docs/storage/storage-knowledge.md).

### Backup and restore

Automatic backups on a configurable interval, plus manual backups from the command or
the GUI. SQL providers produce a JSON row dump; Legacy YAML produces a byte-exact ZIP of
the data tree covering both player data and block data. Every backup carries a SHA-256
checksum and its own format marker, so it can never be restored into the wrong kind of
backend. Restores are transactional on SQL and staged on disk for Legacy YAML, with an
automatic pre-restore snapshot.

### Migration between backends

`/sf storage migrate` copies player data from any provider to any other, Legacy YAML in both
directions included. It refuses to run while anyone is online, takes two verified backups
before its first write, reads and writes records directly rather than through `PlayerData` so
nothing is silently dropped, and leaves the old backend in place as the fallback. Block data
is not migrated â€” it lives in YAML on every provider â€” so it is flushed and archived instead.

See [docs/storage/migration-guide.md](docs/storage/migration-guide.md).

### Whole-state checkpoints

`/sf checkpoint` captures one complete Slimefun state as two halves under a single
`checkpoint-NNN` directory: a ZIP of the file-based block and machine data and the database-owned
player data. Both halves are created, checksum-verified and retained together, while restore can
target storage, database data, or both. Neither half ever contains Minecraft world data. Creation
is all-or-nothing, retention deletes the oldest only after the new one is verified, and every
restore is console-only, refuses to run with players online, and rolls its selected scope back
from a safety copy if it fails.

See [docs/storage/checkpoints.md](docs/storage/checkpoints.md).

### Addon manager

Downloads, updates, enables, disables and removes managed Slimefun addons, matching jars
by addon name against a remote index. Managed addons are detected by reading each jar's
`plugin.yml`. Configured in `addonmanager.yml`, driven by `/sf addon` (also registered as
`/addon`).

### Cargo Grid Monitor

Right-clicking a Cargo Manager opens a live 54-slot dashboard of everything its attached
cargo network holds, with paging, search and explicit deposit and withdrawal. Read-only
apart from those two actions. Sneak + right-click still toggles the network visualizer.

### Modrinth update path

Unofficial builds check the Modrinth project `slimefuncore` instead of the upstream build
number, and can stage a newer jar into `plugins/update/` for the next restart. Controlled
by `options.auto-update`, `options.auto-download-update` and `options.modrinth-project`
in `config.yml`.

## Installing

1. Java 25 and Paper or Purpur on Minecraft 26.1 or newer.
2. Drop `SlimefunCore4-<build>.jar` into `plugins/`.
3. Start the server once to generate `plugins/Slimefun/config.yml`,
   `database.yml` and `addonmanager.yml`.
4. Storage defaults to `provider: legacy`, which is the original YAML tree â€” no database
   is required to run.

## Building

The build runs on **Windows PowerShell only**. `exec-maven-plugin` is bound to the
`initialize` phase and invokes `build-tools/update-build-number.ps1`, so it cannot
complete on Linux or macOS, and none of the shipped GitHub workflows can build this fork
as written.

```powershell
mvn clean package
```

Two flags are worth knowing:

```powershell
mvn clean package "-Dexec.skip=true"      # do not bump .mvn/build-number.properties
mvn test "-Djacoco.skip=true"             # required on Java 25, see below
```

PowerShell splits an unquoted `-Dâ€¦` argument, so every `-D` must be quoted.

**JaCoCo cannot run on this JDK.** The pinned `jacoco-maven-plugin` 0.8.12 rejects Java 25
class files (major version 69) and aborts the test phase before the first test runs, so
`"-Djacoco.skip=true"` is required for any build that reaches `test`. Java 25 support
needs JaCoCo 0.8.14 or newer.

The test scope still resolves `mockbukkit-v1.21`, which is a 1.21 API surface against a
26.x codebase. Bukkit-dependent tests are therefore limited; the storage suite is
deliberately written Bukkit-free.

## Documentation

| Document | Contents |
|---|---|
| [docs/commands.md](docs/commands.md) | Every command and permission node |
| [docs/configuration.md](docs/configuration.md) | Every configuration file and key |
| [docs/storage/storage-knowledge.md](docs/storage/storage-knowledge.md) | Storage, backup, restore and database setup |
| [docs/storage/migration-guide.md](docs/storage/migration-guide.md) | Runbook for moving player data between providers |
| [docs/storage/checkpoints.md](docs/storage/checkpoints.md) | Runbook for whole-state checkpoints and restores |
| [docs/adr/0001-storage-layer.md](docs/adr/0001-storage-layer.md) | Storage layer design record |
| [docs/sop/update.md](docs/sop/update.md) | Procedure for updating to a new Minecraft version |
| [CHANGELOG.md](CHANGELOG.md) | Fork release history |

## Screenshots

Reused from the original project.

| Reactors & Electricity | Awesome Factories | Magic & Altars |
|---|---|---|
| ![Reactors and electricity showcase](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase1.png) | ![Factory automation showcase](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase6.png) | ![Magic and altar showcase](https://raw.githubusercontent.com/Slimefun/Slimefun-Wiki/master/images/showcase5.png) |

## Links

- Repository â€” <https://github.com/rutwok-labs/Slimefun4Core>
- Bug reports â€” <https://github.com/rutwok-labs/Slimefun4Core/issues>
- Modrinth â€” <https://modrinth.com/plugin/slimefuncore>
- Fork wiki â€” <https://rutwok-labs.github.io/SlimefunCore-Wiki/#/>
- Addon repository â€” <https://github.com/rutwok-labs/SF4-Addons>
- Discord â€” <https://discord.gg/hJwXe3tw4>
- Upstream project â€” <https://github.com/SlimeCore-MC/Slimefun4Core>
- Upstream wiki â€” <https://github.com/SlimeCore-MC/Slimefun4Core/wiki>

## Status

The core is working on the 26.x line. Community addons are largely still on 1.21.x and
have not been updated for 26.x.

This build is under active testing and may contain bugs. The storage layer in particular
is marked `@Beta`; see [docs/adr/0001-storage-layer.md](docs/adr/0001-storage-layer.md)
for exactly what is finished and what is not.

## Disclaimer

This is an unofficial fork and is **not affiliated with the Slimefun developers or team**.
All credit for Slimefun goes to the original authors.

## Credits

- Original creator â€” [TheBusyBiscuit](https://github.com/TheBusyBiscuit) (he/him)
- Original team â€” the Slimefun Team and its contributors, since 2013
- Fork maintainer â€” R2Ksanu, rutwok-labs
- Screenshots â€” courtesy of the Slimefun community

## License

GNU GPLv3 â€” see [LICENSE](LICENSE). You may modify and redistribute this project, but you
must give credit and keep the same license.



