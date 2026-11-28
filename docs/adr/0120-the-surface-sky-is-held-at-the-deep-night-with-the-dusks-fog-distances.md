---
status: accepted
---

# The surface sky is held at the deep night, with the dusk's fog distances

Supersedes the swing of [ADR 0030](0030-art-direction-decisions.md)'s sky bullet and section 1 of [art-direction.md](../design/art-direction.md). Issue [#1606](https://github.com/pkeppeler/deepcharter/issues/1606).

## Context

ADR 0030 chose a slow dusk-to-night swing on its own clock (`deepcharter:sky`, 288000 ticks) that never reaches full day, and listed eternal night under Not chosen, because the terrain was too hard to read. At the friends playtest on 2026-11-27 the operator held the server's sky and decided, in game chat:

- the surface should be perpetual night, since it is creepier and fits the mood;
- first at sky tick 80993 (a late dusk), then corrected to the darkest part of the night, tick 144000;
- seeing it: "it should be this dark, but not this foggy". The timeline also sets the fog distances, 20 to 150 at dusk and 8 to 72 at deep night, and it covers the whole campaign world, so the held night pulled the fog in, on the surface and down a layer 1 shaft alike.

## Decision

- **Every track of `data/deepcharter/timeline/sky.json` holds one value**, the deep night's (tick 144000): sky `#07050a`, fog `#160c0a`, no sunset glow, sun 115 and moon 320 degrees, stars at 1.0, sky light `#6a70b0` at a factor of 0.16, no clouds, dust at 0.02.
- **The fog distances are the dusk's**, start 20 and end 150, so the dark does not close in. A layer's zone biomes keep their own fog over the timeline (ADR 0062), so a layer reads the same as before at every tick.
- **The clock and the timeline stay.** `has_fixed_time` stays off: the gameplay clock runs, beds work, and the sky clock still ticks, reading the same value at every tick. A pack can bring the swing back by giving the tracks keyframes again.

## Consequences

- Readability is the risk ADR 0030 named. The `surface-held-sky` evidence shows the plain, the great pit's crater and the closest mesa under the held sky, and the floodlights, lamps and pod lights now carry the surface.
- The sun is never in view (115 degrees is under the western horizon), so the round sun texture of ADR 0030 is not seen; it stays for a pack that brings back the dusk. `SkyClientTest` no longer checks its shape.
- Tests that compared dusk with night now read the same values at both: `SkyTimelineTest` holds the values at ticks across the period, `CampaignAtmosphereTest` reads the held sky light factor and checks the thunder, not the clock, moves the surface, and `LayerAtmosphereClientTest` drops the surface control's brightness ratio, which a night surface cannot meet; the factor checks still tell the layer biome from the timeline.
