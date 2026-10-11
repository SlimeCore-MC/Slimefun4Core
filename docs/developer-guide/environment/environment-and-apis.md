# Developer Environment, APIs & Versioning Guide

> **Target Version:** Paper 26.x / Minecraft 1.26.x  
> **Java Toolchain:** Java 25 (Temurin 25.0.3+ Hotspot)  
> **Build System:** Cross-platform Maven  
> **Last Updated:** September 2026

Before writing or modifying any code in **SlimefunCore**, there are critical upstream resources, API version strings, and synchronization rules you must understand. Ignoring these will result in compile failures, runtime crashes, or broken server data.

---

## 1. Essential External Websites & API Docs

Bookmark and consult these resources before beginning work on any update or feature:

| Resource | URL | Why It Matters |
|---|---|---|
| **PaperMC Javadocs** | [jd.papermc.io](https://jd.papermc.io) | The definitive reference for Paper-specific API classes, event models, and modernization utilities. |
| **PaperMC Maven Repo** | [repo.papermc.io](https://repo.papermc.io/repository/maven-public/) | Where `paper-api` artifacts and builds are hosted. Check this when updating `<paper.api.version>`. |
| **Minecraft Official Changelogs** | [minecraft.net/articles](https://www.minecraft.net/en-us/articles) | Official release notes detailing vanilla mechanics changes, block ID shifts, and data formats. |
| **Minecraft Wiki (Technical)** | [minecraft.wiki](https://minecraft.wiki) | Detailed technical breakdowns of NBT tags, block states, damage causes, and game mechanics. |
| **Spigot Javadocs** | [hub.spigotmc.org/javadocs/spigot](https://hub.spigotmc.org/javadocs/spigot/) | Baseline Bukkit/Spigot API documentation. |
| **Spigot Nexus Snapshots** | [hub.spigotmc.org/nexus](https://hub.spigotmc.org/nexus/content/repositories/snapshots/) | Upstream Spigot dependencies and builds. |
| **Modrinth Project Releases** | [modrinth.com/plugin/slimefuncore](https://modrinth.com/plugin/slimefuncore) | Official distribution point for SlimefunCore unofficial releases. |
| **HikariCP Documentation** | [github.com/brettwooldridge/HikariCP](https://github.com/brettwooldridge/HikariCP) | Best practices for SQL connection pooling configuration used in `database.yml`. |

---

## 2. Paper API Version Strings & Sync Points

SlimefunCore targets the modern year-based Paper release model (`26.x`). Multiple files must remain in exact synchronization:

### A. The Two Paper Version Properties in `pom.xml`
In `pom.xml`, under `<properties>`:
```xml
<!-- The major drop / release -->
<paper.version>26.1.2</paper.version>

<!-- The exact compilation artifact from PaperMC nexus -->
<paper.api.version>26.1.2.build.72-stable</paper.api.version>
```

#### Understanding the Format:
`<year>.<drop>.build.<build_number>-<tier>`
- **`26.1.2`**: Minecraft year 26, drop 1, minor patch 2.
- **`build.72`**: The Paper build number published to the maven repository.
- **`-stable` vs `-alpha`**: Use `-stable` for production releases. Only use `-alpha` during early snapshot migrations.

### B. The `plugin.yml` Sync Point
Whenever `<paper.version>` changes in `pom.xml`, you **must** update `src/main/resources/plugin.yml`:
```yaml
main: io.github.thebusybiscuit.slimefun4.implementation.Slimefun
api-version: '26.1'
```
*Rule:* The `api-version` in `plugin.yml` must match the major drop (e.g. `'26.1'`). If these mismatch, Paper may refuse to enable the plugin or apply legacy compatibility layers that break event listeners.

### C. The `MinecraftVersion.java` Enum
Located at `src/main/java/io/github/thebusybiscuit/slimefun4/api/MinecraftVersion.java`:
- Slimefun tracks version comparisons via its own `MinecraftVersion` enum.
- Constant declaration order is **load-bearing**:
  ```java
  MINECRAFT_26(26, "26.x"),
  // New versions (e.g., MINECRAFT_27) MUST be added immediately here,
  // BEFORE UNKNOWN and UNIT_TEST!
  UNKNOWN(999, "Unknown"),
  UNIT_TEST(1000, "Unit Test");
  ```
  *Warning:* `UNKNOWN` and `UNIT_TEST` must always remain the highest ordinals so unit test mocks pass version-gated logic.

---

## 3. What Breaks Between Minecraft / Paper Drops

When updating to a new Minecraft version, check these areas first:

### 1. Damage Sources & Combat Events
- Bukkit/Paper frequently changes entity damage tracking.
- *Watch out for:* `DamageSource.builder(...)`. Paper 26.2+ rejects causing entities without a direct entity. Always check `ExplosiveBow` and `SeismicAxe`.

### 2. Item Serializations & Data Components
- Modern Minecraft relies heavily on Data Components instead of raw NBT.
- Slimefun converts items to byte streams using `ItemStack.serializeAsBytes()`.
- Always test custom lore, enchantments, and custom NBT items inside backpacks when updating the Paper API.

### 3. Registry & Material Renames
- Mojang frequently renames materials, tags, or sounds between releases.
- Check `ColoredMaterial.java`, `HeadTexture.java`, and `SlimefunItems.java` for deprecated `Material` or `Sound` enum entries.

### 4. Reflection & Provider Class Hierarchies
- Reflection lookups like `getDeclaredMethod()` do not resolve inherited methods across subclasses.
- Example: `MariaDBProvider extends MySQLProvider`. Any connection pool or database introspection must use interface contracts (`StorageProvider.borrowConnection()`), never raw reflection.

---

## 4. `pom.xml` Architecture & Rules

### The Mandatory `UNOFFICIAL` Tag
In `pom.xml`:
```xml
<version>4.0-UNOFFICIAL</version>
```
**Do not remove `UNOFFICIAL` from the version string.**  
The runtime update checker (`UpdaterService.java`) checks if the version contains `UNOFFICIAL`. If present, it checks **Modrinth** (`slimefuncore`) for updates. If removed, it falls back to upstream build endpoints that will fail or attempt to downgrade to an incompatible 1.20 build.

### Java 25 Toolchain Requirement
```xml
<maven.compiler.release>25</maven.compiler.release>
```
- SlimefunCore requires a Java 25 JDK (such as Eclipse Adoptium Temurin 25).
- Ensure your local environment variable points to it:
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
  ```

---

## 5. Developer Command Cheat Sheet

Run these from the project root (`G:\SLIMEFUN\SlimefunCore - 26.x`):

### 1. Compile During Development
A compile does not persist the prepared build number:
```shell
mvn -B clean compile
```

### 2. Full Verified Package
```shell
mvn -B clean verify -DskipTests
```
Produces `target/SlimefunCore4-<build>.jar` and atomically commits that number after verification.

### 3. Running Bukkit-Free Unit Tests
JaCoCo 0.8.14 supports Java 25. Run tests that do not initialize MockBukkit directly:
```shell
mvn -B test -Dtest=TestDatabaseBackupService,TestBackupRoundTrip
```

### 4. Bukkit Integration Testing
The available MockBukkit release targets Minecraft 1.21 and cannot initialize with Paper 26.x registry data. Run Bukkit-facing integration checks on a Paper 26 test server.
