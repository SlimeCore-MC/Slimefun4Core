# Concept: Codebase Architecture & Systems

This concept folder covers the internal architectural blueprints, system layers, registries, execution lifecycles, and class hierarchies of **SlimefunCore**.

## Concept Overview

SlimefunCore is structured around clean layer decoupling:
- **API Layer (`api`):** External contracts, item blueprints, researches, and events.
- **Core Layer (`core`):** Master registries (`SlimefunRegistry`), machine processing timers (`MachineProcessor`), energy and cargo network solvers (`NetworkManager`), and command trees.
- **Storage Layer (`storage`):** Dual persistence engine separating file-based block/inventory trees from SQL/flatfile player progression.
- **Implementation Layer (`implementation`):** Bootstrap lifecycle (`Slimefun.java`), 40+ Bukkit listeners, background tasks (`TickerTask`), and 500+ items.
- **Integrations & Utils:** Third-party plugin hooks and internal bridge toolkits.

## Documents in this Concept

- [**Full Codebase Structure & Architecture (`structure.md`)**](structure.md): Deep-dive into all packages, tables, tick loops, and developer extension points.
