# Pod references

This page collects what makes vehicles and machines in modded Minecraft read as machines and not as boxes. It informs the 3-4 Mole concepts of [#334](https://github.com/pkeppeler/deepcharter/issues/334). Our direction is in [art-direction.md section 3](art-direction.md#3-pods) and [tooling-options.md, "What makes the pod a machine, not a box"](tooling-options.md#what-makes-the-pod-a-machine-not-a-box). We borrow principles only. No model, texture or screenshot from any mod or from Motherload enters the repo ([reference rules](tooling-options.md#7-reference-gathering)). Sources were read on 2026-10-09. Cube and bone counts come from reading the public model files linked below.

## Create

Sources: [Create on Modrinth](https://modrinth.com/mod/create), [drill head model](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/resources/assets/create/models/block/mechanical_drill/head.json), [DrillRenderer](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/kinetics/drill/DrillRenderer.java#L38-L49), [bogey part models](https://github.com/Creators-of-Create/Create/tree/mc1.21.1/dev/src/main/resources/assets/create/models/block/track/bogey), [StandardBogeyRenderer](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/java/com/simibubi/create/content/trains/bogey/StandardBogeyRenderer.java#L96-L124).

- Create calls itself "Aesthetic Technology", built from "animated components working together". Every moving part shows why the machine works.
- The drill head is 10 elements: an axle, a core, four fins, and four stacked bits that shrink from 5 to 2 pixels wide. Each bit is turned 45 degrees on the drill axis, so the cone is a stack of diamonds. The bits use a dark iron texture; the base uses casing.
- The drill spins from the contraption's speed, and the speed goes to 0 when the contraption stalls. The motion tells the player the state.
- A train bogey is separate part models: frame, wheels, pin, piston and drive belt. One wheel angle drives them all. The wheels turn by it, the piston slides by its sine, the pin orbits and counter-turns to stay upright, and the belt scrolls its UV instead of moving.

**We take:** one driver value per motion group (track speed, drill speed) that derives every linked part. Belts and tread faces scroll texture, not cubes. The drill stops visibly when it is blocked.

## Immersive Engineering

Sources: [Immersive Engineering on CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-engineering), [BucketWheelCallbacks](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/main/java/blusunrize/immersiveengineering/client/models/obj/callback/block/BucketWheelCallbacks.java#L50-L81), [BucketWheelRenderer](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/main/java/blusunrize/immersiveengineering/client/render/tile/BucketWheelRenderer.java#L56), [CrusherRenderer](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/src/main/java/blusunrize/immersiveengineering/client/render/tile/CrusherRenderer.java#L48-L63), [multiblock models](https://github.com/BluSunrize/ImmersiveEngineering/tree/1.21.1/src/main/resources/assets/immersiveengineering/models/block/metal_multiblock).

- The stated look is "retro-futurism (think BioShock, Order 1886, SkyCaptain and the World of Tomorrow) rather than clinical white+grey future cubes". Power runs in hanging cables, not glowing tubes.
- The excavator is a 3x7x8 multiblock "with a big rotating bucketwheel". The crusher has rotating wheels and "spits out particles as it breaks the ore".
- The wheel shows what it digs. Each bucket's load (model groups `dig0`, `dig1` and so on) is drawn only when it holds a block, with that block's own texture.
- The frame is one baked model; only the working parts are separate models. The bucket wheel turns only while active. The crusher's two barrels turn in opposite directions.

**We take:** an early-century industrial look, not sci-fi. Counter-turning drill rings. A cargo sight glass or drill chips that show the colour of the ore just mined. The hull stays one still mass; only working parts move.

## Immersive Vehicles (formerly Minecraft Transport Simulator)

Sources: [Immersive Vehicles on Modrinth](https://modrinth.com/mod/immersive-vehicles), wiki pages [Model Requirements](https://github.com/DonBruce64/MinecraftTransportSimulator/wiki/Pack-Making-Models-Model-Requirements), [Lights](https://github.com/DonBruce64/MinecraftTransportSimulator/wiki/Pack-Making-JSON-Lights), [Animated Objects](https://github.com/DonBruce64/MinecraftTransportSimulator/wiki/Pack-Making-JSON-Animated-Objects), [Animation Variables](https://github.com/DonBruce64/MinecraftTransportSimulator/wiki/Pack-Making-JSON-Animation-Variables).

- The mod has no vehicles of its own; content packs add them. Models "MUST be in OBJ format" with one PNG texture. An object whose name starts with `&` is a light. (OBJ meshes are not open to us: GeckoLib uses cubes.)
- A light can be "emissive and render a solid-color of light when on", can have "a glass cover rendered over it", and can be a beam with "beam-blending". A light can also "dim relative to the electric power of the thing it is on".
- Animations read named vehicle variables such as `engine_rpm`, `ground_rotation`, `fuel`, `electric_power` and `headlight`. `applyAfter` chains a part to the motion of the part it hangs on.

**We take:** a lamp in three layers: a dark lens in the base texture, a lit lens in the glowmask, and a cage or glass cube over it. The beam is a separate bone. Lamp brightness follows fuel, so a pod that runs dry dims before it goes dark. Every animation reads a named pod state, never the clock alone.

## Valkyrien Skies and Eureka

Sources: [Valkyrien Skies on Modrinth](https://modrinth.com/mod/valkyrien-skies), [Eureka on Modrinth](https://modrinth.com/mod/eureka), [Eureka block list](https://github.com/ValkyrienSkies/Eureka/tree/1.20.1/main/common/src/main/resources/assets/vs_eureka/blockstates), [ShipHelmBlockEntityRenderer](https://github.com/ValkyrienSkies/Eureka/blob/1.20.1/main/common/src/main/kotlin/org/valkyrienskies/eureka/blockentity/renderer/ShipHelmBlockEntityRenderer.kt#L38-L45), [Trackwork on CurseForge](https://www.curseforge.com/minecraft/mc-mods/Trackwork).

- Ships are "ordinary (or modded!) Minecraft blocks" that get physics when you assemble them at a ship helm. They are built at world scale from world materials, so they belong in the world.
- Eureka adds only a few function blocks: helm, balloon, floater, engine, anchor and ballast. Each one names a job at a glance.
- The helm's wheel is a separate model that turns with the ship's rate of turn.
- Trackwork adds "tank treads" and wheels with suspension to these ships ("Warranty void if suspension is compressed").

**We take:** the same texel density as world blocks (one texel per model unit), so the pod sits in the world. A few iconic function parts that say "this digs" (drill), "this drives" (treads) and "this flies" (rotor). A cab lever or wheel that moves when the pilot steers.

## Drill and mech mods

Sources: [Immersive Machinery on Modrinth](https://modrinth.com/mod/immersive-machinery), [Tunnel Digger model](https://github.com/Luke100000/ImmersiveMachinery/blob/1.20.1/common/src/main/resources/assets/immersive_machinery/objects/tunnel_digger.bbmodel), [TunnelDiggerRenderer](https://github.com/Luke100000/ImmersiveMachinery/blob/1.20.1/common/src/main/java/immersive_machinery/client/render/entity/renderer/TunnelDiggerRenderer.java#L13-L32), [Magitek Mechs on Modrinth](https://modrinth.com/mod/magitek-mechs), [tunnel armor model](https://gitlab.com/DigiDigi/mtmechs/-/blob/1.17.1/src/main/resources/assets/mtmechs/geo/tunnelarmor.geo.json), [its animations](https://gitlab.com/DigiDigi/mtmechs/-/blob/1.17.1/src/main/resources/assets/mtmechs/animations/tunnelarmor.animation.json), [its textures](https://gitlab.com/DigiDigi/mtmechs/-/tree/1.17.1/src/main/resources/assets/mtmechs/textures/entity), [Miner on Modrinth](https://modrinth.com/mod/miner), [Pomkots Mechs on Modrinth](https://modrinth.com/mod/pomkots-mechs).

- Immersive Machinery's Tunnel Digger is "a large, slow, and powerful machine", in a "rustic" style "somewhat close to the vanilla style". Its model is 81 cubes and 2 meshes on one 256x256 box-UV texture; 15 cubes are rotated. Its bones are a seat, an engine (belt, exhaust, two control levers, two gears of 8 cubes each), two tracks, and a drill of five cutter heads of six cubes each.
- Its tracks are a flipbook: four copies of each track cube, one shown per tick while the tracks move. The engine belt swaps two frames while the engine has power.
- Magitek Mechs' tunnel armor (MIT, GeckoLib) is 100 cubes in 19 bones on 128x128. It has two drill arms, each a base of 10 cubes and a spinning rotator of 8 cubes on a 0.5-second loop; the arms lift by keyframes. Its power core has a full texture and an empty one.
- Miner (GeckoLib) drills "forward, upward, or downward with adjustable drill angle". Its page does not describe the model.
- Pomkots Mechs warns that its "graphics without a shader pack may look lackluster".

**We take:** 80-100 cubes make a rich digger when the budget goes to the drill, tracks and engine. A drill mount that pivots on a visible knuckle. Fuel and power shown on the model itself. Designs that look right in plain vanilla rendering; shaders are a bonus.

## Small vehicles

Sources: [Immersive Aircraft on Modrinth](https://modrinth.com/mod/immersive-aircraft), [its model sources](https://github.com/Luke100000/ImmersiveAircraft/tree/1.21.1/common/src/main/resources/assets/immersive_aircraft/objects), [Copperfin model](https://github.com/Luke100000/ImmersiveMachinery/blob/1.20.1/common/src/main/resources/assets/immersive_machinery/objects/copperfin.bbmodel), [Ad Astra on Modrinth](https://modrinth.com/mod/ad-astra), [RoverModel](https://github.com/terrarium-earth/Ad-Astra/blob/1.21.1/src/main/java/earth/terrarium/adastra/client/models/entities/vehicles/RoverModel.java#L94-L111), [Automobility on Modrinth](https://modrinth.com/mod/rqIsPf9F), [Automobility part models](https://github.com/FoundationGames/Automobility/tree/1.21-rewrite/common/src/main/resources/assets/automobility/models/entity/automobile), [vanilla minecart](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/entity/minecart.geo.json).

- Immersive Aircraft aims to be "vanilla-faithful with many details and functionalities, without being overly complicated". In the biplane, each moving part (propeller, rudder, elevator, banners) is its own named group. The parts are meshes, not cubes.
- The Copperfin submarine is 6 cubes. Its window is a separate `glass` cube.
- Ad Astra's rover is 76 boxes on a 256x256 texture. The wheels turn with speed, and the front pair steers, clamped to 0.3 radians. The antenna is its own part.
- Automobility builds a car from a frame, an engine and wheels crafted apart. Each engine tier (stone, iron, copper, gold, diamond) is its own model.
- Vanilla's minecart is 5 cubes on 64x32. It reads as a cart from its open top alone.

**We take:** the canopy is a separate translucent cube in its own bone. Each component tier is a visible bone (a bigger tank, another drill head), not only a stat. Steering visibly turns something.

## Vanilla mobs and modelling guidance

Sources: [Blockbench Minecraft style guide](https://www.blockbench.net/wiki/guides/minecraft-style-guide), [Blockbench format features](https://www.blockbench.net/wiki/blockbench/formats), [vanilla entity models (bedrock-samples)](https://github.com/Mojang/bedrock-samples/tree/main/resource_pack/models/entity), [Iron Golem](https://minecraft.wiki/w/Iron_Golem), [Warden](https://minecraft.wiki/w/Warden), [Copper Golem](https://minecraft.wiki/w/Copper_Golem), [GeckoLib glowmasks](https://wiki.geckolib.com/docs/geckolib5/miscellaneous/glowmasks).

- Vanilla gets strong silhouettes from few cubes: iron golem 8 (128x128), warden 10, ravager 12, sniffer 15 (192x192), camel 23. Each has one dominant mass with limbs clearly apart from it. The iron golem's waist is a cube inflated by half a pixel so it sits proud of the body.
- The style guide: "The overall shape of an object should be defined by the model and most of the detail by the texture." "Rotating an element to create a slant instead is preferable" to stairs, but rotations "need to be justified". No mixels (elements smaller than a pixel). "The top and front of the entity need to be brighter than the bottom and back." "Noise adds no information."
- State lives in the texture. The iron golem shows more cracks below 75, 50 and 25 percent health. The copper golem changes colour as it oxidises, and its eyes dim. The warden's heart is "a glowing pulse inside the warden's chest".
- A GeckoLib glowmask keeps only the glowing pixels in `_glowmask.png`. By default they are "full-sky" brightness. Blockbench's GeckoLib format supports per-face UV, box UV and free rotation.

**We take:** hull damage as crack and dent overlay stages. Dust and rust as texture variants. No sub-pixel cubes. Top and front painted lighter. One rotated cube for a chamfer, never a staircase.

## Motherload's pod (shape in words)

Sources: our own notes only: [art-direction-options.md section 3.4, option A](art-direction-options.md#34-the-pods-mole-and-prospector-and-the-ladder-above-them) and [SPEC section 7](../SPEC.md#7-pods). The original is studied privately and is never copied.

- A squat bell or dome hull on tracks. A dark window band round the top. A big spiral drill cone at the front. Two small antennae and an exhaust stack. A rotor folds out of the top to fly.
- Its silhouette reads at a glance: heroic and a little toy-like.
- Movement we keep: treads on the ground, a rotor to fly, a drill that bores down and sideways but never up. Components: drill, hull, engine, fuel tank, radiator, cargo bay, scanner, lights.

**We take:** the idea of a capsule with a window band and a drill cone, in our own proportions, colours and details. The drill must face down as well as forward. The rotor stays visible, folded on the roof, when the pod is not flying.

## The *Atlantis* digger (shape in words)

Sources: [*Atlantis: The Lost Empire* on Wikipedia](https://en.wikipedia.org/wiki/Atlantis:_The_Lost_Empire), [art-direction-options.md section 3.4, option B](art-direction-options.md#34-the-pods-mole-and-prospector-and-the-ladder-above-them). The fan wiki that option B quotes ([Digger](https://atlantisthelostempire.fandom.com/wiki/Digger)) could not be reached today (HTTP 402), so its text is not checked again here.

- Long and heavy like a locomotive. Riveted plate. A churning cutter head of toothed rings at the front. Twin caged headlamps, an exhaust stack, tracks under the front and big wheels behind.
- "Mignola's graphic, the angular style was a key influence" on the film's look. The team studied "the technology of the early 20th century".

**We take:** flat graphic planes and heavy black shadow in the texture. Rivet rows along every seam. Cutter rings with teeth. Caged lamps. The Mole gets these materials at small scale; the full locomotive waits for the Behemoth.

## What reads at a distance, and what is noise

Reads, from the sources above:

- **Masses and gaps.** Vanilla mobs read from 8-23 cubes because each has one big mass and parts clearly apart from it.
- **Motion.** A spinning drill, turning wheels, scrolling treads, a rotor. Create, Immersive Engineering and the Tunnel Digger keep the frame still and move only the working parts, so the eye goes to them.
- **Light in the dark.** Lit lenses, beams and a pulsing core (Immersive Vehicles lights, the warden's heart).
- **Large blocks of material.** Paint against dark metal against a glass band. Each material is a separate colour ramp.

Noise:

- Many cubes under 2 pixels. The style guide prefers "a single large element with certain pixels strategically being fully transparent".
- Brush noise and too much dithering ("Noise adds no information"), and stair-stepped curves.
- Glow everywhere. Our art direction says small things glow and the world stays black.
- Detail that needs a shader pack to show.

## Principles for the Mole concepts

These rules guide the concepts and bind #243's build. The Mole's hitbox is 1.9 blocks wide, deep and tall (about 30 model units). The bore is the size of the pod (SPEC section 7), so nothing except the drill sticks out of that box at the sides or top while the pod drives or drills.

The concepts of [pod-concepts.md](pod-concepts.md) depart from four rules on purpose, because they draw through vanilla `ModelPart`: tread links slide as cubes, one link pitch at a time (rule 8: `ModelPart` has no UV scroll); the canopy is opaque dark glass (rule 10); the drill has up to 36 cubes (rule 4); and the beam bone, the damage stages and the fuel gauge (rules 6 and 11) wait for #243.

1. **Three masses and a gap.** A solid black side view and front view each show at least three distinct masses (hull, drill, track pods) and at least one see-through gap (between a track and the hull, or under the drill). Neither view is a plain rectangle.
2. **No large flat face.** No flat hull area is larger than 12x12 pixels without a step, a seam line or a recess.
3. **Chamfers from one rotated cube or a 1-pixel step.** Slopes and rounded shoulders use a cube turned 22.5 or 45 degrees, or a 1-pixel step-in. No stair-stepped curves. At most about one cube in five is rotated (the Tunnel Digger: 15 of 81).
4. **The drill reads by its tiered cone and spiral texture, not by cube count.** The head is 3-5 shrinking tiers plus teeth or rings, with a spiral painted across the tiers, in 25 cubes or fewer. It spins in code from drill speed and stops when blocked.
5. **The drill mount pivots.** A `drill_mount` bone swings the head from forward to down (SPEC section 7) on a hinge knuckle you can see.
6. **Lamp lenses inset one pixel into a cage.** Each headlamp is a housing, a lens set 1 pixel back, and a cage of 2-3 thin bars in front. The lens is dark in the base texture and warm white only in the glowmask. The beam cone is a separate bone. Lamps dim when fuel is low and go dark when the pod is stranded.
7. **Only working parts glow.** Glowmask pixels sit only on lamp lenses, gauges and the hot drill tip (dull red). They cover under 5 percent of the texture. No pod part uses the cold colour, which belongs to the deep.
8. **One driver per motion group.** Treads, drive wheels, belt and exhaust flutter all follow track speed. Drill tiers follow drill speed. Tread links move by UV scroll or a 2-4 frame flipbook, not by moving cubes.
9. **Greebles in texture first.** Rivets, vents, seams and hatch lines are painted. A greeble becomes a cube only if its smallest side is 2 pixels or more and it changes the silhouette (exhaust stack, antenna, rotor hub, tank). No cube is smaller than 1 pixel.
10. **The canopy is its own cube.** The window band or porthole is a separate translucent cube in the `canopy` bone, set 1 pixel into the hull, with a frame that stands proud of it.
11. **State shows on the model.** Each component tier is a child bone. Hull damage is three crack-overlay stages (like the iron golem at 75, 50 and 25 percent health). A gauge or sight glass shows the fuel level.
12. **Lit from the top front, and repaintable.** The base texture is lighter on top and front faces and darker below and behind. Paint, metal, rubber and glass use separate palette ramps, so paint per charter is one palette swap.

**Cube budget.** [tooling-options](tooling-options.md#what-makes-the-pod-a-machine-not-a-box) sets the Mole at 80-150 cubes on a 256x128 to 256x256 texture. Points of reference: the Ad Astra rover is 76 boxes, the Tunnel Digger 81 cubes, the Magitek tunnel armor 100, the iron golem 8. A suggested split: hull and canopy 30-45, drill 15-25, tracks and running gear 20-30, lamps 8-12, folded rotor 6-10, greebles 10-20 (89-142 in all). Cubes beyond the budget are a sign that detail belongs in the texture.
