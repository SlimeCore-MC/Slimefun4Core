# Concept: Developer Environment, Toolchains & APIs

This concept folder covers developer tooling, versioning rules, upstream Paper/Mojang dependencies, and build requirements.

## Concept Overview

Working on SlimefunCore requires strict alignment with the modern Minecraft ecosystem:
- **Java 25 LTS:** Paper 26.x requires class file v69 (Eclipse Adoptium Temurin 25).
- **Paper API Scheme:** Synchronizing `<paper.version>` and `<paper.api.version>` in `pom.xml` with `plugin.yml`'s `api-version`.
- **Breaking Drop Changes:** Avoiding upstream traps in combat damage builders, item data components, and reflection lookups.
- **Maven & Testing:** Cross-platform build-number handling, Java 25 coverage, and the current MockBukkit/Paper 26 compatibility boundary.

## Documents in this Concept

- [**Environment, APIs & Versioning Guide (`environment-and-apis.md`)**](environment-and-apis.md): Upstream documentation links, Paper API versioning scheme, Minecraft changelog pitfalls, `pom.xml` sync points, and local build commands.
