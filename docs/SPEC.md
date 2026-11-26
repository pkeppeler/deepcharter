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
- **Recommended optional client mods** (never required; PR #234, [art direction](design/art-direction.md)): a dynamic-lights mod (LambDynamicLights) so held lamps and moving pods light the world smoothly, and an opt-in custom shader pack through Iris on OpenGL (Iris crashes on 26.3's Vulkan backend). Players without them see the plain ledgered light.
- **Servers:** dedicated servers supported from day one. Hosted on the user's Mac first (friends outside the home network need port-forwarding or a tunnel); a rented host later.
- **Assets:** our own. The friends build uses a private resource pack with the user's extracted original Motherload music and sounds; it is never published. Soundtrack sourcing for a public build is open (possibly one per layer).

## 3. World

### Surface and colony

- **Surface:** our own regolith plains, craters and terraced mesas, under a slow dusk-to-night sky that never reaches full day (PR #234, [art direction](design/art-direction.md), [ADR 0030](adr/0030-art-direction-decisions.md)). It is dry and bare: no sea, no grass, no trees, no animals, within the tall-world limits of [ADR 0029](adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md). No monster lives on it, vanilla or ours: the colony's emptiness is the dread ([CREATURES.md](CREATURES.md#surface), decided in the creatures session on 2026-11-21, which replaced the plan to reskin the vanilla night monsters). The surface has no ore and no vanilla ore is promised near it: early metal is salvage (see Bootstrap in section 4, [ADR 0047](adr/0047-the-surface-has-no-ore-the-bootstraps-metal-is-salvage-and-the-yard-is-a-biome.md)), and the first ores are the layers'. The only life on it is pale fungal stalks on crater floors and in patches among the salvage crates (#855). No villages or settlements besides the colony. No Nether or End; the depths replace them.
- **Never called Mars:** the world may look like Mars, but the game never calls it Mars (lore guardrail).
- **Colony:** one derelict mining colony per world, at spawn, named Prosperity. Its terminals are repaired once per world: when any charter fixes one, every charter can use it. The contract terminal works from the start.
- **Conduit:** an undiggable Company pipe runs from the colony's ore processor straight down through every layer, at the same coordinates in each, ending in the Furnace. It is a landmark and the story's first clue ([LORE.md](LORE.md#8-prosperity)).
- **Width:** unlimited, no border.
- **Performance:** world height drives cost, because Minecraft generates whole columns. Keep layers about 256 blocks or less, and deep dimensions no taller than they need (up to 4,064, one dimension's limit, [ADR 0085](adr/0085-depth-is-one-axis-over-a-stack-of-tall-dimensions-and-the-dimension-machinery-stays.md)). If world files grow large, add a tool to trim unvisited chunks.

### Layers

- The underground is a chain of layers. Each layer's floor leads into the top of the next.
- The surface and story layers 1–8 share one tall dimension, the **campaign world**. A layer is a Y band with its own ambient light, fog and sky, set by biome. The depth readout is computed: layer offset plus local Y. See [ADR 0029](adr/0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md).
- Layers vary in thickness.
- **Zones:** each layer has 2–4 zones. Ore value, hazards, creatures and atmosphere step up between zones.

### Breaches

- A breach is a crust at each layer boundary. It is a soft gate: any drill can get through, slowly and painfully (heat, hull damage).
- Crossing is an event: rumble, a transmission, new music. There is no teleport and no fade.
- You can go too deep too early, and you will regret it.

### Seams and grained crust

- Below the campaign world, the worlds are up to 4,064 tall, the limit of one dimension ([ADR 0085](adr/0085-depth-is-one-axis-over-a-stack-of-tall-dimensions-and-the-dimension-machinery-stays.md)). Each joins the next at a **seam**: a fast background swap. The pod carries all its riders.
- A seam at a layer boundary sits in a **grained crust** (working name; the lore session names it). It has a vertical grain and flexes on a slow pulse. Rules: no sideways digging, placed blocks crumble, fluids are absorbed, and anything that stops in it is squeezed. A seam mid-layer sits in a plain slab of ordinary rock, with none of these rules.
- Seed-chosen **decoys** (set so that about a third of restricted crusts are seams) follow the same rules, so a seam at a layer boundary cannot be told from a decoy.

### Story layers

About 8 story layers, roughly 6–8 hours each: a 60+ hour campaign, finale at the bottom of the last. The campaign world ends at the finale's floor, where a seam leads to the Ramp. Themes are settled; each layer's lore, zones and beats are in [LORE.md](LORE.md#11-layers).

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

A few hundred blocks at the top of the first uncharted world, below the seam at the finale's floor, where drilling gets exponentially harder. You can continue, but it is not worth it. It makes the end of the campaign obvious.

### Uncharted layers

- Below the ramp, without limit: a chain of deep dimensions, each up to 4,064 tall. Remixes of story-layer themes, each much harder than the one above; rewards grow far more slowly than difficulty.
- A bragging-rights grind for the records board.
- The altimeter reads "UNCHARTED".
- No rated materials exist for them, so no outpost there can be made habitable.

### Splice rule

- A layer's content is fixed the first time any charter breaks into it.
- An update adding story layer N+1 splices it in as a new world at the seam between the finale and the Ramp. The seam moves to the new world's floor; it is not a seam inside the campaign world. Breaking through N's floor leads into the new layer; its floor leads into whatever already existed.
- Nothing built is lost. Existing layers keep their terrain; their depth readings shift.
- Players already down there get a "the depths moved" story event.
- No shaft can reach a spliced layer early, so pre-mining is impossible.

### Darkness

- The darkness comes from each zone biome's ambient colour, darker with depth; the dimension's own ambient light is 0.
- Pod lights are a component track. On foot, the suit lamp runs on a battery.
- The original was fully lit; Minecraft's real darkness adds to the dread.

## 4. Core loop and progression

### Bootstrap

- Start from the colony's salvage and fungal "wood". This replaces "start like normal Minecraft" on the user's art direction ([art direction](design/art-direction.md) section 11, decided with the user on 2026-10-08, issue #225, PR #234; built in [#245](https://github.com/pkeppeler/deepcharter/issues/245)). Salvage crates supply wood, cloth and scrap (scrap iron and scrap copper, melted in a furnace) and redstone. They are littered round the colony within a set block radius, not only at it (none stand on the pad or its margin): the radius is one number, `min_inclusive` and `max_exclusive` of `data/deepcharter/worldgen/density_function/surface/yard.json`: a ring from 100 to 160 blocks, which starts past the colony's pad and its graded margin. Hardy alien fungal stalks on crater floors, in patches among the crates, and in the caves of layer 1, are gatherable stand-ins for trees: a stalk presses into four planks, burns, and chars to charcoal. A new player reaches tools, a light and the first terminal repair with no tree and no ore.
- Craft the first scanner too: a **scanner unit** from 4 iron, 2 copper and 2 redstone, about four crates' worth of the yard ([mechanics.md](design/mechanics.md#the-crafted-scanner-483-a)). It is the tier 1 part of the first mining pod, made like the Mole's own parts (SPEC section 7, Scanner); the upgrade terminal does not sell it.
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

- **Getting it:** every player is given it on joining, before any charter, and gets another if it is lost. It is bound to you. Open it from the item or a keybind.
- **Terminals:** colony and outpost terminals also show the charter's open Directives.
- **Scope:** the onboarding chapters cover the bootstrap and layers 1–2 as a linear, explicit tutorial: no cryptic wording, no side chapters, no helpdesk. The Directives are shown as a **quest map**, and earlier miners' pencil paths continue it past layer 2 ([ADR 0065](adr/0065-the-handbooks-directives-are-a-quest-map-and-earlier-miners-pencil-paths-continue-it-past-layer-2.md)).

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
  - The next chapter shows its title and Directive list on its own tab. The current chapter's map does not preview it.
  - Anything further ahead is a redacted shape with a CLASSIFIED stamp.

### After layer 2

- The official chapters end with "Further documentation is restricted to Senior Personnel."
- From then on the back section fills only with **Notes** found in the world: wreck logs, torn pages, miners' scribbles.
- A Note picked up by anyone in a charter goes into every member's handbook, unread for each person.
- Using a Note in the world opens it to read, charter or not. A player on no charter is told to found one at the contract terminal to file it.
- Each Note also draws a **pencil path** on the pencil sheet (below), from earlier miners. It completes when the charter reaches the place the Note points to: a cache, a wreck or another Note. The place is the payoff; there is no claim button. A pencil path is not a Directive.
- The paths sit on one fixed **pencil sheet** after the last chapter (nothing drags or zooms), in Roz's green pencil: a box for the Note, a dashed line, an x at the place. The sheet exists once a Note has drawn a path.
- The first version ships two paths from existing Notes: N07, the prayer card, to the Prospector's wreck, and N10, Ines's log, to the Kells' shaft. More come with lore sessions. A wax cylinder found where a path ends ([#864](https://github.com/pkeppeler/deepcharter/issues/864)) is a payoff of the same kind, added with the cylinder.
- Revisions may also add stamped pages: a disciplinary notice, the Chairmen's Book or the Ledger. They carry no rules ([lore/handbook.md](lore/handbook.md#revisions)).

### Directives and progress

- **Directives** are the handbook's objectives, shown as a **quest map**: a tab down the side for the intro and one for each chapter on every page, and each chapter a fixed flowchart sheet: nothing on it drags, pans or zooms (playtest 3, #962). They complete automatically from game events: item obtained, block placed or repaired, layer entered, item crafted, or a custom trigger from our code.
- A step's card shows its instruction, a link to its chapter page and, when the step asks for an item (obtained or crafted), that item with its count and progress. It never lists the item's ingredients. A click on the item opens its recipe, as in the GregTech: New Horizons questbook (BetterQuesting). The chapter text pages stay as the manual.
- The handbook has a controls page after the contents: how to use the handbook, the parts catalogue, the pod and the scanner, with each key read live from the player's bindings, so a rebind shows.
- A completed step shows a themed popup. Nothing is pinned on screen. The book opens where the player left it, and L opens it as well as H.
- Directives give no rewards. The employer's transmission bonuses still exist separately.
- Progress is shared by the charter, with personal read marks for each player.
- **Before a charter:** founding carries the founder's Directive progress, recipe discoveries and found Notes into the new charter. Joining takes the charter's Directive progress and adds the joiner's discoveries and Notes to it.
- Terminals might show the same map later, in CRT style (an idea).

### Three channels

One job each. They can point at each other; a transmission can unlock a handbook chapter.

| Channel | Job |
|---------|-----|
| Handbook | How to progress (Directives). |
| Work orders | Repeatable money tasks. |
| Transmissions | The story. |

### The world is a mystery; the rules never are

There are no Directives after layer 2, only pencil paths. Each new mechanic is explained the first time you meet it:

- A **Spec sheet** when you craft or buy something, for example a pad's power cost or a material's heat rating: a small corner slip names it and the key that opens it, so a first craft never stops play. It is the item's page in the **parts catalogue**, and can be looked up any time after.
- A one-time HUD or scanner warning on first exposure, such as "PRESSURE EXCEEDS HULL RATING".

The story stays in Transmissions and Notes.

Implementation: [ADR 0005](adr/0005-build-our-own-handbook.md); research in [research/handbook-build-vs-reuse.md](research/handbook-build-vs-reuse.md).

### Parts catalogue

Our own EMI-style viewer, styled as a Company parts catalogue ([ADR 0066](adr/0066-we-build-our-own-parts-catalogue-and-the-server-sends-each-player-only-their-charters-known-recipes.md)). It sits beside inventory screens. R on a hovered item shows its recipes, U its uses. It has search, favourites and a "craftable now" filter. Vanilla's recipe book is hidden.

- A recipe is **known** per charter. It becomes known when a quest step that asks for its item becomes current, or when anyone in the charter obtains the item or one of its ingredients. Vanilla items follow the same rule.
- Only what the campaign world yields exists. A recipe that needs or makes an item the world has none of is disabled, vanilla's included, and such items are left out of the catalogue and the creative tabs. A tag ingredient such as "any planks" shows only the members the world yields ([ADR 0093](adr/0093-recipes-and-items-the-campaign-world-does-not-yield-are-removed-by-a-derived-allowlist.md)).
- Unknown recipes still craft. The server sends each player only their charter's known recipes.

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
- **Tier 1 is crafted, every later tier is bought.** The player crafts a scanner unit in the bootstrap and fits it by using it on their own charter's pod; it becomes a tier 1 part stamped for the charter, like a bought one. The upgrade terminal does not sell tier 1. Tiers 2 and up are purchases and need the tier 1 part fitted first: by then mining by hand is too slow, so the game pushes the player into the pod and its economy ([ADR 0048](adr/0048-the-tier-1-scanner-is-a-crafted-unit-fitted-by-use.md), user decision of 2026-10-11).
- Upgrades extend range and detail. Later tiers reveal hazards such as gas pockets, otherwise invisible.

### Camera

Normal Minecraft 3D, first or third person.

## 8. Crew roles and co-op between pods

**Provisional until the prototype and playtest.**

Goal: crew, not passengers. A seat besides the pilot's should have a real-time job that clearly helps. The pilot can do a weaker version alone, so solo play works but a crew is better.

Passengers are allowed. Example: the Prospector's second seat, until the navigator's sonar exists (decided with the user on 2026-10-11, #200).

| Role | Job |
|------|-----|
| Pilot | The legs: movement, flight, fuel. |
| Navigator | The eyes: active sonar. Pings cost power and are loud enough to draw creatures; echoes are ambiguous (ore, gas, empty cave, something that moved) and must be read. Marks targets on the pilot's HUD. Only the navigator sees past the headlights. |
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
- **Hardness:** the way through pod-only blocks (the breach crust, Company rock, and rock below layer 1) is a pod drill. Any hand tool (pickaxe, axe, shovel, hoe, sword or spear) can brute-force one, in any layer, at 60 seconds a block and a ruinous cost that Unbreaking and Mending cannot lower: a diamond or better tool pays half its durability, and a lesser tool (wood, stone, iron, gold, copper) is destroyed as the block goes. It drops nothing, and the game says what the player is doing wrong ([ADR 0069](adr/0069-a-hand-tool-can-brute-force-a-pod-only-block-at-a-ruinous-cost.md)).
- **Environment:** heat, pressure and toxic air outside the pod, worse with depth.
- **Suit timer:** how long you can stay on foot outside a pod or habitable outpost.
- **Suit:** upgraded separately: oxygen and toxins, heat, pressure, lamp battery.
- **Food:** the world has no crops at the start, so the Company feeds a new hand: every player starts with a few sealed Company rations, the ore cars on the colony's track still hold the last shift's tins (a one-time find each), the canteen in the Lamp and Pick gives a ration every time it is used, and a salvage crate sometimes holds one. A fungal stalk cooks into a poor meal on a campfire or in a smoker, so food is renewable ([mechanics.md](design/mechanics.md#company-rations-838-a)).

## 10. Hazards

- Baseline from the original: Company Rock (a plain drill refuses it, and a hand tool breaks it only at the ruinous cost of [ADR 0069](adr/0069-a-hand-tool-can-brute-force-a-pod-only-block-at-a-ruinous-cost.md); a bought blasting charge clears it, [ADR 0050](adr/0050-company-rock-can-be-blasted-and-the-drill-still-refuses-it.md); a shaped charge clears a column of it and a tier 3 drill bites it slowly, [ADR 0052](adr/0052-the-prospectors-best-drill-bites-company-rock-and-a-shaped-charge-clears-a-column.md)), visible lava, invisible gas pockets (damage grows with depth), fall damage, earthquakes. Each layer adds its own.
- Earthquakes (in certain layers) and local cave-ins block a stretch of shaft, small or large, rather than erasing it.
- Ways to clear a blockage:
  1. Climb out and plant explosives, with the suit timer running.
  2. A pod-mounted charge launcher.
  3. Crewmates clearing from above.
  4. Emergency teleport items.
- The original's dynamite and plastic explosives (3×3 and 5×5) return as consumables. Neither clears Company Rock. The blasting charge does: set from the seat under the pod, a fuse, a blast. So does its dearer cousin, the shaped charge, which clears the whole Company Rock column under the pod.
- Hazards never delete player-built blocks. Creatures have their own rule (section 13).

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
- Rated materials also hold against creatures. A creature breaks only placed blocks it can reach that are not rated for the depth, and it does so while nobody is near, so a rated shell keeps everything inside it safe. The game says so on the outpost's spec sheet, its terminal and a placement readout ([ADR 0072](adr/0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md)).

### Pads

- Teleport only between pads you have built, paired with each other, at a power cost that grows with depth and mass.
- They carry players and pods with empty cargo bays. Nothing mined teleports.

The Behemoth's anchor mode is a temporary outpost.

## 12. Death and failure

- Normal Minecraft respawn: the surface, or an outpost's beds. In the fiction this is the employer's Continuity Plan, and each death is a Continuity Event ([LORE.md](LORE.md#8-prosperity)).
- The pod's wreck stays at depth with its cargo, ready for a salvage run.
- Running out of fuel strands the pod (section 7).

## 13. Creatures and combat

Settled 2026-11-21 in the creatures session ([#13](https://github.com/pkeppeler/deepcharter/issues/13)). The full design, with the bestiary, bosses and combat kit: [CREATURES.md](CREATURES.md). Lore hooks: [LORE.md section 14](LORE.md#14-hooks-for-the-creatures-session).

- Rare, unsettling creatures in each layer, plus layer bosses. Dread wins over action.
- **Three families, each with one draw.** Moloch's things come to the dark and take (fuel, cargo, hunger, lamp battery) but never damage a hull or a person. The Sleeper's defences come to the drill (noise, heat, resonance) and hurt; killing one raises the zone's alarm. Gnawers, post-game only, come to the haul and eat.
- **Every mob costs you in the pod and on foot**, and gives you a reason to avoid it. Encounters are rare and costly, seldom lethal: an ordinary mob can cost you the run, a boss can leave a wreck.
- **Encounters scale by pods, not people.** Solo play is tuned with robots in the empty seats.
- **Layer bosses** in layers 3 to 7 guard a chassis wreck or catalyst. They gate the upgrade ladder, not the descent, and each charter fights each one once. Bosses mix pod fights and on-foot fights, by layer.
- **The finale:** phase 1 on foot, where the Chairman can be hurt only in the light of the crew's own lamps; phase 2 in pods, where only the drill and explosives hurt Head Office, which is built from what the charter sold and eats the cargo it takes.
- Pod combat uses mining gear first (drill, explosives, charge launcher), turrets later (from layer 6). On foot: normal Minecraft combat plus suit gear.
- **Placed blocks:** creatures break only exposed blocks not rated for the depth, even while nobody is near (section 11, [ADR 0072](adr/0072-creatures-break-exposed-unrated-blocks-even-while-nobody-is-near.md)).
- The surface has no monsters, and nothing of Moloch's lives below the Ramp.

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
- **The finale is a choice** ([ADR 0031](adr/0031-the-finale-is-won-by-renunciation.md), [LORE.md](LORE.md#12-the-finale)). The fight breaks the boss but cannot kill him, and he offers the charter a **Controlling Interest**.
  - **Renunciation**, the true ending: anyone in the charter throws it into the Furnace, which kills the boss. The charter loses its whole account, and every crew member receives an **Unnumbered Lamp**, a suit lamp that never runs out.
  - **Signing**, by the Director only, is final and permanent. The Director becomes the Chairman, with a payout and a recurring dividend. He carries the Controlling Interest for good, and his suit lamp never works again. The epilogue is grim.
- **Post-game hook:** the Ramp is the wall around the creature's heart, the Motherload. Both endings lead to the uncharted layers below it ([LORE.md](LORE.md#13-post-game)). A splice is the creature stirring ("the depths moved").
- Every change after the finale is presentation for that charter only, never a world edit.
- **Transmissions** go to the whole charter. Most trigger at the charter's deepest point reached; the bootstrap repairs and the finale beats trigger on events.
- A personal build could swap in the original's transmission text ([REFERENCE.md](../original_flash_game/REFERENCE.md)).
- **Altimeter:** each layer has its own trick, presentation only ([LORE.md](LORE.md#11-layers)). "UNCHARTED" shows below the ramp.

## 15. Presentation

- **Art:** 16× textures with GTNH-style layers (overlays, emissive glow, animated active states, connected textures), as decided in [art-direction.md](design/art-direction.md) (PR #234). Pod models are made in Blockbench and rendered with GeckoLib.
- **AI-assisted assets:** AI image models are allowed, curated by the user. Style consistency is managed with a reference sheet and curation. Any public release needs Modrinth's "Contains AI-generated content" disclosure, and Modrinth bans projects whose content is primarily or entirely AI-made, so the project must not become that. A local image model on the user's Mac is a separate install the user approves when needed. The private original audio and art are never used or copied.
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
- Creature numbers: encounter cadence per zone, alarm, boss health per pod, and the rate of loss at an outpost while nobody is near ([CREATURES.md](CREATURES.md#11-open-questions)).
- What happens to dropped items and mobs that fall to a seam.
- Numbers tuning: layer thicknesses, drill speeds, and the prices past layer 2. The early prices (parts, scanner, lights, the Mole and Prospector, repairs) were scaled to a run's income in [PR 206](https://github.com/pkeppeler/deepcharter/pull/206).
- Duration and size of earthquake and cave-in blockages, per layer.
- Public release: name and branding, licence, original soundtrack sourcing.
- Competition between charters (a later update).
- A trimming tool for chunks nobody visits, if world size becomes a problem.
- Exact "couple of component tiers" cadence for catalysts, and which layer supplies which catalyst. Names proposed in [LORE.md](LORE.md#6-motifs).
- Exact component tier caps per chassis.
