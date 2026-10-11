# Contributing to SlimefunCore4

Thanks for your interest in contributing to this project.

This repository is an **unofficial fork** of Slimefun focused on keeping the plugin working on
modern server environments, specifically **Paper / Purpur on Minecraft 26.1+** with **Java 25**.

## Project Scope

This fork is mainly focused on:

- Minecraft `26.1.x` (and newer) compatibility
- Purpur and Paper startup/runtime support
- Dependency and API compatibility fixes
- Stability improvements for this unofficial build
- The fork's own subsystems: multi-backend storage, backups, checkpoints, addon manager,
  Cargo Grid Monitor, Modrinth update path, and the monitor/report system
- Packaging and release fixes for this fork

This repository is **not** intended to replace the original Slimefun project.

Builds for the older `1.21.x` line are kept on their own branch and are not maintained here.

## Original Project Credit

This project is based on the original **Slimefun** project.

- Original Creator: **[TheBusyBiscuit](https://github.com/TheBusyBiscuit)**
- Original Team: **Slimefun Team**
- Upstream Repository: [https://github.com/SlimeCore-MC/Slimefun4Core](https://github.com/SlimeCore-MC/Slimefun4Core)

Please respect the original authors and do not report issues from this unofficial fork to the
official Slimefun team unless the issue is confirmed to exist in the upstream project as well.

## Reporting Issues

If you find a bug in this fork, please open an issue in **this repository**, not the original
Slimefun repository. You can also file a report in-game with `/sf report <category>`.

When reporting a bug, include:

- Server software and exact version
- Minecraft version
- Java version
- Slimefun build number from this repo
- Steps to reproduce the issue
- Full console error or stack trace
- Installed addons or related plugins

Good bug reports make fixes much faster.

## Pull Requests

Pull requests are welcome for changes that fit this fork.

Good pull request targets:

- Compatibility fixes for `26.1.x` and newer
- Runtime/API migration fixes
- Safer startup behavior
- Packaging and shading fixes
- Stability improvements
- Documentation updates for this repo

Please avoid unrelated large feature changes unless they are clearly useful for this fork.

## Before Opening a PR

1. Make sure your change is relevant to this repository
2. Keep the edit focused and easy to review
3. Test it on a real server when possible
4. Include logs or explanation if the fix is runtime-related
5. Do not remove original project credits

## Build Instructions

This project uses **Maven**, and the build runs on **Windows PowerShell only**. The
`exec-maven-plugin` is bound to the `initialize` phase and invokes
`build-tools/update-build-number.ps1`, so it cannot complete on Linux or macOS, and none of the
shipped GitHub workflows can build this fork as written.

```powershell
mvn clean package
```

PowerShell splits an unquoted `-D...` argument, so every `-D` flag must be quoted:

```powershell
mvn clean package "-Dexec.skip=true"      # do not bump .mvn/build-number.properties
mvn test "-Djacoco.skip=true"             # required on Java 25 (see below)
```

**JaCoCo cannot run on this JDK.** The pinned `jacoco-maven-plugin` 0.8.12 rejects Java 25 class
files (major version 69) and aborts the test phase, so `"-Djacoco.skip=true"` is required for any
build that reaches `test`. Java 25 support needs JaCoCo 0.8.14 or newer.

The built jar is generated in:

```text
target/SlimefunCore4-<build>.jar
```

## Testing Expectations

If possible, test your changes with:

- Purpur or Paper
- Minecraft `26.1` or newer
- Java `25`

Helpful test areas:

- plugin startup
- item and block loading
- guide/menu opening
- custom heads or textures
- recipe loading
- machine placement and interaction
- storage engine behavior (if touched)

## Code Style

Try to keep changes consistent with the surrounding code.

General expectations:

- use clear and readable Java
- avoid unrelated refactors
- keep imports clean
- prefer small focused commits
- add comments only where they help explain non-obvious logic

## Important Notes

- This is an **unofficial build**
- Official Slimefun support does **not** apply to this repository
- Contributions here should stay within the license of the original project
- Give proper credit when reusing or modifying work

## Thank You

Thanks to everyone helping improve this unofficial build.

Your fixes, tests, and reports help keep the project usable on modern servers.
