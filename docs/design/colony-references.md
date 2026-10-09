# Colony references

A references study for the colony rebuild. The user's verdict on today's colony ([current-state.md, section 5](current-state.md#5-structures)): minimal, "clearly re-skinned Minecraft blocks", and a statue that still reads as a cross. The new bar is GTNH-level complexity and believable industrial architecture, inside [art-direction.md](art-direction.md#4-colony) (sections 4, 5 and 8) and [ADR 0030](../adr/0030-art-direction-decisions.md).

This page feeds three designs: 2 to 3 building concepts, a 40 to 60 block headframe with a winding wheel and cables, and 2 to 3 Founder statue concepts.

Each entry has **Sources** (official, wiki, source-code or museum pages), **Look** (what it does), **We take** (the design move) and **Does not fit**. Image links point to the page that hosts the image. We take ideas, never structures, models or textures. Scale: 1 block is 1 m.

## 1. Modpack tech mods

### GregTech: New Horizons (GTNH) multiblocks

- **Sources:** GTNH wiki: [Electric Blast Furnace](https://wiki.gtnewhorizons.com/wiki/Electric_Blast_Furnace) ([image](https://wiki.gtnewhorizons.com/wiki/File:EBF.png)), [Distillation Tower](https://wiki.gtnewhorizons.com/wiki/Distillation_Tower), [Large Chemical Reactor](https://wiki.gtnewhorizons.com/wiki/Large_Chemical_Reactor), [Processing Array](https://wiki.gtnewhorizons.com/wiki/Processing_Array), [Assembly Line](https://wiki.gtnewhorizons.com/wiki/Assembly_Line), [Fusion Reactor](https://wiki.gtnewhorizons.com/wiki/Fusion_Reactor). Textures: [GT5-Unofficial iconsets](https://github.com/GTNewHorizons/GT5-Unofficial/tree/master/src/main/resources/assets/gregtech/textures/blocks/iconsets), for example [OVERLAY_FRONT_ELECTRIC_BLAST_FURNACE_ACTIVE_GLOW.png](https://github.com/GTNewHorizons/GT5-Unofficial/blob/master/src/main/resources/assets/gregtech/textures/blocks/iconsets/OVERLAY_FRONT_ELECTRIC_BLAST_FURNACE_ACTIVE_GLOW.png).
- **Look:** every multiblock is a grid of one casing, broken by hatches and one controller face. The EBF is a 3 x 3 x 4 box: casings top and bottom, 16 heating coils in the middle, a muffler on top. The Distillation Tower is 3 to 12 layers tall with one output hatch per layer, so its height shows its job. The Assembly Line is a row of 5 to 16 identical slices. A controller face has four textures: `OVERLAY_FRONT_ELECTRIC_BLAST_FURNACE`, `_ACTIVE`, `_GLOW` and `_ACTIVE_GLOW`. Coils have a `_BACKGROUND` and a `_FOREGROUND` layer. Many active overlays are animated. The muffler hatch emits smoke particles while the machine runs ([MTEHatchMuffler](https://github.com/GTNewHorizons/GT5-Unofficial/blob/master/src/main/java/gregtech/api/metatileentity/implementations/MTEHatchMuffler.java)).
- **We take:** a repeating casing grid broken by hatches, vents and pipes, with one glowing controller face per building. A repeated slice as the module of a long building. Height that tells function: one outlet per level. Four states per face: off, on, off glow, on glow. Smoke from the roof only while the machine works.
- **Does not fit:** the late-tier sci-fi glow overload: fusion rings, exotic casings, glow on every face. The steam age (bronze boilers, the bricked blast furnace) and the early steel casings fit. GTNH machines are also flat boxes; we add roofs, eaves and setbacks.

### Create and Create: Steam 'n' Rails

- **Sources:** Create on [Modrinth](https://modrinth.com/mod/create) and [CurseForge](https://www.curseforge.com/minecraft/mc-mods/create), [Create wiki](https://wiki.createmod.net), [Windmill](https://create.fandom.com/wiki/Windmill). Textures: [andesite_casing_connected.png](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/resources/assets/create/textures/block/andesite_casing_connected.png), [brass_casing_connected.png](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/src/main/resources/assets/create/textures/block/brass_casing_connected.png). [Steam 'n' Rails](https://modrinth.com/mod/create-steam-n-rails) ([gallery](https://modrinth.com/mod/create-steam-n-rails/gallery), [wiki](https://github.com/Layers-of-Railways/Railway/wiki)).
- **Look:** motion is the detail. Shafts, cogs, belts, water wheels and windmill bearings turn in plain view. Casings come in andesite, brass, copper and train. Each casing has a `_connected` sheet, so a wall of casing reads as one panel. Industrial-iron and weathered-iron windows have `_connected` and `_end` variants. Steam 'n' Rails adds stations, smokestacks and semaphores.
- **We take:** visible working parts carry "working" at a distance: a turning wheel, a moving belt, a smoking stack. Show the drive in an open bay instead of a closed box. Connected sheets so a long corrugated wall has no block seams. Window strips with end pieces.
- **Does not fit:** whimsical brass, toy colours, wood and cottage builds. Contraptions are a player system; we need only a few animated set pieces.

### Immersive Engineering

- **Sources:** [Modrinth](https://modrinth.com/mod/immersiveengineering), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-engineering), [FTB wiki](https://ftb.fandom.com/wiki/Immersive_Engineering). Source: [multiblock textures](https://github.com/BluSunrize/ImmersiveEngineering/tree/1.21.1/src/main/resources/assets/immersiveengineering/textures/block/multiblocks), [village houses](https://github.com/BluSunrize/ImmersiveEngineering/tree/1.21.1/src/main/resources/data/immersiveengineering/structure/village/houses), [sign textures](https://github.com/BluSunrize/ImmersiveEngineering/tree/1.21.1/src/main/resources/assets/immersiveengineering/textures/block/metal_decoration/sign).
- **Look:** "retrofuturism, industry and multiblocks". You build the excavator (3 x 7 x 8, with a bucket wheel), crusher, arc furnace, coke oven and blast furnace from plain blocks. When formed, one OBJ model with one texture sheet replaces them (`excavator.png`, `bucket_wheel.png`). The coke oven has `_off` and `_on` textures. Steel scaffolding has connected variants (`_open`, `_open_sides`, `_open_u`). A warning sign is a `base_front` plate plus an icon overlay. The engineer's house comes in five biome palettes.
- **We take:** big set pieces (winding wheel, sheave deck, crusher) as sculpted models, not block stacks. Steel scaffolding and sheet metal as the base vocabulary. Signs as one enamel plate plus a lettering or icon overlay. One plan with palette swaps, which `colony/palette/*.json` already allows.
- **Does not fit:** treated wood (this world has no trees) and the cosy village house. IE's grey-brown is too dull for a gaudy Company town.

### Mekanism

- **Sources:** [Modrinth](https://modrinth.com/mod/mekanism), [wiki: Induction Matrix](https://wiki.aidancbrady.com/wiki/Induction_Matrix), [fission_reactor_casing.png.mcmeta](https://github.com/mekanism/Mekanism/blob/1.21.x/src/generators/resources/assets/mekanismgenerators/textures/block/fission_reactor_casing.png.mcmeta).
- **Look:** hollow boxes with casing edges and faces of casing, ports or glass, up to 18 x 18 x 18. The reactor casing's connected-texture data lists its ports and logic adapter in `connect_to`, so one frame flows around every port. Induction providers have a separate `_led` texture.
- **We take:** connect the casing texture across every block of one building (panel, hatch, door), so hatches sit inside one frame. Glow on its own LED layer.
- **Does not fit:** clean white-and-glass sci-fi. Too clean even for a too-clean town.

## 2. Structure and terrain mods

### YUNG's Better Mineshafts, Better Strongholds, Better Dungeons

- **Sources:** [Better Mineshafts](https://modrinth.com/mod/yungs-better-mineshafts) ([gallery](https://modrinth.com/mod/yungs-better-mineshafts/gallery), [source](https://github.com/YUNG-GANG/YUNGs-Better-Mineshafts)), [Better Strongholds](https://modrinth.com/mod/yungs-better-strongholds/gallery), [Better Dungeons](https://modrinth.com/mod/yungs-better-dungeons/gallery), [BlockStateRandomizer](https://github.com/YUNG-GANG/YUNGs-API/blob/26.1.2/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/api/world/randomize/BlockStateRandomizer.java) in YUNG's API.
- **Look:** Better Mineshafts has 13 biome variants, mesa and red desert among them, plus workstations, miners' outposts and rare surface openings. Supports repeat at a fixed rhythm. Lanterns along the bridges draw the shaft's line in the dark. The API picks each block from a weighted list, so a wall mixes plain, cracked and worn variants.
- **We take:** a weighted randomiser for wear: rust at rivets, dents, a missing panel, dust, in a set ratio per palette. A strict frame rhythm. Small lamps that trace a structure at night. The shaft collar as a set piece.
- **Does not fit:** wood, cobwebs, lush growth, fantasy.

### Repurposed Structures and Towns and Towers

- **Sources:** [Repurposed Structures](https://modrinth.com/mod/repurposed-structures-fabric) ([gallery](https://modrinth.com/mod/repurposed-structures-fabric/gallery), [wiki](https://github.com/TelepathicGrunt/RepurposedStructures/wiki)), [Towns and Towers](https://modrinth.com/mod/towns-and-towers) ([gallery](https://modrinth.com/mod/towns-and-towers/gallery)).
- **Look:** Repurposed Structures makes biome variants of vanilla structures with no new blocks. Towns and Towers has over 50 villages and outposts, many "inspired by real-life buildings or architectural styles".
- **We take:** one plan, many palettes, kept as separate files. Start each building from a real type (pay office, lamp room, engine house), not a generic box; real types bring believable proportions.
- **Does not fit:** vanilla blocks as the final material (the re-skinned look the user rejected), and rural timber villages.

### When Dungeons Arise

- **Sources:** [Modrinth](https://modrinth.com/mod/when-dungeons-arise) ([gallery](https://modrinth.com/mod/when-dungeons-arise/gallery): Mechanical Nest, Foundry Entrance).
- **Look:** 30+ very large structures. At night the Mechanical Nest reads only as many small lit windows on tall dark towers.
- **We take:** scale is many small lights on a large dark mass. A 50-block frame needs lamps at regular heights to exist at night.
- **Does not fit:** floating fantasy, airships, dungeon clutter.

### Terralith and Regions Unexplored

- **Sources:** [Terralith](https://modrinth.com/mod/terralith) ([wiki](https://stardustlabs.miraheze.org/wiki/Terralith), [gallery](https://modrinth.com/mod/terralith/gallery)), [Regions Unexplored](https://modrinth.com/mod/regions-unexplored) ([gallery](https://modrinth.com/mod/regions-unexplored/gallery)).
- **Look:** Terralith makes almost 100 biomes "using just Vanilla blocks", among them Bryce Canyon, Painted Mountains, Red Oasis and Volcanic Crater. It uses slabs and layers to soften steps in the land. Regions Unexplored adds its own ground blocks per biome.
- **We take:** soften the pad. Today it is a sharp-edged plate cut out of the terrain. Use slab steps, ramps and dust drifts against every wall, and band nearby mesa faces in a palette gradient.
- **Does not fit:** forests, water, flowers.

## 3. Texture packs at 16x

- **[Stay True](https://www.curseforge.com/minecraft/texture-packs/stay-true):** a remaster of the default look. Depth comes from random block variants and connected textures (Continuity on Fabric), not from resolution. An add-on gives [emissive ores](https://modrinth.com/resourcepack/glowing-emissive-ores-stay-true). We take: variants plus connected sheets plus a glow layer. Does not fit: lush biome colours.
- **[Mizuno's 16 Craft](https://resourcepack.net/mizunos-16-craft-resource-pack/)** ([creator](https://mizunomcmemo.blogspot.com/), [Modern Mizuno's](https://www.curseforge.com/minecraft/texture-packs/modern-mizunos)): clean, low-noise textures and a strong palette. We take: few colours per texture, drawn rivets and seams, no speckle. Does not fit: the medieval subject.
- **[JICKLUS](https://modrinth.com/resourcepack/jicklus)** ([gallery](https://modrinth.com/resourcepack/jicklus/gallery)): "rustic but classic" 16x with warm, worn surfaces. We take: worn edges and warm shadows. Does not fit: rustic wood.
- **How GTNH and Create layer:** a base, a per-face overlay, an active overlay, a glow mask and a connected sheet. This is the art-direction stack: overlays, emissive glow, animated active states, connected textures.

## 4. Company towns

### Pullman, Illinois

- **Sources:** [NPS: The Town of Pullman](https://home.nps.gov/pull/learn/historyculture/the-town-of-pullman.htm), [NPS overview](https://home.nps.gov/pull/learn/historyculture/a-brief-overview-of-the-pullman-story.htm), [Wikipedia](https://en.wikipedia.org/wiki/Pullman,_Chicago) ([clock tower](https://commons.wikimedia.org/wiki/File:Pullman_Chicago_Clock_Tower.jpg)).
- **Look:** a model town (1880 to 1884) by Solon Beman, every building in one master plan. One brick, from local clay, gives visual unity. A clock tower marks the works.
- **We take:** one material language for all buildings, one landmark over the work, and the too-tidy order of a planned town.
- **Does not fit:** Queen Anne domestic detail, lawns.

### Saltaire, Yorkshire

- **Sources:** [Wikipedia](https://en.wikipedia.org/wiki/Saltaire), [Roberts Park](https://en.wikipedia.org/wiki/Roberts_Park,_Saltaire) ([image](https://commons.wikimedia.org/wiki/File:Roberts_Park,_Saltaire_(25th_September_2010).jpg)), [listing: statue of Sir Titus Salt](https://www.britishlistedbuildings.co.uk/en-337886-statue-of-sir-titus-salt-set-in-centre-o).
- **Look:** a model mill town (1851 to 1871). The mill dominates. The founder stands in bronze (F. Derwent Wood, 1903) in a frock coat, holding a parchment, on an ashlar base with reliefs of the angora goat and the alpaca that made the fortune.
- **We take:** the founder statue is part of the town plan, and the plinth tells where the money came from.
- **Does not fit:** stone terraces, a green park.

### Kennecott, Alaska

- **Sources:** [NPS: Kennecott Mines NHL](https://www.nps.gov/wrst/learn/historyculture/kennecott-mines-national-historic-landmark.htm), [Wikipedia](https://en.wikipedia.org/wiki/Kennecott,_Alaska) ([mill exterior](https://commons.wikimedia.org/wiki/File:Kennecott_Mill_exterior.jpg), [HAER drawing](https://commons.wikimedia.org/wiki/File:Kennecott_Mines,_ca._1920_-_Kennecott_Copper_Corporation,_On_Copper_River_and_Northwestern_Railroad,_Kennicott,_Valdez-Cordova_Census_Area,_AK_HAER_AK,20-MCAR,1-_(sheet_3_of_15).png)).
- **Look:** a 14-storey concentration mill steps down the slope. Ore enters at the top and falls through crushing, concentrating, leaching and flotation. The mill is a stack of gabled sheds and towers in one red paint with white trim. A self-contained town (store, hospital, school, recreation hall) sits below.
- **We take:** the **stepped section**: one building down a slope, one process stage per level, chutes and conveyors between. One Company paint scheme for the whole town.
- **Does not fit:** the timber. On a rust-red world, red walls vanish; we invert it: cream walls, red trim.

### Bodie, California

- **Sources:** [Wikipedia](https://en.wikipedia.org/wiki/Bodie,_California) ([panorama](https://commons.wikimedia.org/wiki/File:Bodie_September_2016_panorama_1.jpg)), [DesertUSA](https://www.desertusa.com/bodie/bodie.html), [California State Parks brochure](https://parks.ca.gov/pages/509/files/Bodie_Brochure_091626_FINAL.pdf).
- **Look:** a ghost town kept in "arrested decay", as time left it. The Standard Mill stands above the town.
- **We take:** keep the town as it was left, with nothing tidied. It fits a company town that emptied in one night.
- **Does not fit:** bleached boards and shacks. Our town is cheerful and too clean; damage stays local.

### Jerome, Arizona

- **Sources:** [Town of Jerome: Then and Now](https://jerome.az.gov/jerome-then-and-now), [Wikipedia](https://en.wikipedia.org/wiki/Jerome,_Arizona) ([panorama](https://commons.wikimedia.org/wiki/File:Jerome,_Arizona,_wider_panorama.jpg)).
- **Look:** buildings climb a hillside on three terraced levels; their backs stand on posts and frames.
- **We take:** build against terraces and prop buildings on steel stilts. A town on levels has a skyline; a flat pad does not.
- **Does not fit:** brick and frame houses; we use steel.

## 5. Headframes and winding engines

### Butte, Montana

- **Sources:** [Main Street Butte: Light UP Butte](https://mainstreetbutte.org/see-do/headframes-light-up-butte/), [HAER record, Anselmo Mine](https://loc.gov/pictures/item/mt0095/) ([headframe and tipple photo](https://commons.wikimedia.org/wiki/File:ANSELMO_HEADFRAME_AND_TIPPLE_LOOKING_EAST_-_Butte_Mineyards,_Anselmo_Mine,_Butte,_Silver_Bow_County,_MT_HAER_MONT,47-BUT.V,1-A-32.tif)), [Butte, America's Story: the Anselmo](https://www.verdigrisproject.org/butte-americas-story-blog/butte-americas-story-episode-240-the-anselmo).
- **Look:** about a dozen steel "gallows frames" stand over the town, 99 to 200 ft (30 to 61 m) tall. The Anselmo frame (1911 to 1912, rebuilt 1936 to 1937) is a riveted lattice: near-vertical front legs, back legs sloped toward the hoist house, a railed sheave deck and a mast. An ore bin and tipple lean against it. Red LED strings outline eight frames at night.
- **We take:** the lattice A-frame with X-braced panels, a railed deck and a mast. Lamp strings that outline the frame at night, in sodium amber.
- **Does not fit:** red light; it is not one of our three lights.

### Zollverein Shaft XII, Essen

- **Sources:** [Wikipedia](https://en.wikipedia.org/wiki/Zollverein_Coal_Mine_Industrial_Complex) ([rear view](https://commons.wikimedia.org/wiki/File:Zeche_Zollverein_-_Schacht_12_-_R%C3%BCckansicht_-_2013.jpg), [evening](https://commons.wikimedia.org/wiki/File:Zeche_Zollverein_abends.jpg)), [Zollverein history](https://www.zollverein.de/zollverein-unesco-world-heritage-site/history/), [KuLaDig: north winding engine house](https://www.kuladig.de/Objektansicht/P-WBuschmann-20090713-0028).
- **Look:** a 55 m "Doppelbock" (double trestle) by Schupp and Kremmer, opened 1932. Two big inclined struts carry a portal with two pairs of sheave wheels. All steel is one red. The buildings are steel frames with brick infill. The north engine house has a box on its roof toward the headframe, where the rope leaves for the upper sheave, and a window over three bays that wraps the corners. Its drive pulley is 7.0 m across.
- **We take:** a bold, simple silhouette: two struts, one portal, visible wheels. One paint for all structural steel. A strict frame-and-panel grid. A rope box on the engine-house roof.
- **Does not fit:** Bauhaus restraint and clean brick. Our Company puts enamel signs, a logo and hazard bands on the same grid.

### Big Pit, Blaenavon

- **Sources:** [Wikipedia](https://en.wikipedia.org/wiki/Big_Pit_National_Coal_Museum) ([image](https://commons.wikimedia.org/wiki/File:Big_Pit,_Blaenavon.jpg)), [listing: pit head, headframe and tram circuit](https://britishlistedbuildings.co.uk/300015280-pit-head-building-headframe-and-tram-circuit-blaenavon).
- **Look:** a steel joist headframe (1921) over a stone-and-brick pit head with a steel superstructure. A tram circuit of corrugated-iron and brick sheds loops from the pit head, with a tippler and weighing scales. Pithead baths came in 1939.
- **We take:** the pit head as one readable loop of work: cage, tippler, scales, sheds.
- **Does not fit:** green hillsides.

### Broken Hill, New South Wales

- **Sources:** [South Mine headframes](https://www.visitbrokenhill.com/Trails/Silver-Trail/96.-South-Mine-Headframes-19191932), [Kintore Reserve](https://www.visitbrokenhill.com/Trails/Silver-Trail/1.-Kintore-Reserve), [Wikipedia: Headframe](https://en.wikipedia.org/wiki/Headframe) ([Kintore headframe](https://commons.wikimedia.org/wiki/File:Kintore_Headframe-2,_Broken_Hill,_NSW,_07.07.2007.jpg), [Quincy Mine shaft house](https://commons.wikimedia.org/wiki/File:QuincyMineNo2Shafthouse.jpg)).
- **Look:** at South Mine, a riveted steel frame (1932, from surplus Sydney Harbour Bridge steel) stands beside a small 1919 timber frame and its winding engine shed. The retired Kintore frame now stands in a reserve.
- **We take:** a big frame next to a small one gives scale. A retired headframe can stand as a monument.
- **Does not fit:** timber frames.

### Winding engine houses

- **Sources:** [Wikipedia: Winding engine](https://en.wikipedia.org/wiki/Winding_engine) ([image](https://commons.wikimedia.org/wiki/File:Steam_winding_engine,_Long_Rake_Spar_Mine_-_geograph.org.uk_-_1586018.jpg)), [Historic England: Bestwood engine house](https://historicengland.org.uk/listing/the-list/list-entry/1017653), [listing: Bersham winding engine house](https://britishlistedbuildings.co.uk/300015825-bersham-colliery-winding-engine-house-esclusham).
- **Look:** Bestwood (1874) keeps a vertical twin-cylinder steam engine with a 6 m cast-iron drum on the second floor. Bersham (about 1933) is red brick with a barrel-vaulted roof. Its gable toward the headframe has a raised centre "to accommodate the line of the winding cable" and two covered rope openings. Large first-floor windows light the engine; the ground floor is almost blind.
- **We take:** the engine house faces the headframe. A raised gable or roof box marks where the ropes leave. The drum shows through big upper windows, lit at night.
- **Does not fit:** brick; we use riveted plate.

### Rules from a 1907 headframe paper

- **Source:** J. M. C. Corlette, ["The design of head frames for mines"](https://openjournals.library.sydney.edu.au/SUES/article/view/2110), Journal of the Sydney University Engineering Society, vol. 12, 1907-08.
- **Numbers:**
  - The best back leg (back-stay) is parallel to the sloping rope. If it bisects the angle between the ropes, the front legs carry no rope load. Common practice sits between the two.
  - The rope slope from pulley to drum "varies considerably". The worked example uses 45 degrees.
  - The example frame: 75 ft to the pulley centre, two pulleys of 10 ft. Height to wheel diameter is 7.5 to 1. Zollverein's 7.0 m pulley against its 55 m tower is close to 8 to 1.
  - The landing stage is 25 ft (about 8 m) above ground.
  - The legs batter (lean in) 1 in 6 to 1 in 10. The example uses 1 in 8: 8.5 ft apart at the top, 27.25 ft at the feet.
  - Cross-bracing between legs stiffens the frame. Each panel is the length between bracing points.

## 6. Pithead buildings

- **Lamp room.** [Wikipedia](https://en.wikipedia.org/wiki/Lamp_room), [National Coal Mining Museum](https://www.ncm.org.uk/collections/topics/lamproom/), [Museum Wales: colliery checks](https://museum.wales/blog/1072/Colliery-checks-and-tokens/). Racks with a numbered place for each lamp, a service window, and a board of numbered checks that shows who is underground. We take: a long wall of numbered racks behind one window, and the check board as a quiet prop. Does not fit: nothing.
- **Pithead baths.** [Big Pit](https://en.wikipedia.org/wiki/Big_Pit_National_Coal_Museum): baths came in 1939, so miners no longer walked home wet; the bathhouse is now listed. We take: the Company's cleanest building, with tiled walls and lockers in long rows. Does not fit: nothing.
- **Pay office.** [Kiveton: in the offices](https://www.kivetonwaleshistory.co.uk/heritage/lives-at-the-pit/in-the-offices), [Museum Wales: pay day, Abergorky Colliery](https://museum.wales/collections/online/object/a0cab7f5-b18f-33fd-83f3-01cef7a1f51f), [NPS: Scrip](https://www.nps.gov/biso/learn/historyculture/scrip.htm) ([coal scrip](https://commons.wikimedia.org/wiki/File:Coal_scrip.jpg)). The front rooms were the wages office, with two pay hatches, one for each seam's men. Miners queued outside on pay day. Scrip was good only at the company store. We take: a front of barred pay hatches in a row, a queue rail and a clock; the grille is the building's face. Does not fit: nothing.
- **Tipple and stamp mill.** [Tipple](https://en.wikipedia.org/wiki/Tipple) ([1900 diagram](https://commons.wikimedia.org/wiki/File:Coal_tipple_diagram_1900.jpg)), [Stamp mill](https://en.wikipedia.org/wiki/Stamp_mill) ([Quincy Mine](https://commons.wikimedia.org/wiki/File:LOC_MI0086_QuincyMine_TIF_00027a_cropStampMill.png)). A tipple is a raised frame with chutes and screens over a track. A stamp mill is a row of identical stamps. We take: a raised ore bin on steel legs over a track; a row of stamps as a vertical rhythm with an animated active state. Does not fit: coal; we sell ore.

## 7. Monumental statues at a distance

From far away a statue is a silhouette. Faces vanish first, then fingers. What stays is the outline and where its widest point is.

| Statue | How it reads from far away | Lesson |
|---|---|---|
| [Christ the Redeemer](https://en.wikipedia.org/wiki/Christ_the_Redeemer_(statue)) ([image](https://commons.wikimedia.org/wiki/File:Christ_the_Redeemer_-_Cristo_Redentor.jpg)) | 30 m tall, arms 28 m wide. The arms were opened "to form the image of the cross itself". | Arms level with the shoulders and a span close to the height make a cross. This is today's Founder. |
| [Angel of the North](https://en.wikipedia.org/wiki/Angel_of_the_North) ([image](https://commons.wikimedia.org/wiki/File:Angel_of_the_North_2010.jpg)) | 20 m tall, 54 m wingspan, weathering steel with vertical ribs. Reads as a cross or an aircraft. | Horizontal spread reads as a cross in any material. Surface ribs give scale. |
| [Statue of Liberty](https://en.wikipedia.org/wiki/Statue_of_Liberty) ([image](https://commons.wikimedia.org/wiki/File:Front_view_of_Statue_of_Liberty_(cropped).jpg)) | One arm raised with the torch; the other holds a tablet against the hip. The robe falls to the feet. | Asymmetry: one diagonal, one arm inside the outline, a base that widens. |
| [The Motherland Calls](https://en.wikipedia.org/wiki/The_Motherland_Calls) ([image](https://commons.wikimedia.org/wiki/File:The_Motherland_Calls,_2019.jpg)) | 52 m figure, 33 m sword raised, the other arm out in a calling gesture, garments blown back. | Diagonals and a stride read from kilometres. Motion beats symmetry. |
| [Worker and Kolkhoz Woman](https://en.wikipedia.org/wiki/Worker_and_Kolkhoz_Woman) ([image](https://commons.wikimedia.org/wiki/File:Ouvrier_kolkhosienne_2.jpg)) | 24.5 m of stainless steel. Two figures stride forward, arms raised together. | One forward diagonal from heel to raised hand reads as action, not a cross. |
| [Lincoln Memorial](https://en.wikipedia.org/wiki/Lincoln_Memorial) ([statue](https://commons.wikimedia.org/wiki/File:The_Lincoln_Memorial_Statue,_with_inscription_in_background.jpg), [NPR on the hands](https://www.npr.org/2009/02/24/100630385/hands-of-an-artist-daniel-frenchs-lincoln-memorial)) | Marble, seated, 19 ft on an 11 ft pedestal. One hand clenched, one open, both on the chair. | A seated figure is one solid block with no free arms. Open hands can rest on it. |
| [Marcus Daly, Butte](https://www.verdigrisproject.org/butte-americas-story-blog/butte-americas-story-episode-269-marcus-daly-statue) ([image](https://commons.wikimedia.org/wiki/File:Marcus_Daly_statue_by_Augustus_Saint-Gaudens,_Montana_Tech_university.jpg)) | Saint-Gaudens bronze of a copper magnate, larger than life. Standing, hat low in one hand, coat over the other arm, on a big granite base. | The industrialist type: a column with small bulges at hip height. The base is about as tall as the figure. |
| [Sir Titus Salt, Saltaire](https://www.britishlistedbuildings.co.uk/en-337886-statue-of-sir-titus-salt-set-in-centre-o) | Frock coat, parchment, reliefs of the animals that made the fortune. | Coat and plinth carry the meaning. |
| [Michelangelo's David](https://www.euronews.com/culture/2023/09/08/culture-re-view-unveiled-today-in-1504-here-are-5-intriguing-facts-about-michelangelos-dav) | Head and arms are larger than true scale, because people see it from below. | Enlarge what must read: here, the hands. |
| [Ruskin, The Stones of Venice II](https://www.lancaster.ac.uk/fass/ruskin/eSoV/texts/vol10/vol10p269.html) | "the upper ornamentation will be colossal, increasing in fineness as it descends". | Coarse detail high up, fine detail at eye level. |

**Why arms straight out read as a cross.** A cross is a vertical bar and a horizontal bar that meet near the top. Arms straight out at shoulder height make exactly that bar. Bronze, open hands and a chest slot do not change it, because none of them survive distance. Three things break it: hands below the shoulders, a span well under the height, and the widest point low (coat hem or plinth), not at the shoulders.

## What this means for Deep Charter's colony

**Key building (ore processor house or pay office)**

1. **A stepped ore-processor house** (Kennecott, GTNH Distillation Tower). Three or four levels step down a terrace or the pad edge. Ore arrives at the top by a covered conveyor gallery from the headframe tipple. Each level is one stage (crush, wash, sort, weigh) with its own chute. A tall gabled head-tower crowns the top.
2. **Casing grid, broken by function** (GTNH, Zollverein, Mekanism). Every facade is a steel frame every 4 or 5 blocks, filled with corrugated or riveted panels in connected sheets. Hatches, louvres, vents, pipes and doors break the grid. Each building has exactly one controller face (the terminal or front window) with a sodium-amber glow and an animated active state.
3. **One Company paint, inverted for a red world** (Pullman, Kennecott). Cream walls, red trim and lettering, black-and-yellow bands at doors, edges and machinery. The ground is rust-red, so red walls would vanish.
4. **The pay office face** (Kiveton, scrip). Two or three barred pay hatches in a row, a brass queue rail, a clock and a cream enamel sign with red lettering above. Inside: the counter, the stub racks, scrip.
5. **Visible work** (Create, GTNH muffler, IE). An open bay with a turning flywheel or belt, a row of stamps, roof vents. Smoke and motion start only when a charter repairs the terminal. The active state is the reward.
6. **Wear and ground** (YUNG's, Terralith, Jerome). A weighted randomiser for rust, dents, missing panels and collapsed roof sections. Slab steps, ramps and dust drifts at the pad edge and against walls. At least one building on steel stilts over a drop.

**Headframe**

7. **Form and size** (Butte, Zollverein, Corlette). A steel lattice A-frame with Zollverein's bold portal on top. Sheave axle at about 48 blocks, railed deck at about 50, maintenance beam and mast to 56-58. Front legs batter 1 in 8: about 5 blocks apart at the top and 17 at the feet. X-braced panels every 5 or 6 blocks. Thin members (models or bar blocks), so the frame stays see-through against the sky.
8. **Wheels sized to read.** The real ratio of axle height to wheel diameter is about 7.5 to 8 to 1 (Corlette, Zollverein): 6 to 7 blocks at 48. Exaggerate it to about 5 to 1, a 9-block wheel, as Michelangelo enlarged David's hands. Two wheels side by side, spokes open, as an animated model that turns while the cage moves.
9. **Back legs follow the rope** (Corlette). The back legs lean toward the engine house, parallel to the rope. A 45 degree rope puts the engine house about 42 blocks back; 55 to 60 degrees brings it to 24 to 30 blocks, which fits the 64-block pad. Draw the cables as rendered lines, not stair-stepped blocks.
10. **The winding engine house** (Bestwood, Bersham, Zollverein). Two storeys of riveted plate on a steel frame. A 6 to 7 block drum seen through large upper windows that wrap a corner, lit at night. A raised gable or roof box where the ropes leave. A stack if the engine is steam.
11. **The bank and the night outline** (Corlette, Big Pit, Butte, WDA). A landing stage about 8 blocks up, with an ore bin and tipple on steel legs over a track, and one readable ore loop with scales. At night, sodium-amber lamps climb the legs at regular heights, with one lamp on the mast.

**Founder statue**

12. **The anti-cross rule.** From the front, the span stays under half the figure's height. Both hands stay below the shoulders, ideally at the waist. The widest point is the coat hem or the plinth. Test every concept as a black silhouette from the square's edge and from the headframe deck.
13. **Concept A: the offer** (Daly, Salt, Liberty's robe). Standing, upper arms close to the body, forearms forward at the waist, palms up and empty. From the front the forearms foreshorten inside the coat outline; from the side they make an L. A long frock coat widens to the hem. The chest slot sits at the sternum in a brass frame.
14. **Concepts B and C: seated, or one gesture** (Lincoln; Liberty, Motherland Calls, Worker and Kolkhoz Woman). B is seated on a heavy chair, hands open on the knees, palms up: one solid block with the slot at its centre. C is asymmetric: one open hand raised on a 45 degree diagonal in greeting, the other open and palm up at the waist, one foot forward. A diagonal never makes a T.
15. **Scale, plinth and material** (David, Ruskin, Salt, Angel of the North). Enlarge the head and hands beyond true scale. Coarse forms at the top, fine detail at eye level. Make the plinth half to fully the figure's height, with bronze reliefs of the trade: ore carts, pods, the bull's-head logo. Bronze with brass trim, and seams or ribs on the surface so the 16x texture shows scale.
