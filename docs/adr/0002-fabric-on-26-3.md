---
status: accepted
---

# Build on Fabric for Minecraft 26.3

We build Deep Charter on Fabric for Minecraft 26.3. Fabric has a stable loader and client GameTests, and 26.x is unobfuscated. A total conversion doesn't need the NeoForge 1.21.1 content ecosystem (Create and the like).

## Considered Options

- **NeoForge 26.2 / 26.3**: rejected. NeoForge 26.3 is beta only. Sinytra Connector, the bridge to Fabric mods, doesn't support 26.2 or later.
- **Forge 1.12.2 with Cubic Chunks**: rejected. Legacy Java 8, and the Cubic Chunks project warns that it breaks other mods.

## Consequences

Minecraft drops arrive roughly quarterly, so we re-port our mixins each quarter.
