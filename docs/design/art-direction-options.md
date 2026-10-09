# Design overhaul 3: art direction options

> Superseded by [art-direction.md](art-direction.md); this is the options record.

Issue [#225](https://github.com/pkeppeler/deepcharter/issues/225), 2026-10-08. A fresh-eyes critique of the game as it looks today, a target look in words, and options for each area, for the user to decide. When the user decides, the chosen direction is added to this document and becomes the brief for the implementation issues.

**Inputs:** the catalogue of every asset as seen in the game ([current-state.md](current-state.md), PR #227, 144 stills), the tooling research ([tooling-options.md](tooling-options.md), PR #228), the lore canon ([LORE.md](../LORE.md), PR #34), [SPEC](../SPEC.md) and [ADR 0029](../adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (one tall campaign world, PR #219). The original game was studied privately from the local export; no file of it is copied, traced or linked here. Every image on this page is our own, shot in our game.

**Two references judge everything:** Motherload's look and feel, and our own lore and vibe (mystery, dread of the deep and dark unknown, Tolkien and Lewis darkness where evil is hollow and beaten and faith is quiet).

## In plain words

- **Why it looks like a mod:** the first thing you see is a sunny Minecraft savanna with cows. The pod is a stretched copper block you sit on top of. The colony is a set of low castle-like walls in a dozen vanilla materials. The screens are black boxes with Minecraft's font. Almost nothing on screen is ours.
- **What already works:** the handbook, the green CRT terminal idea, the real darkness below ground, the lampless figure that fades near light, the Conduit as a landmark, and the Prospector wreck with one lamp still lit.
- **The target in one line:** a rust-red world held at dusk, where a small Company town of riveted steel and sodium lamps sits over a black hole that goes down forever, and the only true light is the lamp you carry.
- **The recommended set:** eternal dusk sky; our own bare regolith surface with craters and rifts; a prefab Company town under one monumental headframe; pods that grow from a Motherload capsule (the Mole) to an Atlantis-style digger (the Behemoth); 16x textures with GTNH-style layers and glow; per-layer fog from the original's depth colours; terminals framed as physical machines; three kinds of light (true lamp, Company sodium, the deep's own).
- **Decisions that change settled SPEC lines:** the vanilla surface (§3) and the "start like normal Minecraft" bootstrap (§4), texture resolution and "AI-assisted assets" (§15), and optional mods in the pack (§2). See [Decisions for the user](#5-decisions-for-the-user).
- **Tryouts in the game:** four data-only looks (today, eternal dusk, eternal night, dusk plus a colour grade), shot from the same views. A dusk sky changes the mood a lot for one data file, but grass, trees and vanilla walls still say "Minecraft" under it. See [section 4](#4-tryouts-in-the-game).

---

## 1. Critique

### Why it reads as "a mod tacked onto Minecraft"

Ranked by how much each cause hurts the first impression. Each one names the stills that show it ([catalogue](current-state.md)), and how it fails each reference.

**1. The planet is vanilla Minecraft.** The first frame is a savanna: acacia trees, yellow-green grass, grass tufts, a sand beach at the edge, and cows and armadillos walking through the colony square.

- Stills: [surface-south-noon], [surface-east-noon], [colony-aerial-south], [colony-aerial-northwest], [statue-from-the-square] (a cow by the terminals), [pay-office-from-the-square] (a cow in the doorway), [hud-pod-status-and-altimeter-surface] (an armadillo by the pod).
- Fails Motherload: its surface is a bare strip of brown soil under the sky, a handful of shops, and nothing alive. The soil is the game. Grass and trees are the single strongest "this is Minecraft" signal.
- Fails the lore: Prosperity is a company town on a hostile world that emptied in one night. Grazing cattle say "safe and alive". They also contradict "400 bunks, all made up" and the silence of the last 38 years.
- The colony pad is cut into the hills as a flat plate with sheer cliffs at its edges ([colony-aerial-northwest], [colony-aerial-northeast]), so it reads as pasted on, not built on.

**2. The sky and light are a sunny Minecraft day.** Blue sky, the white square sun, white clouds, the vanilla orange dusk band, and the vanilla 20-minute day.

- Stills: [sky-up-noon], [surface-south-dusk], [sky-up-dusk], [surface-north-night], [colony-from-the-south-edge].
- Fails Motherload: the user remembers its world as "eternally dusk or night". In fact the original cycles day and night about once a minute, but almost all play happens underground against dark depth colours, so the felt mood is dark. Our surface is bright for about half of every 20-minute day.
- Fails the lore: "a lamp is life" needs darkness around the lamp. A sunlit colony has nowhere for a lamp to matter.

**3. The pod is a stretched vanilla block.** The Mole is raw copper, the Prospector is an iron block, a wreck is a coal block, each squashed to 0.9 blocks tall. It has no front, no drill, no window, no lamp and no treads. The player sits on top of it in the open, holding whatever is in their hand.

- Stills: [mole-unlit-day-front], [mole-unlit-day-side], [prospector-unlit-day-front], [hud-pod-in-third-person-surface], [hangar-founding-mole-repaired], [wrecks-mole-and-prospector-day], [mole-lit-dark-front].
- Fails Motherload: its pod is the hero of every frame: a dome-shaped hull on treads, a dark window band, a big spiral drill cone, antennae and an exhaust, and it unfolds a rotor to fly. Its silhouette reads at a glance.
- Fails the Atlantis reference: the digger is mass, rivets, teeth and lamps. Ours has none of them.
- Fails the lore: the pod is where your lamp lives. "Things come to dark pods" cannot be shown when a lit pod and an unlit pod look the same apart from the light on the floor ([mole-unlit-dark-front] against [mole-lit-dark-front]).

**4. The colony is a patchwork of vanilla building blocks in low, roofless boxes.** Stone bricks, spruce planks, red brick, white terracotta, blackstone, polished andesite, a light-blue concrete pad. The "worn" wall tops are square notches, which read as castle battlements. Nothing is taller than the Conduit's 10 blocks. There are no roofs, pipes, lamps, signs, logos or wires.

- Stills: [colony-aerial-south], [hangar-from-the-square], [chapel-from-the-square], [bunkhouse-from-the-square], [pay-office-from-the-square], [personnel-office-from-the-square], [lamp-and-pick-from-the-square], [continuity-office-inside], [conduit-from-the-square].
- Fails Motherload: its buildings are few, chunky and each one a landmark: clay-and-steel domes and sheds with pipes, dishes and huge bold signs (fuel, mineral processing, a junk shop). You know which shop is which from across the map.
- Fails the lore: Prosperity is H. Colom & Company's town, with a bull's-head logo, "Deeper Together!", a pay office stacked with uncollected stubs, a burned-out bar. None of that identity is visible. It reads as a medieval village ruin.
- **The Founder statue reads as a large copper cross on a plinth** ([statue-from-the-square], [statue-close], [statue-hands-from-above]). In the lore it is the idol's counterfeit: a bronze figure with open, empty hands and a slot in its chest. A cross at the heart of the Company town breaks guardrail 4 ("faith is quiet and unnamed") and inverts the meaning of the most important prop in the colony. This is small to fix and should be fixed whatever else is chosen.

**5. Every material is vanilla or flat.** The layers are vanilla stone in vanilla cave noise. Our own textures are flat: ores are grey noise with a few coloured squares, terminals are flat app-style icons (a drop, a cross, an arrow), and items are coloured discs and framed pictograms.

- Stills: [layer-1-cave-south], [layer-2-cave-south], [texture-sheet-blocks], [block-gallery-1], [block-gallery-2], [texture-sheet-items], [items-gallery-1].
- Fails Motherload: its soil is a rich speckled brown, its ores are glinting crystals and nuggets, its rocks are rounded boulders, and its parts are chunky rendered machines (drill cones in each metal's colour, engines, fans, fuel tanks). The colour coding of ores is right in ours; the material is not.
- Fails the lore: there is no wear. Nothing shows 38 years of dust, rust and abandonment.

**6. The screens and HUD are debug overlays.** Terminals fill the screen with black and use Minecraft's font, with no machine around them. The pod HUD is four lines of white text. The vanilla hotbar, "Press Left Shift to dismount" and the player's held item stay on screen while piloting. The cargo screen is vanilla grey.

- Stills: [screen-fuel-pump-online], [screen-ore-processor-online], [screen-upgrade-terminal-online], [screen-repair-station-online] (its text runs off the right edge), [hud-pod-status-and-altimeter-surface], [screen-pod-cargo].
- Fails Motherload: its shops are physical machines: grimy riveted metal panels, rounded CRT glass, cogs at the corners, bevelled red and green buttons, and a chrome model-number nameplate. Its HUD is diegetic: a red hull cylinder, an amber fuel can and a yellow altimeter.
- Fails the lore: the Company's cheerful voice has no visual home. Its terminals could carry the bull logo, "Deeper Together!" and Personnel's smile.

**7. The layer dressing is a vanilla mineshaft.** Oak planks, rails, cobblestone, bookshelves, a sea lantern clock, white quartz props. Company Rock is a riveted steel plate set into natural rock, so it reads as a misplaced poster. Lava sits as single glowing cubes in walls. The breach crust is a flat rust-red tile like netherrack. A vanilla nameplate ("PROSPECTOR-0002") floats over the wreck.

- Stills: [structure-gallery-toward-the-rubble], [structure-punch-clock-overview], [structure-rails-long-drift], [structure-wreck-prospector-0002], [layer-2-cave-south], [layer-2-cave-east], [layer-1-breach-crust-floor].
- Fails Motherload: each depth band has its own colour behind the tunnels (brown, near-black, dark green, teal, navy, plum, amber, red), so you feel the depth change. Our two layers are the same grey stone.
- Fails the lore: the Shift Change hub should feel like a stopped clock in a dark station. The punch-clock hall reads as a library.

**8. Scale is flat.** Everything is one or two blocks above head height. Motherload and the Atlantis convoy are about small vehicles in very large spaces. We have no big shape in the sky line and no great void below.

**9. The lampless figure is a black zombie.** The vanilla zombie model, arms forward ([lampless-figure-front], [lampless-figure-side]). In the dark it reads as a silhouette, which is right, but in any light it reads as "dark Steve".

**10. Every sound is a vanilla placeholder.** Layer 1 plays the Deep Dark's music, layer 2 the End's, terminals play the creative-mode music (catalogue, [Sounds and music](current-state.md#sounds-and-music)). Players who know Minecraft recognise each one.

### What already works and should be kept

- **The handbook** ([handbook-page-cover], [handbook-page-chapter]): cream paper, a red binding, rubber stamps, redaction bars and Roz's handwritten margin. It is diegetic and on tone. Keep it; give it a texture and a font.
- **The CRT language** ([hud-transmission-t05], [hud-transmission-t02]): phosphor green, typewriter text, and the sender header coloured by source (live, relay, unknown). Right idea; it needs a machine around it and a font, not a replacement.
- **Real darkness below ground** ([layer-1-cave-as-played-no-light], [layer-2-cave-as-played-no-light]). The original was fully lit; ours is not, and this is our strongest dread tool.
- **The lampless figure fading near light** ([lampless-figure-fading-by-a-lit-pod], [lampless-figure-dark-no-night-vision]). A pure black shape at the edge of the light is the right idea.
- **The Prospector wreck with one lamp still lit** ([structure-wreck-prospector-0002-no-night-vision]). This is the lamp motif in one picture.
- **The Conduit** as a striped vertical landmark at the same place in every layer ([conduit-from-the-north]). Keep the idea; make it much bigger.
- **Ore colour coding** (orange Bronzium, yellow Goldium, cyan Einsteinium, red Cicatrium). It matches the original's readable colours.
- **The breach event** ([hud-breach-fade]): the fade, the shaking altimeter, then a transmission.

---

## 2. The target in words

*Prosperity at dusk.* One page, for reading without the code.

**The world.** A rust-red planet, never named in the game. (The lore's guardrail says "no Mars": it may look like Mars, it must never be called Mars.) Bare regolith in dusty reds, ochres and burnt umber, broken by black basalt, craters and rifts that drop out of sight. No grass, no trees, no animals. Wind, dust and silence. The world feels used and abandoned, not wild.

**The sky.** Held at dusk. A small, pale sun sits low and never climbs; around it is a cold blue glow, as on real Martian evenings ([NASA SVS 11875](https://svs.gsfc.nasa.gov/11875)). Away from the sun the sky is rust going to maroon, and at the horizon a dust haze swallows distance. Gameplay "night" is the same sky gone near-black, with hard stars. Motherload's mood, in one fixed hour.

**The palette.** Three families, each with a job:

- *The land:* iron-oxide reds and ochres, basalt black, dust beige. Low contrast, so it stays behind everything else.
- *The Company:* bronze and brass (the Founder, the bull's-head logo), cream enamel signs with red lettering, black-and-yellow hazard bands, sodium-amber lamps. Cheerful, gaudy, a little too clean: the well-lit offices of the Screwtape letters.
- *Down below:* the original's depth bands, darkened and desaturated: brown going black in layer 1, a near-black green in layer 2, then teal, navy, plum, amber and red with depth, black at the bottom.

**Light: three kinds, and the story is in which is which.**

- *The true lamp:* warm white, steady, small. A miner's lamp, a pod's headlamps, the chapel candle that nobody tends. Faith, quietly.
- *Company light:* sodium amber, buzzing, everywhere in the colony and on Company machines. It looks like warmth and is not. "Moloch's glow is counterfeit light."
- *The deep's own light:* cold and living (fungal glow in layer 3, crystal pulses in layer 5). Not ours, not the Company's.

Underground, darkness is the default. The lamp's radius is the only safe place. Fog eats distance at 30 to 50 blocks, and dust drifts through the lamp beam.

**Silhouettes.** Big, simple, readable shapes. Motherload's rounded capsules and domes, crossed with the Atlantis film's heavy, angular, riveted machines. That film's look came largely from Mike Mignola: flat graphic shapes and heavy black shadows ([Wikipedia](https://en.wikipedia.org/wiki/Atlantis:_The_Lost_Empire)). That style suits blocks better than smooth realism does: detail goes into the texture and the light, not into tiny geometry.

**Materials.** Worn industrial, about a century out of date: riveted plate steel, brass fittings, cast iron, rubber treads, glass canopies, caged sodium lamps, cables and pipes, enamel signs, soot and exhaust stains, rust weeping from rivets, dust drifted against every wall. Thirty-eight years of nobody.

**Scale.** Small people and pods in very large spaces. A few huge landmarks: the headframe over the Conduit, the hangar, the Founder statue. Below, caverns whose far wall the lamp cannot reach.

**Texture density.** 16x, like vanilla, with detail in layers rather than resolution (the GTNH method: a base, an overlay, a glow layer, an "active" state). Most detail where the eye rests: pods, terminals, landmarks. Terrain stays quiet so lit objects stand out.

**How dread and mystery show.**

- Darkness first, then a lamp. Never the reverse.
- Things at the edge of the light: a shape that should not be there, gone when you turn the lamp on it.
- Absence instead of gore: made-up bunks, a lit lamp in an empty cab, a punch clock still ticking, a candle that has burned for 38 years.
- Vertical voids: the Conduit going down past the light, a rift with no bottom.
- Sound before sight: a creak, a hum, a hymn on the sonar.
- The Company is always bright and always smiling. The deeper you go, the less its light reaches.

---

## 3. Options per area

Each option gives what it looks like, a reference, what it serves, the cost and tools ([tooling-options.md](tooling-options.md)), whether it is a **drop-in skin** (resource pack or data file) or **needs code**, and its risks. **Recommended** marks one per area, with the reason. ⚠ SPEC marks a choice that changes a settled decision.

**Constraints from the tall-world work** ([ADR 0029](../adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md), measurements in #185 and PR #187), which every surface and sky option below respects:

- **Our own generator.** The surface is the top band of one tall campaign world. Vanilla terrain there costs 1.8x per column and floods the layer caves with lava; our own dry surface costs about 1.03x. So the vanilla surface goes in any case.
- **One condition per block.** The surface material rule runs on every solid block of a column about 2,352 blocks tall. Two conditions cost about 20% more. Variety (craters, terraces, colour bands) comes from noise, density functions and block choice, not stacked rule conditions.
- **No sea.** `sea_level` is the world's bottom and there are no aquifers: no oceans, lakes or pools.
- **Sky:** a fixed dusk or night sky and custom fog are free choices on the overworld dimension type.
- **Features cost extra.** Surface props, rocks and structures (the colony's dressing included) add cost on top of 1.03x. Each such option below says it needs measuring.
- **Layer atmosphere lives in biomes.** In one tall world a layer is a Y band, so its fog, light tint and ambient colour come from biome attributes, not from its own dimension type. The dimension-level `ambient_light` number is shared by the surface and every layer; a layer's darkness has to come from `ambient_light_color`, fog and light tint. Worth checking in the game that this gives enough range.

### 3.1 Sky and light (the surface)

**A. Eternal dusk (visual timeline).** **Recommended.**

- Looks: a low sun fixed near the horizon with a pale-blue halo, rust-to-maroon sky, ochre dust haze, no clouds. Gameplay night darkens it to near-black with stars.
- Reference: real Martian sunsets ([NASA SVS 11875](https://svs.gsfc.nasa.gov/11875), [APOD 2015-05-12](https://apod.nasa.gov/apod/ap150512.html)); tryout stills in [section 4](#4-tryouts-in-the-game).
- Serves: the user's "eternally dusk"; Motherload's dark mood; lamps matter at the colony.
- Cost: low. One timeline file and one tag ([tryouts/dusk-sky.json](tryouts/dusk-sky.json)), tuned in the game. A GameTest samples the sky at noon and midnight.
- Skin or code: **drop-in data**. A client atmosphere layer (one reload listener, tooling doc) would let F3+T reload it; optional.
- Risks: the timeline must come after `minecraft:day` in the tag; the tryout confirmed that it then overrides vanilla's day and the biome's sky. The big square vanilla sun on the horizon looks out of place; a small, round sun is a texture swap (`textures/environment/celestial/sun.png`). The blue glow needs a lower alpha than the tryout's.

**B. Eternal night.**

- Looks: the sun never rises. Near-black sky with a faint rust horizon, bright stars, a small moon. The colony is carried by its own lamps.
- Reference: tryout stills in [section 4](#4-tryouts-in-the-game); Motherload's night half; [Iron Lung](https://en.wikipedia.org/wiki/Iron_Lung_(video_game)) (a rusty vehicle in total dark).
- Serves: the most dread. Lamps become the whole picture.
- Cost: low (data), but the colony then needs many working lamps to be readable, and the surface bootstrap is harder to play in the dark.
- Skin or code: **drop-in data**, plus lamp blocks in the colony.
- Risks: hard to read the terrain; players may raise gamma and lose the look. Less "Mars", more "deep space".

**C. Fixed time on the dimension type (`has_fixed_time`).**

- Looks: the same as A or B, but frozen. No change between "day" and "night" at all.
- Serves: simplest possible "eternal".
- Cost: very low: a few lines in the overworld dimension type.
- Skin or code: **drop-in data**.
- Risks: it freezes gameplay time too. Vanilla's `isDarkOutside` then always says "not dark", which breaks sleeping and anything else that waits for night ([tooling doc](tooling-options.md#3-sky-atmosphere-and-lighting)). With our own surface there are no villages, foxes or patrols left to break, so the cost is mostly beds and the absence of any rhythm.

**D. A slow dusk-to-night cycle (a "long day").**

- Looks: dusk and night alternate over a long period (for example 2 to 3 real hours), never full day. The sky is a clock you notice.
- Reference: the original's day and night cycle, slowed and never bright.
- Serves: some rhythm and variety without daylight; night can bring the surface's own dangers.
- Cost: low (data; a timeline with a longer `period_ticks` on its own clock).
- Skin or code: **drop-in data**; a gameplay day of a different length needs checking against beds and spawning.
- Risks: two looks to tune instead of one.

**Why A:** it is the user's own words, it costs one data file, and it keeps gameplay day and night (beds, spawning) separate from the look. Look and gameplay are separate dials in 26.3: the timeline can hold the sky at dusk while `gameplay/sky_light_level` still decides whether monsters spawn.

**Sky extras (any option):** a different moon or a sister planet is a texture swap, but vanilla draws one moon at a fixed size; a large planet or two moons need the optional Nuit mod or a mixin (tooling doc). Dust in the air is an `ambient_particles` attribute (data), with a dust particle of our own (see [section 4](#4-tryouts-in-the-game), finding 11).

### 3.2 The surface: terrain, blocks, colour, flora

All options are our own generator (constraint above). Variety comes from noise and density functions, and from **weighted random block-model variants**: a blockstate can pick one of several models and rotations per block from a resource pack, which costs nothing at world generation.

**A. Regolith plains, craters and mesas.** **Recommended.**

- Looks: rolling rust regolith with dust-pale and dark-oxide patches, scattered impact craters, terraced mesas on the horizon, black basalt outcrops. No flora, no fauna.
- Reference: rover panoramas, for example Curiosity at Gale Crater ([NASA image use is generally free](https://www.nasa.gov/nasa-brand-center/images-and-media/)); Motherload's bare surface; [SteamWorld Dig](https://en.wikipedia.org/wiki/SteamWorld_Dig)'s desert town over a deep mine.
- Serves: removes the single biggest "Minecraft" signal. Matches the original's bare soil and the abandoned-colony tone.
- Cost: medium. Density functions for craters (`distance_to_point`) and terraces (`floor`/`round`), one noise condition choosing between two regolith blocks, new blocks with 2 to 4 weighted texture variants. Python texture generator.
- Skin or code: **data** (worldgen JSON) and **skin** (textures); new block ids need one registration line each.
- Risks: a bare plain can look empty and samey. The fix is landmarks (rifts, wrecks, survey stakes), and each prop is a feature with a cost that needs measuring.

**B. Rifts and black badlands.**

- Looks: dark basalt and ash plains cut by deep, narrow rifts that drop out of sight toward the layers; dust-red only in drifts.
- Reference: real lava plains and canyons; the "black-fog rift" biome in the tooling doc.
- Serves: dread from the first frame: the ground already opens into the dark. Hints that the deep is close.
- Cost: medium, as A. Rifts from density functions; check that open rifts do not raise per-column cost or expose layer caves to sky light (sky light falls straight down an open shaft).
- Skin or code: **data** and **skin**.
- Risks: less "red planet" and less warm; harder to walk and build on. Works best as one biome of A, not the whole surface.

**C. A terraformed pocket by the colony (an "oasis").**

- Looks: a fenced ring of Company terraforming around the colony: dark soil in planters, pale sickly crops under glass domes, a few imported trees, irrigation pipes, the rest of the planet bare.
- Reference: greenhouse domes in films such as *The Martian*; the Company's "Deeper Together!" optimism.
- Serves: keeps SPEC §4's wood-and-crafting bootstrap (trees for wood) inside a believable story.
- Cost: medium to high: props and planters are features (needs measuring); crops and trees need soil and light. **Breaks the "no sea" constraint if it uses open water**; irrigation must be pipes and farmland moisture only.
- Skin or code: **data** plus code for any non-vanilla growth.
- Risks: brings green back into the first frame. It must stay small and look failing.

**D. Keep vanilla terrain, change only sky and colour.**

- Looks: the tryouts in [section 4](#4-tryouts-in-the-game): savanna under a dusk sky.
- Cost: lowest, but **ruled out by the tall-world measurements**: vanilla terrain costs 1.8x per column and floods the layers with lava. It would need a seam at the surface floor or a generator wrapper (ADR 0029, Consequences).
- Listed so the cost of "just recolour it" is on record. The tryouts also show that a dusk sky over grass and acacias still reads as Minecraft.

**Flora and fauna (with A or B):** recommended none living. Dead things only, as props: dry white stalks, a fallen survey mast, fence posts. Vanilla night monsters on the surface (SPEC §3) say "Minecraft" as loudly as cows do; the creatures session decides what replaces them.

**Bootstrap materials** ⚠ SPEC §4: a bare planet has no trees. Options: (1) the colony is the forest: crates, pallets, bunks and scaffolding are salvage for wood, and scrap metal for tools; (2) option C's small terraformed pocket; (3) a Company supply drop with a starter crate. Recommended (1): it turns the colony itself into the first resource, which suits "a world that feels merely abandoned".

### 3.3 The colony

The colony is built once at spawn, so it is not a per-chunk worldgen cost, but its props are still features: **needs measuring**. Every option moves the layout to structure templates (`.nbt`) built by a Python script, which amends ADR 0016 (tooling doc, section 4).

**A. Prefab Company town.**

- Looks: corrugated-steel Quonset huts and pressurised modules on stilts, catwalks, pipe runs, sodium floodlights on poles (some dead, one flickering), cream enamel signs with red Company lettering, the bull's-head logo, cable drums, dust drifted against every wall.
- Reference: [Quonset huts](https://en.wikipedia.org/wiki/Quonset_hut); the ghost mining town of [Kennecott, Alaska](https://en.wikipedia.org/wiki/Kennecott,_Alaska); [Create](https://modrinth.com/mod/create)'s brass-and-casing machines, which show 16x can read as precise machinery when the models carry the shape.
- Serves: the company town of the lore (400 bunks, pay office, the Lamp & Pick). Lamps can tell the story.
- Cost: medium to high. 15 to 25 new blocks (corrugated steel, riveted plate, grating, pipe, lamp, sign panels), JSON models with free rotation, `.nbt` pieces.
- Skin or code: blocks and textures are **skins**; new block ids and the template placement need **code once**.
- Risks: many small props can turn into clutter. Keep it to a few big readable pieces.

**B. Motherload frontier: clay domes and bold signs.**

- Looks: domed and vaulted buildings of regolith brick with steel ribs and pipes, each shop a single landmark with a huge sign readable from across the plain.
- Reference: the original's shops (studied privately; described, not copied); [SteamWorld Dig](https://en.wikipedia.org/wiki/SteamWorld_Dig)'s Tumbleton.
- Serves: Motherload's look most directly; very legible.
- Cost: medium. Fewer block kinds than A; domes are blocky in Minecraft and need careful stepping or rotated model elements.
- Skin or code: as A.
- Risks: closest to the original, so the most care is needed not to copy its building shapes or signs.

**C. The Company monument.**

- Looks: a monumental core: a steel headframe 40 to 60 blocks tall over the Conduit with a wheel at the top, a cathedral-scale processing plant of concrete and bronze, and a Founder statue three or four times human height, arms open, palms up, a slot in the chest. Searchlights sweep the sky.
- Reference: mine [headframes](https://en.wikipedia.org/wiki/Headframe) (the "gallows frames" of Butte, Montana); the idol's silhouette in the lore.
- Serves: scale (a landmark visible from far away); the lore's idol hidden in plain sight; the Conduit becomes the centre of the town.
- Cost: high for the headframe (a large structure with many custom blocks) but one-time.
- Skin or code: as A.
- Risks: a monument with no human scale feels like a theme park. It needs the small town around it.

**D. A + C together.** **Recommended.**

- The miners' prefab town (A: small, warm, human, abandoned) under the Company's monument (C: huge, bronze, still lit). Each terminal building gets one big bold sign, as in B.
- Why: the contrast is the story. The miners' lamps are small and warm; the Company's light is big and amber and everywhere. And the headframe gives the colony a skyline.

**Must do in any option:** redesign the Founder statue (see cause 4). Raise building height to two or three storeys with roofs; replace castle-notch ruins with collapsed roofs, missing panels and open doors; light the chapel with its one candle so it is the only white light in town.

### 3.4 The pods (Mole and Prospector, and the ladder above them)

Every option needs a real entity model: Blockbench sources, GeckoLib 5.5.7 at runtime (jar-in-jar, an ADR), glowmasks, part tiers as bones (tooling doc, section 2). **Needs code once** (the renderer, bones, animation hooks); after that the model, texture and animations are **skins**. SPEC §15 already says "chunky pod models made in Blockbench".

**A. The Motherload capsule.**

- Looks: a squat bell- or dome-shaped hull on tracks, a dark window band around the top, a big spiral drill cone at the front, two small antennae and an exhaust stack. A rotor folds out of the top to fly.
- Reference: the original's pod (studied privately, described here in words only).
- Serves: instant recognition for anyone who played Motherload; a heroic, slightly toy-like silhouette that reads at any distance.
- Cost: medium. About 80 to 150 cubes for the Mole; a 256x256 texture with a glowmask.
- Risks: too close to the original if copied; must be our own proportions and details.

**B. The Atlantis digger.**

- Looks: long and heavy like a locomotive, riveted plate, a churning cutter head of toothed rings at the front, twin caged headlamps, an exhaust stack, tracks under the front and big wheels behind.
- Reference: the Digger driven by "Mole" in *Atlantis: The Lost Empire* (2001): "a massive vehicle like a tank or locomotive with a huge spinning, churning drill at the front with many teeth", treads at the front and large rear wheels on a differential that drives the drill; the largest vehicle of the expedition's convoy ([Atlantis wiki: Digger](https://atlantisthelostempire.fandom.com/wiki/Digger)). The team studied early-20th-century technology for the vehicles; Matt Codd and Jim Martin drew the submarine *Ulysses*, and Mike Mignola, one of four production designers, set the film's angular style ([Wikipedia](https://en.wikipedia.org/wiki/Atlantis:_The_Lost_Empire)); concept art in [*The Art of Atlantis*](https://www.scbwi.org/books/0786853277) and [this gallery](https://characterdesignreferences.com/art-of-animation-1/art-of-atlantis-the-lost-empire).
- Serves: weight, menace and industry; the Company's machines.
- Cost: medium to high; a long body does not fit a 1.9-block Mole bore.
- Risks: a locomotive is wrong for a one-seat Mole that has to fly. Too big for the early game.

**C. A hybrid ladder: Motherload for the first chassis, Atlantis for the last.** **Recommended.**

- Looks: the chassis ladder grows from one reference to the other.
  - **Mole:** a Motherload-style capsule (A) built in Atlantis materials: riveted plates, brass trim, a caged headlamp pair, a spiral drill whose cone is made of cutter rings.
  - **Prospector:** longer, two seats in tandem under one canopy, a wider cutter head, a winch.
  - **Badger, Hauler, Crawler:** progressively longer, heavier and more locomotive-like.
  - **Behemoth:** the full Atlantis digger (B), the size of a building, with its anchor-mode outpost folded along its back.
- Serves: both references, each where it fits; the upgrade loop (pillar 1) shows on screen, because a bigger chassis looks like a bigger machine.
- Cost: as A for the Mole and Prospector now; the rest when their chassis are built.
- Risks: needs one shared detail kit (rivets, lamps, drill rings, tread links) so the ladder reads as one Company's product line.

**D. The diving bell.**

- Looks: a spherical riveted hull with round portholes and a drill bolted underneath; a bathysphere that digs.
- Reference: Beebe and Barton's [bathysphere](https://en.wikipedia.org/wiki/Bathysphere) (1930s); the sub-pods of *Atlantis*'s submarine *Ulysses* ([Atlantis wiki: Ulysses](https://atlantisthelostempire.fandom.com/wiki/Ulysses)).
- Serves: the dread of the deep directly, and layer 4 (the Drowned Deep).
- Risks: reads as a submarine, not a digger; fights the treads-and-rotor movement.

**In any option:**

- **Drill:** spins while drilling (code: one rotation in the animation hook, no keyframes); dust and sparks particles; it glows dull red when hot.
- **Lamps:** emissive lenses (glowmask) plus long translucent emissive beam cones that fade along their length, so a lit pod shows a beam in the murk. The real light stays the ledgered light block (ADR 0024). Unlit pod: dark lenses, no beam.
- **Animation:** tread scroll matched to speed, rotor fold-out on take-off, a tilt when flying sideways, an engine shake at idle.
- **The pilot sits inside.** A canopy (glass with frame), the player model hidden or seated low; first person shows the canopy frame as a HUD overlay (see 3.7).
- **Variants:** the derelict Mole (dust, flat tracks, dark lamps, a tarp), the wreck (scorched, cracked canopy, one lamp maybe still lit), and paint per charter (a palette swap).

### 3.5 Texture style and resolution

**A. 16x, GTNH-style layers.** **Recommended.**

- Looks: vanilla-resolution textures with detail from layering: a base, an overlay (rivets, stencils, grime), a glow layer that stays bright in the dark, an "active" state, animated `.mcmeta` for flicker, connected casings for big machines.
- Reference: [GT5-Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial) (GTNH) overlays and `_GLOW` layers (tooling doc, section 1).
- Serves: the user's "detailed, like GTNH"; consistent with every vanilla texture still on screen (GUI, items, particles).
- Cost: low to medium. A committed Python generator (Pillow, numpy), palettes as data, a darkness preview at light levels 0, 3, 7 and 15.
- Skin or code: **skin**. Glow layers are block model data (`light_emission`); connected casings need one custom model class (code once).
- Risks: 16x limits fine detail; that is why the layers, glow and wear matter.

**B. 32x for our blocks only.**

- Looks: our machines and colony blocks at twice the pixels; terrain and vanilla stay 16x.
- Serves: finer rivets and signage on hero blocks.
- Cost: four times the pixels to draw and check; a mipmap fix (Better Mipmaps port) against shimmer.
- Skin or code: **skin** (plus the mip fix, code).
- Risks: a 32x block next to a 16x one looks out of place; distance shimmer. ⚠ SPEC §15 says 16x.

**C. 64x or HD everywhere.**

- Looks: a full HD resource pack for all blocks, ours and vanilla.
- Cost: very high; shimmer; heavy to generate and to review.
- Risks: far more work than the gain; the blocky shapes stay. ⚠ SPEC §15.

**D. Mixed density by kind.** Blocks and items at 16x (A), pods and creatures at vanilla entity density (one texel per model unit, about 256x256 for the Mole), GUI frames as nine-slice sprites. This is part of A in practice.

**Also decide** ⚠ SPEC §15: "Assets are AI-assisted, with the user curating." The tooling research recommends scripts written by agents and hand-directed tools only, with no AI image-model output, because of Modrinth's 2026 AI rules and unclear training data. Confirm this reading.

### 3.6 Layers 1 and 2: fog, palette, darkness, dressing

In the tall world these are biome attributes per zone (constraint above). A colour grade per layer can be added by the server when a player crosses a breach (a one-line `addPostEffect` call: code once, the grade file is a skin).

**A. The original's depth bands, darkened.** **Recommended.**

- Looks: layer 1 fog brown going black with depth (the original's surface brown to near-black), layer 2 a near-black green (its next band); dust motes in the lamp light; own rock blocks per layer (layer 1: rust-brown packed regolith going to dark shale; layer 2: grey-green shale scarred with old workings).
- Reference: the original's depth colours, recorded in the private `REFERENCE.md`; tryout stills in [section 4](#4-tryouts-in-the-game).
- Serves: the felt sense of going down through bands, from Motherload; real darkness, from us.
- Cost: low for fog and light (data); medium for new rock blocks (textures and worldgen).
- Skin or code: **data** and **skin**.
- Risks: fog colour is barely visible in the dark; it shows in the lamp light and at the edge of fog. Must be tuned as played, without night vision.

**B. Pure black.**

- Looks: black fog, very short; only the lamp shows anything. Layers differ by rock texture and dressing only.
- Serves: maximum dread.
- Cost: lowest.
- Risks: layers lose their identity; no sense of "a new place" after a breach.

**C. The anatomy palette (hidden reading).**

- Looks: layer 1 strata in faint flesh-ochre bands with fine veining (the lore's "skin"); layer 2 pale, fibrous, ropy rock around the old workings ("scar tissue").
- Reference: the lore's hidden layer readings (LORE §5).
- Serves: mystery: the player half-notices the rock looks wrong, and the game never explains why.
- Cost: medium (textures).
- Risks: too obvious becomes body horror and breaks guardrail 7 (no gore). Keep it to a texture detail inside option A.

**Dressing (any option):** Company infrastructure (cable runs on brackets, lamp cages with dead bulbs, survey stakes with tags, timber sets stencilled with the Company mark), miners' traces (candle niches, lunch pails, prayer cards), the Conduit's casing as a huge riveted pipe. Redesign **Company Rock** as something that belongs in rock (a basalt plug or Company-poured concrete with a stencil), **lava** as flows and falls rather than single cubes, the **gas pocket** as a faint discoloured stone the scanner reveals, the **breach crust** as a cracked, layered crust that looks like a floor you should not break. Replace bookshelves, quartz and sea lanterns in structures with our own props (a time-card rack, a station clock with its own glow, ore carts). All structures are features: **needs measuring**.

### 3.7 UI, HUD and terminals

**A. Diegetic machine panels.** **Recommended for terminals.**

- Looks: each terminal screen sits inside a riveted metal bezel with a rounded CRT glass, a Company nameplate with the bull logo and a model number, physical bevelled buttons, cogs and screws in the corners, grime. The CRT inside keeps today's phosphor green and typewriter text.
- Reference: the original's shop screens (described in the tooling doc, section 5); [Fallout's Pip-Boy and terminals](https://en.wikipedia.org/wiki/Fallout_(series)) as a well-known diegetic UI.
- Serves: Motherload's shops; the Company's voice gets a home.
- Cost: medium. Move `CrtTuning` colours to a JSON skin, draw frames and buttons as nine-slice GUI sprites, one font id (`deepcharter:terminal`), a pixel font (Unscii, VT323 or Departure Mono, all free licences).
- Skin or code: **code once** (the skin loader and sprite drawing), then **skin**.
- Risks: each screen's layout is still code; a bad bezel can waste screen space.

**B. Refined pure CRT.**

- Looks: today's full-screen terminal, refined: a curved-glass vignette, scanlines, a little flicker, a proper font, no outer machine.
- Cost: low to medium (sprites and a font; flicker needs a GUI shader).
- Risks: still reads as "a screen over the game", not a machine in the world.

**C. Pod cockpit HUD.** **Recommended for the pod.**

- Looks: while piloting, the view is framed by the canopy rim (an overlay sprite); gauges sit on the dash at the bottom: a hull cylinder, a fuel can with F and E, an altimeter drum, the scanner as a round sonar screen. The vanilla hotbar, hearts, held item and "dismount" hint are hidden while piloting.
- Reference: the original's red hull cylinder, amber fuel can and yellow altimeter (described); [Iron Lung](https://en.wikipedia.org/wiki/Iron_Lung_(video_game))'s cockpit-only view.
- Serves: immersion: you are inside a machine. The scanner as a sonar screen suits the navigator's role.
- Cost: medium. Sprites; code to hide vanilla HUD parts while riding.
- Skin or code: **code once**, then **skin**.

**D. Re-skinned vanilla HUD on foot.** The hotbar, hearts and hunger re-drawn as a suit display (oxygen, suit battery) through vanilla GUI sprite overrides: **skin only**. Recommended alongside A and C, later.

Keep the handbook as paper; give it a paper texture and a typewriter and a handwriting font.

### 3.8 Lighting and glow

**A. Glow accents, dark world.** **Recommended (with C).**

- Looks: small things glow: terminal screens, gauge needles, lamp lenses, the chapel candle, the Conduit's warning lamps. The world around them stays black. Ores glint only in lamp light (not emissive), so the scanner and the lamp keep their value.
- Reference: GTNH glow layers; the Warden's pulsing glow in vanilla.
- Cost: low (glow textures, `light_emission` in block models, emissive entity layers).
- Skin or code: **skin** for blocks; one emissive render layer for entities (code once).
- Risks: too many glowing things and the dark stops being dark.

**B. Dynamic lights (optional mod).**

- Looks: held lamps and moving pods light the world smoothly as they move.
- Cost: none to us; LambDynamicLights is an optional player install ⚠ SPEC §2 if it goes in the pack.
- Risks: friends without it see the plain ledgered light only.

**C. Fake volumetric beams.**

- Looks: translucent emissive cones from pod headlamps and colony floodlights that fade along their length; dust particles inside them.
- Cost: low to medium (model elements and one render layer).
- Skin or code: **skin** once the entity layer exists.
- Risks: beams pass through walls if not shortened by a raycast (code).

**D. Shader pack (opt-in).**

- Looks: real volumetric fog and light, shadows, through Iris and a shader pack.
- Cost: none to us; an opt-in for friends. ⚠ SPEC §2 if bundled.
- Risks: shader packs draw their own sky and fog, so our sky may be lost (unverified); macOS OpenGL limits; Iris crashes on the Vulkan backend.

**Light colours (any option):** true lamps warm white; Company lamps sodium amber; the deep's own light cold. The layers' `block_light_tint` sets the colour of every lamp below; compare the sodium and cold tryouts in [section 4](#4-tryouts-in-the-game).

### 3.9 Sound and music (described only)

The friends build keeps the private pack of the original's audio (SPEC §2); nothing here uses it. This is the direction for our own sounds, made with numpy, scipy and ffmpeg recipes in the repo (tooling doc, section 6).

**A. Drone ambience per layer.** **Recommended (with the hymn motif from B).**

- Sounds like: on the surface, wind, dust hiss and a far metallic groan; below, low sub-bass drones, slow beating tones, filtered noise, distant creaks and drips, rare unexplained knocks. Sparse.
- Serves: dread; each layer has a sound identity; breaches feel like a change of place.
- Cost: medium. Data-wired through `ambient_sounds` (loop, mood, additions) per biome.

**B. A melodic theme around the Lamp Hymn.**

- Sounds like: a short original hymn melody (the lore's "Lamp Hymn") on a lone instrument at the colony, coming back distorted and slower with depth; on the sonar in layer 4.
- Serves: quiet faith; a musical motif that pays off in the story.
- Cost: medium to high (composition). Must be original: guardrail 4, no quoted hymns.

**C. Diegetic only.**

- Sounds like: no score. The colony's PA plays Joy's cheerful recorded announcements; sodium lamps buzz; the Conduit hums; the pod's engine, drill and rotor carry the play.
- Serves: realism and the Company's voice.
- Risks: long silent stretches; less emotional range.

**In any option:** the pod should sound like a machine (an engine whose pitch follows idle, driving and drilling, as in the original), terminals click and hum, the drill grinds differently in each rock. Positional sounds need mono Vorbis, which this Mac's ffmpeg cannot write yet (tooling doc).

### 3.10 The lampless figure's look

Its behaviour belongs to the creatures session (#13). These are looks only; each needs a model (code once), and the texture is a skin.

**A. A miner without a lamp.** **Recommended.**

- Looks: a tall, thin miner in a long coat and helmet, entirely matte black, with an empty lamp bracket on the helmet. No face. Head slightly bowed, arms at its sides. It absorbs light: it stays black even in the lamp beam.
- Serves: the lore exactly (the Retained "no longer need a lamp"; it might be Tomas). Mignola-style black silhouette.
- Risks: needs a custom model; the vanilla zombie pose must go.

**B. A faded miner.**

- Looks: a normal miner model, grey and desaturated, an unlit lamp, turning translucent near light (today's fade).
- Serves: makes it clear they were people.
- Risks: less frightening; risks looking like a ghost from another game.

**C. Wrong proportions.**

- Looks: like A, but a little too tall, limbs a little too long, edges smeared (texture noise at the outline), moves too smoothly.
- Serves: wrongness without gore.
- Risks: easy to overdo into a monster. Best used as a subtle touch on A.

---

## 4. Tryouts in the game

Four looks, made with data files only (no code), shot in the game from the same views on the design-tour seed. In every grid:

| | |
|---|---|
| **Top left:** today | **Top right:** eternal dusk (3.1 A) |
| **Bottom left:** eternal night (3.1 B) | **Bottom right:** eternal dusk plus a colour grade |

- **Surface:** the dusk and night skies are timelines ([dusk-sky.json](tryouts/dusk-sky.json), [night-sky.json](tryouts/night-sky.json)) added after vanilla's day in the overworld's timeline tag ([in_overworld.json](tryouts/in_overworld.json)). The terrain is still the vanilla savanna, because only the sky was changed.
- **Layers, in the three new looks:** the fog colours follow the original's depth bands (layer 1 brown going black, layer 2 a near-black green), fog from 2 to 40 blocks in layer 1 and 2 to 28 in layer 2, a darker ambient colour, and faint drifting ash. The lamp tint (`block_light_tint`) is sodium amber in dusk and grade, cold white in night. Each layer view has one lamp-strength light (level 14) at the camera, as a pod's lights part places, and no night vision.
- **Grade:** a post effect ([grade.json](tryouts/grade.json), [grade.fsh](tryouts/grade.fsh)) that pulls greens toward olive-brown, lowers saturation, warms the shadows and adds a vignette.

**Surface, looking south:**

![tryout-surface-south-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-surface-south-day.png?raw=true)

**Surface, looking west toward the sun:**

![tryout-surface-west-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-surface-west-day.png?raw=true)

**The colony from the air:**

![tryout-colony-aerial-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-colony-aerial-day.png?raw=true)

**The colony from the south edge, at gameplay night:**

![tryout-colony-south-edge-night](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-colony-south-edge-night.png?raw=true)

**Layer 2, by lamp light (amber in dusk and grade, cold white in night):**

![tryout-layer-2-lamp-east](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-2-lamp-east.png?raw=true)

**The Prospector wreck in layer 2, as played:**

![tryout-layer-2-wreck-as-played](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-2-wreck-as-played.png?raw=true)

**Layer 1, a large cavern by lamp light:**

![tryout-layer-1-lamp-west](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-1-lamp-west.png?raw=true)

More grids: [surface east](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-surface-east-day.png?raw=true), [surface south at night](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-surface-south-night.png?raw=true), [colony from the air at night](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-colony-aerial-night.png?raw=true), [colony from the south edge by day](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-colony-south-edge-day.png?raw=true), layer 1 [south](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-1-lamp-south.png?raw=true) and [east](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-1-lamp-east.png?raw=true), layer 2 [south](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-2-lamp-south.png?raw=true) and [west](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/tryout-layer-2-lamp-west.png?raw=true). Every single still is also on `pr-media/234/`, named `<look>--<view>.png` (for example [dusk--surface-south-day](https://github.com/pkeppeler/deepcharter/blob/pr-media/234/dusk--surface-south-day.png?raw=true)).

### What the tryouts show

1. **The sky alone changes the mood a lot, for one data file.** The dusk timeline turns the blue noon into a rust evening with a haze; the night timeline gives a maroon starfield.
2. **The sky alone cannot hide Minecraft.** Grass, acacias and the colony's vanilla walls still read as "Minecraft at sunset". Even with the grade, the tree shapes give it away. This confirms that the surface (3.2) and the colony materials (3.3) must change, not only the light.
3. **The override works.** A timeline listed after `minecraft:day` in the tag wins over vanilla's day and over the savanna's own sky colour. This settles the open question in the tooling doc.
4. **The vanilla sun is now the most "Minecraft" thing in the sky.** At a fixed low angle the big white square sits on the horizon (surface west). Replace it with a small, round, pale sun texture.
5. **The pale-blue sun glow is too wide at this strength.** It paints a grey band across a third of the sky (colony from the air). Lower its alpha and keep it near the sun.
6. **Night (3.1 B) is moody, but the colony is barely readable** without its own lamps. Night needs the lit colony of 3.3 first.
7. **Lamp tint is a strong, cheap lever below ground.** Amber warms the rock; cold white turns layer 2 blue-grey and clinical. It is the simplest way to tell the true lamp, Company light and the deep's own light apart (3.8), and to give each layer a mood.
8. **Layer 2's near-black green fog reads as a different place** from layer 1's brown, even in a dark scene. Good for "you are somewhere new" after a breach.
9. **Fog colour shows only at the edge of the light.** In layer 1's large cavern the lamp does not reach the walls, so the views are mostly fog and darkness. Tune fog as played, never under night vision.
10. **One grade everywhere is wrong.** It unifies the surface palette but crushes the layers to near black. Use a grade per place: a stronger one on the surface, a light one below.
11. **Vanilla white ash reads as square flakes near the camera.** Drifting dust needs a particle of our own.

**Recommendation after the tryouts:** eternal dusk (3.1 A) with a smaller sun glow and a new sun texture; per-layer fog from the depth bands; amber for Company light and warm white for true lamps; a per-place grade later, once the new surface exists.

To try a look: copy `dusk-sky.json` or `night-sky.json` to `data/deepcharter/timeline/tryout_sky.json` and `in_overworld.json` to `data/minecraft/tags/timeline/in_overworld.json` in any data pack, then open a new world. For the grade, copy `grade.json` to `assets/deepcharter/post_effect/tryout_grade.json` and `grade.fsh` to `assets/deepcharter/shaders/post/tryout_grade.fsh`, then run `/posteffect add @s deepcharter:tryout_grade`.

*How they were shot:* a throwaway scenario (`art-tryout`) on a detached worktree with PR #220's harness fix, never committed. The Mac's screen locked during the session, which stops OpenGL client runs; the Vulkan backend (`--graphicsBackend vulkan`) ran normally on the locked screen.

---

## 5. Decisions for the user

**Settled decisions these options change:**

1. ⚠ **SPEC §3, the surface:** "vanilla-style frontier (terrain, trees, animals, vanilla ores near the surface for the bootstrap, vanilla night monsters)". Every surface option replaces it (ADR 0029 already rules out vanilla terrain in the campaign world). What replaces trees, animals and night monsters needs a call.
2. ⚠ **SPEC §4, the bootstrap:** "Start like normal Minecraft: hand-gathering and crafting." With no trees, wood and early tools need another source: colony salvage (recommended), a terraformed pocket, or a supply crate.
3. ⚠ **SPEC §15, art:** "consistent with vanilla (16x textures)". Option 3.5 A keeps 16x; B and C change it.
4. ⚠ **SPEC §15, "Assets are AI-assisted, with the user curating":** confirm that this means agent-written generators and hand-directed tools, not AI image models (tooling doc's recommendation).
5. ⚠ **SPEC §2, pack contents:** "the mod plus Sodium and Lithium". GeckoLib as jar-in-jar keeps it true. Bundling LambDynamicLights, Nuit, Sound Physics Remastered or a shader pack changes it; recommending them as optional extras does not.
6. **ADR 0016 (colony built in code):** structure templates for the colony amend it; needs a new ADR (not a SPEC change, but hard to reverse).
7. **Lore guardrail 9 ("no Mars"):** not a change, a reminder. The world may look like Mars; no text, item or title calls it Mars.

**The calls, in the order they unblock work:**

1. Sky: eternal dusk, eternal night, frozen, or a slow dusk-to-night cycle (3.1)?
2. Surface: regolith plains, black rifts, a terraformed pocket, or a mix (3.2)? And the bootstrap's wood (above)?
3. Pods: Motherload capsule, Atlantis digger, the hybrid ladder, or the diving bell (3.4)?
4. Colony: prefab town, frontier domes, Company monument, or town plus monument (3.3)?
5. Texture resolution (3.5) and the AI-assets reading.
6. Terminals and HUD (3.7), lighting (3.8), layers (3.6), sound (3.9), the figure (3.10).

---

## Sources

- Our catalogue: [current-state.md](current-state.md) (PR #227); tooling: [tooling-options.md](tooling-options.md) (PR #228); lore: [LORE.md](../LORE.md) (PR #34); [ADR 0029](../adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md) (PR #219).
- *Atlantis: The Lost Empire*: [Wikipedia](https://en.wikipedia.org/wiki/Atlantis:_The_Lost_Empire) (production design, Mignola, vehicle designers); [Atlantis wiki: Digger](https://atlantisthelostempire.fandom.com/wiki/Digger); [Atlantis wiki: Ulysses](https://atlantisthelostempire.fandom.com/wiki/Ulysses); [Atlantis wiki: Aqua-Evac](https://atlantisthelostempire.fandom.com/wiki/Aqua-Evac); [D23 entry](https://d23.com/a-to-z/atlantis-the-lost-empire-film/); [concept art gallery](https://characterdesignreferences.com/art-of-animation-1/art-of-atlantis-the-lost-empire); *The Art of Atlantis: The Lost Empire* (2001, [ISBN 0786853277](https://www.scbwi.org/books/0786853277)). No image from the film is stored or committed.
- Mars light: [NASA SVS 11875](https://svs.gsfc.nasa.gov/11875) (blue Martian sunset), [APOD 2015-05-12](https://apod.nasa.gov/apod/ap150512.html), [NASA image guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/).
- Games named as references: [SteamWorld Dig](https://en.wikipedia.org/wiki/SteamWorld_Dig), [Iron Lung](https://en.wikipedia.org/wiki/Iron_Lung_(video_game)), [Fallout](https://en.wikipedia.org/wiki/Fallout_(series)); Minecraft mods: [GT5-Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial), [Create](https://modrinth.com/mod/create).
- Real places and things: [Headframe](https://en.wikipedia.org/wiki/Headframe), [Kennecott, Alaska](https://en.wikipedia.org/wiki/Kennecott,_Alaska), [Quonset hut](https://en.wikipedia.org/wiki/Quonset_hut), [Bathysphere](https://en.wikipedia.org/wiki/Bathysphere).
- Motherload: studied from the private local export only (`original_flash_game/`, git-ignored). Descriptions are in our own words; no file, frame or trace of it is in this repository.

<!-- Catalogue stills (PR #227) -->
[surface-south-noon]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-south-noon.png?raw=true
[surface-east-noon]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-east-noon.png?raw=true
[sky-up-noon]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/sky-up-noon.png?raw=true
[surface-south-dusk]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-south-dusk.png?raw=true
[sky-up-dusk]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/sky-up-dusk.png?raw=true
[surface-north-night]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/surface-north-night.png?raw=true
[colony-aerial-south]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-south.png?raw=true
[colony-aerial-northwest]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-northwest.png?raw=true
[colony-aerial-northeast]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-aerial-northeast.png?raw=true
[colony-from-the-south-edge]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/colony-from-the-south-edge.png?raw=true
[statue-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-from-the-square.png?raw=true
[statue-close]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-close.png?raw=true
[statue-hands-from-above]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/statue-hands-from-above.png?raw=true
[pay-office-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/pay-office-from-the-square.png?raw=true
[hangar-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-from-the-square.png?raw=true
[chapel-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/chapel-from-the-square.png?raw=true
[bunkhouse-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/bunkhouse-from-the-square.png?raw=true
[personnel-office-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/personnel-office-from-the-square.png?raw=true
[lamp-and-pick-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lamp-and-pick-from-the-square.png?raw=true
[continuity-office-inside]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/continuity-office-inside.png?raw=true
[conduit-from-the-square]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/conduit-from-the-square.png?raw=true
[conduit-from-the-north]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/conduit-from-the-north.png?raw=true
[mole-unlit-day-front]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-front.png?raw=true
[mole-unlit-day-side]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-day-side.png?raw=true
[prospector-unlit-day-front]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/prospector-unlit-day-front.png?raw=true
[hud-pod-in-third-person-surface]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-pod-in-third-person-surface.png?raw=true
[hud-pod-status-and-altimeter-surface]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-pod-status-and-altimeter-surface.png?raw=true
[hangar-founding-mole-repaired]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hangar-founding-mole-repaired.png?raw=true
[wrecks-mole-and-prospector-day]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/wrecks-mole-and-prospector-day.png?raw=true
[mole-unlit-dark-front]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-unlit-dark-front.png?raw=true
[mole-lit-dark-front]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/mole-lit-dark-front.png?raw=true
[layer-1-cave-south]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-south.png?raw=true
[layer-2-cave-south]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-south.png?raw=true
[layer-2-cave-east]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-east.png?raw=true
[layer-1-cave-as-played-no-light]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-cave-as-played-no-light.png?raw=true
[layer-2-cave-as-played-no-light]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-2-cave-as-played-no-light.png?raw=true
[layer-1-breach-crust-floor]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/layer-1-breach-crust-floor.png?raw=true
[texture-sheet-blocks]: https://github.com/pkeppeler/deepcharter/blob/pr-media/227/texture-sheet-blocks.png?raw=true
[texture-sheet-items]: https://github.com/pkeppeler/deepcharter/blob/pr-media/227/texture-sheet-items.png?raw=true
[block-gallery-1]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/block-gallery-1.png?raw=true
[block-gallery-2]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/block-gallery-2.png?raw=true
[items-gallery-1]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/items-gallery-1.png?raw=true
[screen-fuel-pump-online]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-fuel-pump-online.png?raw=true
[screen-ore-processor-online]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-ore-processor-online.png?raw=true
[screen-upgrade-terminal-online]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-upgrade-terminal-online.png?raw=true
[screen-repair-station-online]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-repair-station-online.png?raw=true
[screen-pod-cargo]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/screen-pod-cargo.png?raw=true
[structure-gallery-toward-the-rubble]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-gallery-toward-the-rubble.png?raw=true
[structure-punch-clock-overview]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-punch-clock-overview.png?raw=true
[structure-rails-long-drift]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-rails-long-drift.png?raw=true
[structure-wreck-prospector-0002]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002.png?raw=true
[structure-wreck-prospector-0002-no-night-vision]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/structure-wreck-prospector-0002-no-night-vision.png?raw=true
[lampless-figure-front]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-front.png?raw=true
[lampless-figure-side]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-side.png?raw=true
[lampless-figure-fading-by-a-lit-pod]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-fading-by-a-lit-pod.png?raw=true
[lampless-figure-dark-no-night-vision]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/lampless-figure-dark-no-night-vision.png?raw=true
[handbook-page-cover]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-cover.png?raw=true
[handbook-page-chapter]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/handbook-page-chapter.png?raw=true
[hud-transmission-t05]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-t05.png?raw=true
[hud-transmission-t02]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-transmission-t02.png?raw=true
[hud-breach-fade]: https://github.com/pkeppeler/deepcharter/blob/pr-media/317/hud-breach-fade.png?raw=true
