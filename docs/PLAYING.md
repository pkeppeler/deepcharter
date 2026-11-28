# Playing Deep Charter {{VERSION}}

Deep Charter is a co-op Minecraft mod in the spirit of Motherload: drive a pod down through the layers, drill ore, sell it, upgrade. This is an early friends build. Expect missing parts.

You get two files from the host: `deepcharter-friends-{{VERSION}}.mrpack` (the client) and an address to join. The host also runs `deepcharter-server-{{VERSION}}.zip`.

**Start a new world for this version.** The overworld is one tall campaign world: the surface and layers 1 to 3 are bands of one dimension, and a breach is a dig with no teleport. A world made by an earlier version is not opened: see [Old worlds](#old-worlds).

## Old worlds

This version does not open a world made by the version before it. Chunks saved in an old world would have air under them, and players and pods left in the old layer dimensions would be lost. So a world made before it is not migrated.

It refuses the old world, and changes nothing in it. Singleplayer shows "This world cannot be opened", and a dedicated server logs the reason and stops. The older Deep Charter that made the world can still open it. A version that changes the world format again needs a new world in the same way.

To start a new world: in singleplayer, **Create New World**. On a server, delete or move the `world` folder, then start it again.

## Install (Prism Launcher)

You need a Minecraft Java Edition account.

1. Install [Prism Launcher](https://prismlauncher.org/) and sign in with your Microsoft account.
2. Click **Add Instance**, then **Import**, and choose the `.mrpack` file (or drag it in).
3. Prism creates an instance with Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.164.0, Sodium 0.9.2, Lithium 0.26.2 and Deep Charter. It downloads the mods from Modrinth.
4. Java 25 is required. Prism can download it: **Settings, Java, Auto-detect or Download**.
5. Launch the instance.

## Join

1. **Multiplayer, Add Server**, then enter the address the host gave you.
2. The server uses a whitelist. Send the host your Minecraft name first.

## Controls (today)

- **Mount a pod:** right-click it.
- **Dismount:** sneak (default Left Shift).
- **Drive:** the pod steers like a tank. W drives forward along the pod's nose. S backs up, slower, without turning. A and D turn the pod at a steady rate while held and stop the moment you let go; on their own they turn it on the spot. Where you look does not steer it; the mouse only looks around.
- **Camera:** taking the pilot seat switches to the third-person chase view, and leaving the seat brings back the view you had. F5 still cycles it. The view turns with the pod, so the angle between you and the pod stays; driving settles a looked-away chase camera back behind the pod.
- **Cockpit:** in the pilot seat the ribbon along the top names each gauge on a tab under it: FUEL, HULL, LOAD, DEPTH and HEAT. The plate under the scanner gives hull, fuel and depth as numbers, each after the icon of its gauge, and the ore in the bay after a cart. While the pod sinks fast, a SINK tab under the ribbon's middle names the bar that shows the sink. The handbook's controls pages carry the same legend.
- **Rotor:** hold jump (Space) to lift. Weight slows the climb: the bay's ore, everything in your pack (ore included), a towed pod and lining bricks. The LOAD gauge on the cockpit ribbon shows the weight, a segment for each tenth of what the pod can still climb with, and its red mark is that limit (half the engine's power on a stock Mole). At or past the mark the pod cannot climb at all, though it still drives: the bar turns red, the last lamp lights and the plate says OVERLOADED. Ore in your pack counts double while you ride (see Loose ore). Drop cargo from the pod screen to climb again. Let go and the pod sinks; tap jump to slow it. A landing faster than 14 blocks a second costs hull: the SINK plate under the top ribbon shows the sink against that limit, turning amber near it and red past it. Nothing brakes it for you near the ground: feather the jump key to land soft, since a hard landing costs 3.5 hull for each block a second over the limit and a free fall down a deep shaft wrecks a stock pod.
- **Sprint:** hold sprint (default Left Ctrl) with W and the pod drives twice as fast on the ground and in the air (8 blocks a second on a stock engine, against the 4 of the normal drive), for about 29% more fuel a block; the readout shows SPRINT while it boosts. The sprint outruns a sprint-jumping player, and the normal drive is slower than a sprinting player. Sprint never makes the drill faster, never boosts S, and does nothing in a bore as wide as the pod.
- **Drill:** sprint (default Left Ctrl). Sprint on the ground with no direction key drills down; drive forward (W) into a wall to drill sideways along the compass direction the pod faces nearest. The pod turns square to the grid before it bores, so the tunnel stays straight, and in a bore as wide as itself A and D do not steer it, except that one press turns it a quarter to the next compass direction so you can branch (then W drills the branch). S never drills.
- **Pod screen:** sneak and right-click a pod, or press the inventory key (E) in a seat, as on a horse. It opens for anyone when the pod has no owner or its owner is dormant or gone, and only the owner charter can fit parts. It shows every part track, the fuel slot and its gauge, and the hold. Drop a crafted unit on its track's slot (or shift-click it) to fit it; a fitted part stays fitted, and a unit that cannot go in stays with you, with the reason shown. Right-clicking the pod with a unit still fits it. Fuel in the fuel slot (one stack) feeds the tank whenever it has room for a whole piece. The hold is as big as the cargo bay and works like a chest for anything you stow, and ore moves in and out of it too. Ore in your pack counts as loose ore while you ride.
- **Dump cargo:** a pod too heavy to lift says so when you jump. Open the pod screen (E) and press Q on a stack in its hold to dump one (Ctrl+Q for the stack) under the pod. Dumped items lie on the ground: nobody in a seat picks them up, so step out to gather them. Over lava they burn.
- **Loose ore:** ore carried in your pack while you ride weighs double against the pod's lift, slows the drill and the steering (half a percent for each unit of ore mass, at most half), and adds one hull point of damage to a hard landing for each ore. The cockpit readout says LOOSE while anyone aboard carries some, and the pod screen says it over your pack. Rack ore in the hold to avoid all three.
- **Refuel:** put coal, charcoal or biofuel in the pod screen's fuel slot, or right-click the pod while holding it. They stack to 16, weigh like ore (a pack slows you on foot and cuts your pod's lift), and give 2, 1 and 3 litres. They are for the field, where there is no pump: pump fuel is cheaper per litre once the soot is paid for. Coal and charcoal leave soot, and biofuel a little. Past 3 litres of soot the engine loses speed and burns more, and at 12 litres it limps (30% of the speed, twice the burn) but never stops. Once the colony's repair station works, a pod in limp mode will not launch from the colony: the repair station's SERVICE ENGINE clears the soot for $10 a litre, or as many whole litres as the account pays for, and with no money SCRAPE FLUE is free: wait a minute at the station and the pod leaves just under the limit, still slow. Biofuel is three stalk mash, fermented. The low-fuel tone sounds at 21% of the tank. A full tank is about four minutes of drilling, driving and climbing, so a first trip down 40 blocks and home leaves fuel to spare. A pod burns fuel only while someone is aboard; parked with nobody inside, it burns none.
- **Stranded:** a pod that runs dry goes dark, and nobody comes to fetch it. Refuel it (a fuel item, or a reserve tank) and it wakes, so carry one. Without one, a crewmate can tow the pod home with a tow cable (they get out, hook the two pods and drive off), or, where the shaft allows, you can climb out on foot and come back with fuel. A pod you cannot get back to stays where it stopped, with its ore: plan the trip home before the low-fuel tone. A pod stranded in a layer, or left in one by its pilot low on fuel, tells every online member of its charter once, with its name, layer and place, that someone with a tow cable can tow it home; a wreck in a layer says the same after its report. The Fuel Is Life chapter has a page on the rescue, and the handbook's controls pages cover hooking it.
- **Terminals:** right-click one in the colony (fuel pump, ore processor, upgrade terminal, repair station, hangar console). A terminal that is offline takes the parts that repair it: put them in from its screen. Or right-click it while holding one of its own parts: the part goes in under the same rules as its slot, and the screen opens (a refused part stays in your hand, with the reason shown). Park your pod beside a terminal to use it on the pod. From a pod's seat, right-click with an empty hand or the handbook (and nothing in the off hand) to open the terminal you are looking toward, if it is within reach of the pod's hull and nothing solid stands between; "No terminal in reach" means none is. Closing the screen leaves you seated.
- **Handbook:** press H. It lists nine chapters of directives that tick off as you do them, drawn as a flowchart per chapter. Right-click a sheet of paper in the world to file its Note.
- **Parts catalogue:** in the inventory, a crafting table or a furnace, click an item (or hover it and press R) for its recipes, right-click it (or press U) for what it is used in. Hovering any slot works too, a chest or the creative inventory included: there R and U open the recipe on a screen of its own, and Escape goes back to the chest. An item your charter knows no recipe of says so by the cursor. Backspace, or the < knob on the recipe, goes back through the recipes you looked at. Press M on a recipe for everything it takes in total, down through the recipes your charter knows: for each material, how many you hold, how many it takes and how many you are short. What you already hold is used first and not broken down. The wheel scrolls a long list, and M again shows the recipe. Press A on an item, or click the star at the top right of its recipe, to add it to your favourites or take it off. On a recipe, FILL GRID moves the ingredients into the open crafting grid (hold Shift for as many as fit); if you lack some, it shows the recipe as a ghost over the grid with the missing slots in red. A recipe that needs the 3x3 grid says it needs a crafting table when you are at the inventory. SPEC SHEET on the recipe opens the item's spec sheet, and the sheet's RECIPES link comes back to the recipes. The first time you craft or buy a Company item a small slip in the corner names its new spec sheet; J opens the latest one.
- **Sonar (navigator):** sit in the Prospector's second seat. V pings, and G marks the echo you are looking at on the pilot's HUD. A ping is loud.
- **Thrown kit and tether:** buy flares, decoys, charges and the tether at the repair station, and use them from the hand on foot, not from a seat.
- **Suit:** from layer 3 down the suit on your back runs on a reserve of about 3 minutes. A powered pod seat or a sound outpost hall refills it, and an air canister adds a minute.
- **Tow cable, make it:** six iron ingots around a lead. A lead is five string, and one salvage cloth cuts into four string, so keep a few cloths from the lockers and crates. The recipe book and parts catalogue show the cable recipe once the handbook asks you to tow the Prospector; holding string shows the lead, and holding cloth shows string from cloth. The Fuel Is Life chapter's third page gives the recipe in words, early.
- **Tow cable, use it:** get out of your pods, hold a tow cable and right-click the pod that will tow, then right-click the pod it should tow (a wreck too, never the other way round). A line from the first pod to you shows it is picked; right-click empty ground, or wait half a minute, to put the cable away. A refused second click says why and keeps the first pod. Right-click a towed pod with the cable to take it off. The cable does nothing from a seat.
- **Operator commands** (ops only): `/deepcharter pod spawn` puts a pod next to you, `/deepcharter layer goto <n>` moves you to the surface of layer n, `/deepcharter pod dump` empties the ridden pod's cargo.

## Your first hour

There are no trees and no ore on the surface. The Company's salvage crates lie in a ring round the colony, 100 to 160 blocks out: break one by hand for planks, sticks, cloth, scrap iron, scrap copper and a little redstone. Pale stalks grow on the floors of craters, such as the great pit south of the square, and in patches among the crates: fell them for wood (break one block and everything above it bursts at once in a spore puff, like sugar cane, the drops scattering; now and then a great stalk, three thin trunks braided round each other with a shelf jutting off each, gives a whole stack; break one trunk and only that trunk comes down), craft the stalk into planks, and burn planks to melt scrap in a furnace. Regolith rock is your stone. That is enough for tools, a torch and every part the colony's terminals need. The crates do not come back, but your pod's rock does: with a spoil hopper fitted, park at the ore processor and press the spoil it kept into new salvage crates.

You start with a few Company rations. When you run low, the canteen dispenser in the Lamp and Pick, the burned-out bar on the square, gives a ration every time you use it, and a fungal stalk cooks into a poor meal on a campfire or in a smoker.

## Optional mod: dynamic lights

[LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) makes light sources shine while they move. A lit pod's glow slides smoothly with the pod instead of stepping from block to block. A torch or lava bucket in your hand lights up too. Nothing else changes: the dark stays dark, and the Retained throw no light. A pod without a lights part takes its glow from the light around it, so beside a torch or lamp it shines too. It is for the client only, so the host does not need it.

- **Version:** use exactly **4.13.0+26.3**, the one we tried. [Download it from Modrinth](https://modrinth.com/mod/lambdynamiclights/version/4.13.0+26.3) and drop the jar in the instance's mods folder (Prism: right-click the instance, **Folder**, **.minecraft/mods**). The build does not include it, because its licence does not let us hand the jar out.
- **What breaks:** nothing we saw in the dark layers, where the cockpit gauges, the headlamp beam and the tow cable all drew as before. We tried it without Sodium and Lithium; with them it is untested. We did not try the colony's terminals or the scanner map with it. A Minecraft update can stop it loading, so keep the version above.
- **Cost:** it rebuilds the chunks near a light each time the light moves. If the game stutters, open **Options, Video Settings**, find its **Dynamic Lights** setting and turn it down or off, or remove the jar.
- **Reporting a bug:** reproduce it without the jar first. A flicker or stutter that only shows with it is LambDynamicLights' and not ours.

## Private audio pack

The original game's sounds are not in the build. If the host gives you the audio pack (a `.zip` they build privately, see issue #57), drop it in your instance's `resourcepacks` folder (Prism: right-click the instance, **Folder**, **.minecraft/resourcepacks**), then enable it under **Options, Resource Packs**. Without it the game uses placeholder sounds. Never share the pack outside the group.

Host only: with the extracted originals in `original_flash_game/extracted/sounds` and `ffmpeg` installed, run `tools/build-audio-pack.sh`. It writes `private/audio/deepcharter-audio-pack.zip` (the folder `private/` is git-ignored, and the script refuses to write anywhere that is not). Hand that zip to friends directly. Never commit it, and never upload it to GitHub or any download page.

## Known issues

- Content is partial: only the surface and layers 1 to 3 exist, with placeholder text, sounds and models. The lore and the creatures are unfinished.
- Sodium and Lithium are speed mods and are not ours. If something looks wrong, remove them from the instance (**Edit, Mods**) and check whether the problem goes away. Say so in your report.
- Do not add other mods, except the optional one above. Old Sodium add-ons are blocked by it. Iris 1.11.7 now supports Sodium 0.9.2, but only as a dev opt-in, not for friends: see [iris-audit.md](tooling/iris-audit.md).
- Back up your world before updating the build.

## Report a bug

Open an issue at <https://github.com/pkeppeler/deepcharter/issues> if you have access, otherwise message the host. Include:

1. What you did and what you expected.
2. Whether it still happens with Sodium and Lithium removed.
3. Your build: `{{VERSION}}`.
4. `logs/latest.log` from the instance folder (Prism: right-click, **Folder**), and the newest file in `crash-reports/` if the game crashed.

## Running the server (host)

Unzip `deepcharter-server-{{VERSION}}.zip`.

1. Java 25 is required.
2. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA), then set `eula=true` in `eula.txt`. The zip ships with `eula=false`; accepting it is your call.
3. Run `./start.sh`. On first run it downloads the pinned Fabric API and Lithium (sha512-checked, from `mods.lock`) and the Fabric server launcher, which in turn downloads Mojang's server jar. The zip contains none of these.
4. In the server console, `whitelist add <name>` for each friend and `op <name>` for yourself.
5. Forward TCP port 25565 to the machine, or use a tunnel. Hosting choices and costs are yours.
