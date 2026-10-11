# SlimefunCore Localization & Translation System Guide

> **Target Version:** Paper 26.x / SlimefunCore 26.x  
> **Source Directory:** `src/main/resources/languages/`  
> **Java Package:** `io.github.thebusybiscuit.slimefun4.core.services.localization`  
> **Last Updated:** September 2026

Slimefun features an international localization engine that supports over 40 languages. It can translate item names, lore descriptions, research titles, recipe names, categories, and system messages into a server's default language or even per-player languages.

This guide explains how the translation system works under the hood, how source files are organized, and provides a step-by-step tutorial on how to add and translate a new language.

---

## 1. System Architecture & Source File Layout

```text
src/main/
├── java/io/github/thebusybiscuit/slimefun4/core/services/
│   ├── LocalizationService.java           # Central localization broker & player language tracker
│   └── localization/
│       ├── Language.java                  # Holds the 5 loaded YAML configurations for a language
│       ├── LanguageFile.java              # Enum representing the 5 language file types
│       ├── LanguagePreset.java            # Registry enum of supported languages, flags & textures
│       ├── SlimefunLocalization.java      # Low-level key resolver, colorizer & formatter
│       └── TextDirection.java             # LTR vs RTL (Hebrew, Arabic, Persian)
└── resources/
    ├── languages/
    │   ├── en/                            # Master reference language (English)
    │   │   ├── categories.yml             # Guide category names & lores
    │   │   ├── messages.yml               # Chat, action bar, warning & error messages
    │   │   ├── recipes.yml                # Recipe type names (Enhanced Crafting Table, etc.)
    │   │   ├── researches.yml             # Tech tree research titles & descriptions
    │   │   └── resources.yml              # GEO resource names (Oil, Iron Ore, etc.)
    │   ├── de/, es/, fr/, ja/, etc.       # Translated language directories
    │   └── translators.json               # Community contributor credits
    └── crowdin.yml                        # Automated Crowdin translation mapping
```

---

## 2. The 5 Language Files Explained

Every language folder inside `src/main/resources/languages/<code_name>/` contains five YAML files. Each file is dedicated to a specific part of the plugin:

### 1. `messages.yml`
Controls all user-facing text strings: chat notifications, errors, warnings, GUI button labels, and command feedback.
- Example:
  ```yaml
  commands:
    no-permission: "&cYou do not have permission to use this command."
  backpack:
    already-open: "&cThis backpack is already open elsewhere!"
  ```
- **Placeholders:** Strings often contain dynamic variables like `%player%`, `%item%`, `%time%`, or `%count%`. You **must preserve** these variables exactly as written.

### 2. `categories.yml`
Controls the display names and lore descriptions of item categories inside `/sf guide`.
- Example:
  ```yaml
  weapons:
    name: "&cWeapons"
    lore:
      - "&7Swords, bows, and combat gear"
  ```

### 3. `recipes.yml`
Translates custom recipe type headers displayed in the guide (e.g. Ancient Altar, Ore Crusher, Enhanced Crafting Table).
- Example:
  ```yaml
  enhanced-crafting-table: "Enhanced Crafting Table"
  ore-crusher: "Ore Crusher"
  magic-workbench: "Magic Workbench"
  ```

### 4. `researches.yml`
Translates the names and descriptions of Slimefun tech tree research nodes.
- Example:
  ```yaml
  alloy_smelting:
    name: "Alloy Smelting"
    lore:
      - "&7Master the combination of basic ores"
      - "&7to craft high-durability metals."
  ```

### 5. `resources.yml`
Translates the names of raw resources discovered via the GEO-Scanner and mined by the GEO Miner.
- Example:
  ```yaml
  oil: "Crude Oil"
  iron: "Iron Deposit"
  copper: "Copper Vein"
  ```

---

## 3. How the Engine Works (`LocalizationService`)

1. **Startup & Loading:**
   - On plugin startup, `LocalizationService` scans `src/main/resources/languages/` and loads the language specified by `options.language` in `config.yml` (default: `en`).
   - English (`en`) is always loaded into memory as the **universal fallback**. If a translation key is missing in your language file, Slimefun silently falls back to the English string instead of throwing an error or showing blank text.
2. **Text Formatting & Color Codes:**
   - Strings are parsed using Minecraft color codes (`&a`, `&b`, `&c`, etc.) or modern hex formats.
3. **Right-to-Left (RTL) Support:**
   - Slimefun natively supports RTL languages (Arabic, Hebrew, Persian) via `TextDirection.RIGHT_TO_LEFT` in `LanguagePreset.java`.
4. **Per-Player Language Selection:**
   - When player translations are enabled, a player's preferred language is stored directly on their Bukkit `PersistentDataContainer` using a custom `NamespacedKey`.

---

## 4. Step-by-Step Tutorial: How to Add a New Language

Follow this guide to add a brand-new language to SlimefunCore. For this example, let's add **Pirate English (`en-PT`)** or **Sinhala (`si`)**.

### Step 1: Create the Language Folder
Navigate to:
`G:\SLIMEFUN\SlimefunCore - 26.x\src\main\resources\languages\`

Create a new directory named after your language's standard ISO 639-1 code (e.g. `si`, `ja`, `es`, or `en-PT`).

### Step 2: Copy the English Templates
Copy all 5 files from:
`src/main/resources/languages/en/*.yml`  
into your new directory:
`src/main/resources/languages/<your_code>/`

### Step 3: Translate the Files
Open each YAML file in an editor (UTF-8 encoding is mandatory) and translate the strings:

```yaml
# In src/main/resources/languages/si/messages.yml
commands:
  no-permission: "&cඔබට මෙම විධානය භාවිතා කිරීමට අවසර නැත."
```

#### Important Rules While Translating:
- **Never modify YAML keys:** Only edit the text after the colon `:`.
- **Preserve placeholders:** Keep tokens like `%item%`, `%player%`, `%prefix%` exactly intact.
- **Retain color codes:** Ensure formatting codes (`&e`, `&a`, `&6`) remain balanced.

---

### Step 4: Register the Language in Java (`LanguagePreset.java`)

To make Slimefun recognize your language and display a flag head in GUI menus, add an entry to:
`src/main/java/io/github/thebusybiscuit/slimefun4/core/services/localization/LanguagePreset.java`

Find the `LanguagePreset` enum:
```java
public enum LanguagePreset {

    ENGLISH("en", "a1701f21835a898b20759fb30a583a38b994abf60d3912ab4ce9f2311e74f72"),
    GERMAN("de", "5e7899b4806858697e283f084d9173fe487886453774626b24bd8cfecc77b3f"),
    // ...
    YOUR_LANGUAGE("code", "minecraft_head_texture_hash");
```

#### Arguments:
1. `key`: The folder name (e.g. `"si"`).
2. *(Optional)* `TextDirection`: Pass `TextDirection.RIGHT_TO_LEFT` if the language reads right-to-left.
3. `texture`: The 64-character SHA-256 skin texture hash representing the national flag for the head item in GUI menus (from [minecraft-heads.com](https://minecraft-heads.com)).

---

### Step 5: Test Your Translation

1. Open `plugins/Slimefun/config.yml` on your test server.
2. Set `language` to your new code:
   ```yaml
   options:
     chat-prefix: "&a&lSlimefun 4&7> "
     language: si                     # <-- Your language code here
     enable-translations: true        # <-- Enable dynamic item translation
   ```
3. Recompile the plugin:
   ```powershell
   mvn clean package -DskipTests
   ```
4. Start the server and run `/sf guide` or join the game to verify your translated strings!

---

## 5. Automated Translation Synchronization (Crowdin)

Slimefun uses [Crowdin](https://crowdin.com) for open-source community localization.

Configuration is defined in `crowdin.yml` at the repository root:
```yaml
files:
  - source: /src/main/resources/languages/en/*.yml
    translation: /src/main/resources/languages/%osx_locale%/%original_file_name%
```

- When translators update strings on Crowdin, translated files are synced back into `src/main/resources/languages/<locale>/`.
- Translators are credited in `src/main/resources/languages/translators.json`.
