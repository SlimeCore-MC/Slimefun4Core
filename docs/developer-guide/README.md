# SlimefunCore Developer Guide — Concept Directory

Welcome to the **SlimefunCore Developer Guide**. To keep documentation clean, modular, and easy to navigate, the documentation is categorized into dedicated **Concept Folders**. Each folder encapsulates everything you need to understand that specific system.

---

## Concept Index

```text
docs/developer-guide/
├── architecture/       # Codebase Architecture, Module Layers & Systems
├── environment/        # Toolchains, Java 25, Paper APIs, pom.xml & Builds
├── addons/             # Addon Plugin Development & Core AddonManager
└── language/           # Localization Engine, Translation Files & Crowdin
```

---

### 1. [Architecture & Systems (`architecture/`)](architecture/README.md)
*Focus: Deep structural understanding of the Slimefun codebase.*
- **[Full Codebase Architecture & Structure (`architecture/structure.md`)](architecture/structure.md)**
  - API, Core, Storage, Implementation, and Integrations layer breakdown.
  - Server startup sequence and the machine tick cycle (`TickerTask`).
  - Item capabilities and traits (`EnergyNetComponent`, `Rechargeable`, `Soulbound`, etc.).

---

### 2. [Environment, APIs & Versioning (`environment/`)](environment/README.md)
*Focus: Toolchains, upstream dependencies, and build requirements.*
- **[Environment, APIs & Versioning Guide (`environment/environment-and-apis.md`)](environment/environment-and-apis.md)**
  - Essential external links (PaperMC Javadocs, Nexus, Mojang release notes).
  - Paper versioning scheme (`<year>.<drop>.build.<build>-<tier>`) and `plugin.yml` sync points.
  - Minecraft update traps (DamageSource API, Data Components, reflection).
  - Java 25 requirement and cross-platform Maven build recipes.

---

### 3. [Addon Ecosystem & Extensibility (`addons/`)](addons/README.md)
*Focus: Building third-party plugins and understanding the internal AddonManager.*
- **[Addon System & Plugin Architecture (`addons/addon-system.md`)](addons/addon-system.md)**
  - How to create a Slimefun addon plugin from scratch (`SlimefunAddon` contract).
  - Registering item categories (`ItemGroup`), custom items (`SlimefunItem`), and research.
  - Modern 26.x changes (`SlimefunItemStack` vs Bukkit `ItemStack`).
  - Core `AddonManager` subsystem (`addonmanager.yml`, GitHub cloud sync, `/addon` commands).

---

### 4. [Localization & Translations (`language/`)](language/README.md)
*Focus: Translating the plugin and managing the multi-language engine.*
- **[Localization & Translation System (`language/README.md`)](language/README.md)**
  - How `LocalizationService` works with universal English fallback and RTL support.
  - The 5 core translation files (`messages.yml`, `categories.yml`, `recipes.yml`, `researches.yml`, `resources.yml`).
  - Step-by-step tutorial: adding, translating, and registering a new language in `LanguagePreset.java`.
  - Community translation workflow via Crowdin (`crowdin.yml`).
