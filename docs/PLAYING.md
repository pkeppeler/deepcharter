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
- **Drive:** the pod steers like a vanilla boat. W drives forward along the pod's nose. S backs up, slower, without turning. A and D turn the pod. Where you look does not steer it; the mouse only looks around.
- **Camera:** taking the pilot seat switches to the third-person chase view, and leaving the seat brings back the view you had. F5 still cycles it. The view turns with the pod, so the angle between you and the pod stays; driving settles a looked-away chase camera back behind the pod.
- **Cockpit:** in the pilot seat the ribbon along the top names each gauge on a tab under it: FUEL, HULL, LOAD, DEPTH and HEAT. The plate under the scanner gives hull, fuel, cargo and depth as numbers, each after the icon of its gauge. The handbook's controls pages carry the same legend.
- **Rotor:** hold jump (Space) to lift. Weight slows the climb: the bay's ore, everything in your pack (ore included), a towed pod and lining bricks. The LOAD gauge on the cockpit ribbon shows the weight, a segment for each tenth of what the pod can still climb with, and its red mark is that limit (half the engine's power on a stock Mole). At or past the mark the pod cannot climb at all, though it still drives: the bar turns red, the last lamp lights and the plate says OVERLOADED. Dump or drop ore to climb again.
- **Drill:** sprint (default Left Ctrl). Sprint on the ground with no direction key drills down; drive forward (W) into a wall to drill sideways along the compass direction the pod faces nearest. The pod turns square to the grid before it bores, so the tunnel stays straight, and in a bore as wide as itself A and D do not steer it, except that one press turns it a quarter to the next compass direction so you can branch (then W drills the branch). S never drills.
- **Refuel:** right-click the pod while holding coal, charcoal or biofuel. They stack to 16, weigh like ore (a pack slows you on foot and cuts your pod's lift), and give 2, 1 and 3 litres. They are for the field, where there is no pump: pump fuel is cheaper per litre once the soot is paid for. Coal and charcoal leave soot, and biofuel a little. Past 3 litres of soot the engine loses speed and burns more, and at 12 litres it limps (30% of the speed, twice the burn) but never stops. Once the colony's repair station works, a pod in limp mode will not launch from the colony: the repair station's SERVICE ENGINE clears the soot for $10 a litre, or as many whole litres as the account pays for, and with no money SCRAPE FLUE is free: wait a minute at the station and the pod leaves just under the limit, still slow. Biofuel is three stalk mash, fermented. The low-fuel tone sounds at 21% of the tank. A full tank is about four minutes of drilling, driving and climbing, so a first trip down 40 blocks and home leaves fuel to spare.
- **Stranded:** a pod that runs dry goes dark. Refuel it (a fuel item, or a reserve tank) and it wakes. If nobody does, the HUD counts down a minute and the Company's winch crew hauls you and the pod home for a recovery fee of $30 for each layer you are down (layer 2 is $60, layer 3 $90). The fee comes from your charter's account, up to what it holds. A bare account costs hull instead, a point a dollar, never debt, and never below 10 hull. Any ore in the bay stays where the pod stopped, for about 30 minutes, and the crew leaves a litre of fuel to drive to the pump.
- **Terminals:** right-click one in the colony (fuel pump, ore processor, upgrade terminal, repair station, hangar console). A terminal that is offline takes the parts that repair it: put them in from its screen. Park your pod beside a terminal to use it on the pod.
- **Handbook:** press H. It lists nine chapters of directives that tick off as you do them, drawn as a flowchart per chapter. Right-click a sheet of paper in the world to file its Note.
- **Parts catalogue:** in the inventory, a crafting table or a furnace, click an item (or hover it and press R) for its recipes, right-click it (or press U) for what it is used in. Hovering any slot works too, a chest or the creative inventory included: there R and U open the recipe on a screen of its own, and Escape goes back to the chest. An item your charter knows no recipe of says so by the cursor. Backspace, or the < knob on the recipe, goes back through the recipes you looked at. Press A on an item, or click the star at the top right of its recipe, to add it to your favourites or take it off. On a recipe, FILL GRID moves the ingredients into the open crafting grid (hold Shift for as many as fit); if you lack some, it shows the recipe as a ghost over the grid with the missing slots in red. A recipe that needs the 3x3 grid says it needs a crafting table when you are at the inventory. SPEC SHEET on the recipe opens the item's spec sheet, and the sheet's RECIPES link comes back to the recipes. The first time you craft or buy a Company item a small slip in the corner names its new spec sheet; J opens the latest one.
- **Sonar (navigator):** sit in the Prospector's second seat. V pings, and G marks the echo you are looking at on the pilot's HUD. A ping is loud.
- **Thrown kit and tether:** buy flares, decoys, charges and the tether at the repair station, and use them from the hand on foot, not from a seat.
- **Suit:** from layer 3 down the suit on your back runs on a reserve of about 3 minutes. A powered pod seat or a sound outpost hall refills it, and an air canister adds a minute.
- **Tow cable:** ride a pod, hold a tow cable and right-click another pod. Right-click a towed pod with the cable to take it off.
- **Operator commands** (ops only): `/deepcharter pod spawn` puts a pod next to you, `/deepcharter layer goto <n>` moves you to the surface of layer n, `/deepcharter pod dump` empties the ridden pod's cargo.

## Your first hour

There are no trees and no ore on the surface. The Company's salvage crates lie in a ring round the colony, 100 to 160 blocks out: break one by hand for planks, sticks, cloth, scrap iron, scrap copper and a little redstone. Pale stalks grow on the floors of craters, such as the great pit south of the square, and in patches among the crates: fell them for wood (break one block and everything above it sags and bursts in a spore puff, the drops scattering; now and then a great stalk, three thin trunks braided round each other with a shelf jutting off each, gives a whole stack; break one trunk and only that trunk comes down), craft the stalk into planks, and burn planks to melt scrap in a furnace. Regolith rock is your stone. That is enough for tools, a torch and every part the colony's terminals need. The crates do not come back, but your pod's rock does: with a spoil hopper fitted, park at the ore processor and press the spoil it kept into new salvage crates.

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
