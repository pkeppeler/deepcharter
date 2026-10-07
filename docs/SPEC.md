# Deep Charter: vision and feature spec

Vision spec — settled 2026-10-06; numbers pending. Lore settled 2026-10-07 in [LORE.md](LORE.md).

Terms in **bold** or capitalised as glossary terms are defined in [CONTEXT.md](../CONTEXT.md). Original game numbers: [REFERENCE.md](../original_flash_game/REFERENCE.md).

## 1. Vision

Deep Charter is a total-conversion Minecraft mod: Motherload (XGen Studios, 2004 Flash game) crossed with Terraria and Minecraft, with unlimited depth. Mod ID `deepcharter` (permanent, baked into saved worlds); the display title may change before a public release.

Pillars, ranked. The higher one wins a conflict.

1. The upgrade loop
2. Dread and mystery
3. Exploration through layers
4. Sandbox building

## 2. Audience, platform and assets

- **Audience:** the user and friends, co-op multiplayer from day one. Designed so it could be released publicly later.
- **Group size:** 2–4 core, up to about 8 supported.
- **Platform:** Fabric, Minecraft 26.3 (unobfuscated, Java 25). See [ADR 0002](adr/0002-fabric-on-26-3.md).
- **Distribution:** one Modrinth modpack (the mod plus Sodium and Lithium), installed through Prism or a similar launcher.
- **Servers:** dedicated servers supported from day one. Hosted on the user's Mac first (friends outside the home network need port-forwarding or a tunnel); a rented host later.
- **Assets:** our own. The friends build uses a private resource pack with the user's extracted original Motherload music and sounds; it is never published. Soundtrack sourcing for a public build is open (possibly one per layer).

## 3. World

### Surface and colony

- **Surface:** vanilla-style frontier (terrain, trees, animals, vanilla ores near the surface for the bootstrap, vanilla night monsters). No villages or settlements. No Nether or End; the depths replace them.
- **Colony:** one derelict mining colony per world, at spawn, named Prosperity. Its terminals are repaired once per world: when any charter fixes one, every charter can use it. The contract terminal works from the start.
- **Conduit:** an undiggable Company pipe runs from the colony's ore processor straight down through every layer, at the same coordinates in each, ending in the Furnace. It is a landmark and the story's first clue ([LORE.md](LORE.md#8-prosperity)).
- **Width:** unlimited, no border.
- **Performance:** layer height drives cost, because Minecraft generates whole columns. Keep layers moderately tall. If world files grow large, add a tool to trim unvisited chunks.

### Layers

- The underground is a chain of layers. Each layer's floor leads into the top of the next.
- Each layer is its own dimension (own ambient light, fog, sky). The depth readout is computed: layer offset plus local Y. See [ADR 0003](adr/0003-depth-as-chained-layer-dimensions.md).
- Layers vary in thickness.
- **Zones:** each layer has 2–4 zones. Ore value, hazards, creatures and atmosphere step up between zones.

### Breaches

- A breach is a crust at each layer boundary. It is a soft gate: any drill can get through, slowly and painfully (heat, hull damage).
- Crossing is an event: rumble, short fade, a transmission, new music.
- You can go too deep too early, and you will regret it.

### Story layers

About 8 story layers, roughly 6–8 hours each: a 60+ hour campaign, finale at the bottom of the last. Themes are settled; each layer's lore, zones and beats are in [LORE.md](LORE.md#11-layers).

| # | Name | Theme |
|---|------|-------|
| 1 | The Claim | Dirt and stone, first ores (Ironium, Bronzium, Silverium, Goldium), previous miners' abandoned shafts. Teaches the loop. |
| 2 | The Old Workings | Collapsed colony mine levels, rails, salvageable pod wrecks (including the Prospector wreck), first garbled transmissions, something moving in the dark. |
| 3 | Fungal Hollows | Huge caverns of glowing fungi (natural light), spore-gas hazards, first real on-foot exploration. |
| 4 | The Drowned Deep | Flooded caverns and pressure (a Subnautica nod). The pod needs seals. The lost miners' story plays out here. |
| 5 | The Lattice | Crystal and geode, emerald and ruby. Sound resonance attracts things; scanner interference. |
| 6 | The Magma Belt | Lava rivers and heat; the radiator becomes vital. The employer starts saying "turn back". |
| 7 | The Ossuary | Colossal bones and ancient structures, things that aren't fossils. "THE EYES." |
| 8 | The Furnace | Head Office, the employer's true factories; the reveal; the final boss and the choice. |

### Ramp

A few hundred blocks at the bottom of the last story layer where drilling gets exponentially harder. You can continue, but it is not worth it. It makes the end of the campaign obvious.

### Uncharted layers

- Below the ramp, without limit. Remixes of story-layer themes, each much harder than the one above; rewards grow far more slowly than difficulty.
- A bragging-rights grind for the records board.
- The altimeter reads "UNCHARTED".
- No rated materials exist for them, so no outpost there can be made habitable.

### Splice rule

- A layer's content is fixed the first time any charter breaks into it.
- An update adding story layer N+1 splices it into the chain below the last story layer and above the ramp. Breaking through N's floor leads into the new layer; its floor leads into whatever already existed.
- Nothing built is lost. Existing layers keep their terrain; their depth readings shift.
- Players already down there get a "the depths moved" story event.
- No shaft can reach a spliced layer early, so pre-mining is impossible.

### Darkness

- Each layer sets its own ambient light, darker with depth.
- Pod lights are a component track. On foot, the suit lamp runs on a battery.
- The original was fully lit; Minecraft's real darkness adds to the dread.

## 4. Core loop and progression

### Bootstrap

- Start like normal Minecraft: hand-gathering and crafting.
- Repair the colony's terminals one by one (fuel pump, then ore processor, then upgrade terminal, and so on). Each is a crafting goal; the employer gets in touch as they come back online.
- The first charter also repairs the founding pod (the Mole) in the colony hangar.

### Economy

After the bootstrap, progression is money: sell ore at terminals, buy pod upgrades. Crafting remains for building and decoration.

- **Ore:** a physical, heavy, non-stacking item. Carrying it on foot slows you sharply, so the pod is the hauler. Ore can be stored, handed over and sold at terminals.
- **Cargo:** the cargo bay has a set number of slots. Weight reduces lift; too heavy to take off means dumping ore.
- **Ore names:** original-style (Ironium, Bronzium…) for shallow tiers, new minerals deeper.
- **Prices:** fixed base prices, plus employer work orders ("deliver 20 X for a bonus") that can carry story beats.
- **Numbers:** upgrade costs and ore values start from the original's ([REFERENCE.md](../original_flash_game/REFERENCE.md)), scaled to fit, then tuned in playtesting.
- **Catalysts:** every chassis, and every couple of component tiers, also needs a rare material found in one specific layer, on top of money.
- **Records board:** at the colony, one entry per charter: deepest breach, wealth hauled, wrecks salvaged and so on. No single score.

## 5. Employee Handbook and teaching the rules

The **Employee Handbook** is a company-issued onboarding manual.

- **Getting it:** issued when you sign your Employment Contract. It is bound to you, so it cannot be lost. Open it from the item or a keybind.
- **Terminals:** colony and outpost terminals also show the charter's open Directives.
- **Scope:** onboarding only, the bootstrap and layers 1–2. A linear, explicit tutorial: no cryptic wording, no side chapters, no helpdesk.

### Chapters

1. Bootstrap crafting
2. Repairing the colony terminals one by one
3. Repairing the Mole
4. Fuel, driving, flying and drilling
5. Selling and upgrading
6. The scanner
7. Layer 1's hazards
8. Your first breach
9. Salvaging the Prospector wreck in layer 2, ending at layer 2's floor

### Voice and visibility

- **Voice:** the employer's cheerful corporate voice. Previous miners' handwritten margin notes tell a different story. The handbook is "revised" five times as the story turns. Revisions change its tone, never its rules. Text, margin notes and revisions: [lore/handbook.md](lore/handbook.md).
- **Visibility:** show only the road just ahead, and never spoil.
  - The current Directives are explicit.
  - The next chapter shows its title and Directive list.
  - Anything further ahead appears only as CLASSIFIED.

### After layer 2

- The official chapters end with "Further documentation is restricted to Senior Personnel."
- From then on the back section fills only with **Notes** found in the world: wreck logs, torn pages, miners' scribbles.
- A Note picked up by anyone in a charter goes into every member's handbook, unread for each person.
- Revisions may also add stamped pages: a disciplinary notice, the Chairmen's Book or the Ledger. They carry no rules ([lore/handbook.md](lore/handbook.md#revisions)).

### Directives and progress

- **Directives** are the handbook's objectives. They complete automatically from game events: item obtained, block placed or repaired, layer entered, item crafted, or a custom trigger from our code.
- Directives give no rewards. The employer's transmission bonuses still exist separately.
- Progress is shared by the charter, with personal read marks for each player.

### Three channels

One job each. They can point at each other; a transmission can unlock a handbook chapter.

| Channel | Job |
|---------|-----|
| Handbook | How to progress (Directives). |
| Work orders | Repeatable money tasks. |
| Transmissions | The story. |

### The world is a mystery; the rules never are

There are no Directives after layer 2. Each new mechanic is explained the first time you meet it:

- A **Spec sheet** when you buy or craft something, for example a pad's power cost or a material's heat rating.
- A one-time HUD or scanner warning on first exposure, such as "PRESSURE EXCEEDS HULL RATING".

The story stays in Transmissions and Notes.

Implementation: [ADR 0005](adr/0005-build-our-own-handbook.md); research in [research/handbook-build-vs-reuse.md](research/handbook-build-vs-reuse.md).

## 6. Charters

- The employer issues charters. At the colony's contract terminal a player either:
  - founds a charter (names it, becomes its Director; it gets its own account), or
  - signs on with an existing charter as crew through an Employment Contract, which the Director approves.
- Anyone can leave and found their own.
- Each charter has its own account, pods, components, outposts, pads and story progress.
- Charters run in parallel and are friendly: no PvP, no contested ore. Competition might come in a later update.
- No money transfers between charters. Components are company property: bound to the charter and serial-numbered; installing another charter's parts voids them. Handing ore over physically is allowed.
- Outposts and pads belong to a charter, with a toggle to share them with other charters.

## 7. Pods

A pod is a vehicle with seats. There is no walkable interior while it moves. See [ADR 0004](adr/0004-pods-are-vehicles.md) and [research/walkable-pod-interior.md](research/walkable-pod-interior.md).

### Chassis

| # | Chassis | Seats | Where it comes from | Built for, and leaning |
|---|---------|-------|---------------------|------------------------|
| 1 | Mole | 1 | Founding pod: repaired in the colony hangar by the first charter; later charters buy a refurbished one | Layers 1–2 |
| 2 | Prospector | 2 | Wreck in layer 2, at the end of onboarding | Up to layer 3. The first crew seat (navigator) |
| 3 | Badger | 2–3 | Wreck in layer 3 or 4 | Up to layer 4. Adds the operator seat; leans toward the drill |
| 4 | Hauler | 3 | Wreck in layer 4 or 5 | Up to layer 5. Leans toward cargo |
| 5 | Crawler | 3–4 | Wreck in layer 6 | Up to layer 7. Leans toward armour against heat and pressure |
| 6 | Behemoth | 4–6 | Wreck in layer 7 | Everything below. Anchor mode |

- **A ladder with leanings:** each chassis is generally better than the one before, but each leans somewhere, so older chassis keep a job in a fleet: the old Hauler becomes the tender pod, the old Prospector the spotter.
- **Mole:** the founding pod, capped for layers 1–2.
- **Anchor mode (Behemoth):** parked and anchored, it unfolds into a small walkable temporary outpost (beds, storage, one terminal) and packs up to move. Walking only while anchored.

### Components

- Tiered parts installed in a chassis: drill, hull, engine, fuel tank, radiator, cargo bay, scanner, lights.
- They move over when you change chassis.
- Each chassis caps component tiers. Restoring a salvaged chassis takes money plus catalysts.

### Getting more pods

- **Founding pod:** the first charter repairs the hangar Mole. Later charters buy a refurbished Mole at the colony for a modest fee.
- **Wrecks:** more chassis come from salvaging wrecks in specific layers (see the chassis table). Tow one home and pay to restore it.
- **Registration fees** grow with every pod a charter adds.
- Each pod buys its own components.
- Together these stop a second charter's free Mole from being a cheap deep miner: a capped Mole stays a shallow helper.

### Movement

The original's rules, in 3D.

- Treads on the ground, a rotor to fly.
- Drill down and sideways in 4 horizontal directions. Never up.
- The bore is the size of the pod; bigger pods carve bigger tunnels.
- Only ore is kept; dirt and stone are destroyed. A "keep stone" upgrade is possible later.
- Hard landings cause damage. Drilling slows with depth.

### Fuel and stranded

- Fuel is the clock. It drains even idle, faster while moving and drilling. A beep warns when low.
- Early fuel is crafted (coal, charcoal, biofuel). Later it is bought at the pump.
- Running dry leaves the pod stranded: powered off and dark until rescue or a reserve tank.

### Scanner

- An early component: a side-view minimap in the HUD, a vertical slice around the pod showing ore in range.
- Upgrades extend range and detail. Later tiers reveal hazards such as gas pockets, otherwise invisible.

### Camera

Normal Minecraft 3D, first or third person.

## 8. Crew roles and co-op between pods

**Provisional until the prototype and playtest.**

Rule: no passengers, only crew. Every seat besides the pilot's has a real-time job that clearly helps. The pilot can do a weaker version alone, so solo play works but a crew is better.

| Role | Job |
|------|-----|
| Pilot | The legs: movement, flight, fuel. |
| Navigator | The eyes: active sonar. Pings cost power; echoes are ambiguous (ore, gas, empty cave, something that moved) and must be read. Marks targets on the pilot's HUD. Only the navigator sees past the headlights. |
| Operator | The hands: manipulator arm (grabs artifacts and wall ore the drill would destroy) and charge launcher (clears cave-ins, used in boss fights). A turret comes later. |

- **Seats follow the chassis ladder:** the Mole has 1 seat (pilot), the Prospector adds the navigator, the Badger adds the operator.
- **Robots** are components that fill the navigator or operator seat with less skill. Bigger chassis add more systems, so robots matter more as the pod grows.
- Engineer and quartermaster roles are cut for now (or exist only as robots).
- Prototype navigator and operator first.

### Co-op between pods

- **Tender runs:** one pod brings fuel and empty cargo capacity down to a pod that keeps drilling.
- **Towing and winches:** rescue stranded pods, or haul pods too heavy to take off.
- **Infrastructure:** one player builds outposts, pads and lighting while another pushes the shaft deeper.
- **Spotting:** a player on foot or in a second pod scans ahead and marks targets.
- **Cave-in clearing** from above.

## 9. On foot

- Hybrid play: leave the pod to explore caverns, fight and build.
- **Hardness:** below layer 1, rock cannot be broken by hand tools, only by pod drills.
- **Environment:** heat, pressure and toxic air outside the pod, worse with depth.
- **Suit timer:** how long you can stay on foot outside a pod or habitable outpost.
- **Suit:** upgraded separately: oxygen and toxins, heat, pressure, lamp battery.

## 10. Hazards

- Baseline from the original: undiggable rock, visible lava, invisible gas pockets (damage grows with depth), fall damage, earthquakes. Each layer adds its own.
- Earthquakes (in certain layers) and local cave-ins block a stretch of shaft, small or large, rather than erasing it.
- Ways to clear a blockage:
  1. Climb out and plant explosives, with the suit timer running.
  2. A pod-mounted charge launcher.
  3. Crewmates clearing from above.
  4. Emergency teleport items.
- The original's dynamite and plastic explosives (3×3 and 5×5) return as consumables.
- Hazards never delete player-built blocks.

## 11. Getting back up

Staged.

- **Early:** expensive emergency teleport items (the original's Quantum Teleporter and Matter Transmitter). They send you to the nearest pad or the surface, and you drop your cargo.
- **Mid and late:** player-built outposts and pads.

### Outposts and ratings

- Built at depth. They host terminals (sell, fuel, repair), pads, beds (respawn point), storage and lights.
- They sell at worse rates than the colony, so the surface trip still matters.
- Every depth has environment ratings: heat, pressure, flooding, quake bracing.
- An outpost built from materials below its depth's rating is uninhabitable (heat leaks in, water seeps in, the suit timer keeps running inside) but never destroyed.
- Rated materials come from that layer's own resources.

### Pads

- Teleport only between pads you have built, paired with each other, at a power cost that grows with depth and mass.
- They carry players and pods with empty cargo bays. Nothing mined teleports.

The Behemoth's anchor mode is a temporary outpost.

## 12. Death and failure

- Normal Minecraft respawn: the surface, or an outpost's beds. In the fiction this is the employer's Continuity Plan, and each death is a Continuity Event ([LORE.md](LORE.md#8-prosperity)).
- The pod's wreck stays at depth with its cargo, ready for a salvage run.
- Running out of fuel strands the pod (section 7).

## 13. Creatures and combat

**Details deferred** to a dedicated creatures and combat session. Lore hooks: [LORE.md section 14](LORE.md#14-hooks-for-the-creatures-session).

- Rare, unsettling creatures in each layer, plus layer bosses. Dread wins over action.
- Bosses mix pod fights and on-foot fights, by layer.
- Pod combat uses mining gear first (drill, explosives, charge launcher), turrets later.
- On foot: normal Minecraft combat plus suit gear.

## 14. Story and transmissions

Settled 2026-10-07. Canon, with spoilers: [LORE.md](LORE.md). Player-facing text: [lore/transmissions.md](lore/transmissions.md), [lore/handbook.md](lore/handbook.md) and [lore/notes.md](lore/notes.md).

Original lore with the original game's shape (a cheerful employer, vanishing miners, orders to turn back, a two-phase boss), expanded and "unabridged". Tone and guardrails: [LORE.md section 2](LORE.md#2-tone).

| Act | Layers | Beats |
|-----|--------|-------|
| 1 | 1–2 | The employer's cheerful Personnel pays bonuses while you repair Prosperity; the first garbled relays; something moving in the dark. |
| 2 | 3–5 | Wrecks and relays of the vanished crews; the Employment Contract's fine print is unredacted; the Chairman's name echoes backwards in the Lattice. |
| 3 | 6–7 | The Chairman takes over the charter's account: orders to turn back, every threat with a receipt; THE EYES. |
| Finale | 8 | Head Office, the reveal, the two-phase boss and the choice. |

- **Employer:** H. Colom & Co. H. COLOM reversed is MOLOCH: the hidden-name trick, with clues in layers 1, 5 and 7 and the reveal on screen in layer 8.
- **The reveal:** the Company is the lure of a parasite in the heart of a vast sleeping creature whose body is the deep. Everything sold goes down the Conduit to the parasite's Furnace ([LORE.md](LORE.md#3-the-spine)).
- **The finale is a choice** ([ADR 0006](adr/0006-the-finale-is-won-by-renunciation.md), [LORE.md](LORE.md#12-the-finale)). The fight breaks the boss but cannot kill him, and he offers the charter a **Controlling Interest**.
  - **Renunciation**, the true ending: anyone in the charter throws it into the Furnace, which kills the boss. The charter loses its whole account, and every crew member receives an **Unnumbered Lamp**.
  - **Signing**, by the Director only, is final and permanent. The Director becomes the Chairman, with a payout and a recurring dividend, and his suit lamp never works again. The epilogue is grim.
- **Post-game hook:** the Ramp is the wall around the creature's heart, the Motherload. Both endings lead to the uncharted layers below it ([LORE.md](LORE.md#13-post-game)). A splice is the creature stirring ("the depths moved").
- Every change after the finale is presentation for that charter only, never a world edit.
- **Transmissions** go to the whole charter. Most trigger at the charter's deepest point reached; the bootstrap repairs and the finale beats trigger on events.
- A personal build could swap in the original's transmission text ([REFERENCE.md](../original_flash_game/REFERENCE.md)).
- **Altimeter:** each layer has its own trick, presentation only ([LORE.md](LORE.md#11-layers)). "UNCHARTED" shows below the ramp.

## 15. Presentation

- **Art:** consistent with vanilla (16× textures); chunky pod models made in Blockbench. Assets are AI-assisted, with the user curating.
- **UI:** retro CRT-terminal style with typewriter text, like the original's shops and transmissions.
- **Audio:** the friends build uses the private resource pack (section 2). Original audio list: [REFERENCE.md](../original_flash_game/REFERENCE.md). Public soundtrack is open.

## 16. First milestone

### Tech prototypes (behind the scenes)

The riskiest pieces first:

1. The layer chain and breach crossing.
2. The pod vehicle (movement, drilling, flight) on a dedicated server with several players.
3. The scanner HUD.

The walkable-interior prototype is dropped ([ADR 0004](adr/0004-pods-are-vehicles.md)).

### Vertical slice (for the friends)

Surface, repairing the colony, the Mole, the onboarding handbook (implied by the handbook's scope), layers 1–2, terminals, the scanner, fuel and wreck rules, charters. About 5–10 hours of play.

## 17. Dev tooling

- Tooling choices (kept and rejected): [ADR 0001](adr/0001-reject-ai-modding-tool-wave.md).
- mcpfabric audit and pinning: [tooling/mcpfabric-audit.md](tooling/mcpfabric-audit.md).
- Platform: [ADR 0002](adr/0002-fabric-on-26-3.md).

## 18. Open questions

- Very late game: a way to drill up or angle upward? A pod upgraded at an outpost may not fit back up its own shaft, possibly by design.
- Final crew-role design, after the prototype.
- Gore stays out by default ([LORE.md guardrail 7](LORE.md#guardrails)); lift it only on the user's say.
- Finale numbers and rules: the dividend's size and cadence, and how the Chairman's dead suit lamp and the Unnumbered Lamp sit with the suit lamp's battery track.
- Records board extras proposed by the lore: a Continuity Events column, and a lamp or mask mark for each charter after the finale.
- Creatures and combat session.
- Numbers tuning: layer thicknesses, prices, drill speeds.
- Duration and size of earthquake and cave-in blockages, per layer.
- Public release: name and branding, licence, original soundtrack sourcing.
- Competition between charters (a later update).
- A trimming tool for chunks nobody visits, if world size becomes a problem.
- Exact "couple of component tiers" cadence for catalysts, and which layer supplies which catalyst. Names proposed in [LORE.md](LORE.md#6-motifs).
- Exact component tier caps per chassis.
