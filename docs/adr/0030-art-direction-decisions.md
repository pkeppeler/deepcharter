---
status: accepted
---

# The art direction: a visual timeline sky, our own surface, GeckoLib pods and a template-built colony

Amends [ADR 0016](0016-the-colony-is-built-once-in-code-and-the-conduit-is-set-when-its-chunk-loads.md). Builds on [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (our own dry surface). The direction itself is [art-direction.md](../design/art-direction.md), decided with the user on 2026-10-08 (issue #225, PR #234). This record keeps only the calls that are hard to reverse.

## Decision

- **The sky is a visual timeline.** The overworld gets a timeline in `#minecraft:in_overworld` that sets sky, fog, sun and glow attributes and swings slowly between a dusk and a deep night, and never reaches full day. Gameplay light (`gameplay/sky_light_level`) is a separate attribute, so the 20-minute gameplay clock, beds and spawning are unchanged. The look is data in a pack; the vanilla sky colours, sun texture and clouds are replaced, not mixed in.
- **The surface is our own generator.** The surface band of the campaign world is regolith plains, craters and terraced mesas, with no flora, fauna or water. It stays inside the ADR 0029 limits: about one material-rule condition per block, no sea, and every feature measured against the 1.15x per-column bar. SPEC section 3 no longer calls the surface a vanilla-style frontier.
- **Pod models are GeckoLib.** GeckoLib 5.5.7 is bundled jar-in-jar and renders the pods and the lampless figure from Blockbench sources, with glowmasks and part tiers as bones. Model, animation and texture are skins; code names only bones. If GeckoLib breaks on a Minecraft bump, the fallback is vanilla `ModelPart` loaded from the same JSON.
- **The colony is built from structure files.** This amends ADR 0016's "not a structure template". `ColonyBuilder` still runs once on `SERVER_STARTED`, still centres and flattens the pad at the world spawn, and still records the pad in `ColonySite` before the first block. What changes is the buildings: they are structure `.nbt` pieces written by a script, placed from a reloadable layout file (`colony/layout.json`) and palettes, instead of a table of offsets in code. The Conduit stays as ADR 0016 says. A dev command rebuilds the colony from the files.

## Considered Options

- **Gameplay-clock sky.** Rejected: the sky would follow the 20-minute day and be bright for half of it. A fixed dimension time (`has_fixed_time`) is rejected too: it freezes gameplay time and breaks beds.
- **Vanilla terrain with a recolour.** Rejected: the tall-world measurements (ADR 0029), and the grass and acacias still read as Minecraft under a dusk sky (tryouts).
- **Vanilla `ModelPart` entities only.** Rejected for the pods: no tooling for animated, layered, glowmasked models, and no Blockbench round trip. Kept as the fallback.
- **Keep the colony in code.** Rejected: the user wants everything editable as files, and a table of offsets cannot hold a roofed, signed, multi-storey town or a 40 to 60 block headframe.
- **Placing the colony as a worldgen structure.** Rejected, as in ADR 0016: the pad is flattened at a spawn the world already chose, and no structure placement can follow it.

## Consequences

- Template files are data that Minecraft's data fixers must carry. A Minecraft bump re-checks every `.nbt`; the Python script that writes them is the source of truth and can rewrite them. `ColonyTest.aFileFromAnOlderMinecraftLoadsUnchanged` still guards `ColonySite`.
- The colony still clears its pad volume and builds once: old worlds keep the old colony. New builds need a new world.
- GeckoLib is a runtime dependency that every player needs; it ships inside the mod jar.
- Sky and biome data are server data, so they need a world reopen, not F3+T, unless the client atmosphere layer from the tooling research is added.
- Follow-up issues: #239 to #250 (milestone "Art direction overhaul (before M3)").
- AI-assisted assets (SPEC section 15) carry a Modrinth disclosure for any public release. It is a policy note, not an architecture call, and is recorded in the SPEC.
