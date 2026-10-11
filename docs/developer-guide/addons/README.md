# Concept: Addon Ecosystem & Extensibility

This concept folder covers how Slimefun operates as an extensible platform for third-party plugin developers, and how SlimefunCore dynamically discovers, downloads, and runs addons.

## Concept Overview

The Slimefun addon architecture bridges two worlds:
- **For Addon Developers:** A lightweight API (`SlimefunAddon`, `SlimefunItem`, `RecipeType`, `ItemGroup`, `EnergyNetComponent`) allowing developers to create massive expansions without modifying SlimefunCore.
- **For Server Administrators & Slimefun Engine:** A built-in `AddonManager` that fetches compatible addon manifests from GitHub, verifies versions against the running server drop, and allows automated downloading and updating.

## Documents in this Concept

- [**Addon System & Plugin Architecture (`addon-system.md`)**](addon-system.md): Complete guide to building Slimefun addons, registering items and energy machines, avoiding 26.x pitfalls, and configuring `addonmanager.yml`.
