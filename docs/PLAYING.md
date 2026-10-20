# Playing Deep Charter {{VERSION}}

Deep Charter is a co-op Minecraft mod in the spirit of Motherload: drive a pod down through the layers, drill ore, sell it, upgrade. This is an early friends build. Expect missing parts.

You get two files from the host: `deepcharter-friends-{{VERSION}}.mrpack` (the client) and an address to join. The host also runs `deepcharter-server-{{VERSION}}.zip`.

**Start a new world for this version.** The colony replaces what stands at the world spawn, so a world with a build there loses it. The surface terrain changed too: in an old world, new chunks have red regolith land and the old chunks keep their grass and trees, so the two meet in a hard seam. The old colony stays where it was, with no flat ground. A new world has none of this.

## Install (Prism Launcher)

You need a Minecraft Java Edition account.

1. Install [Prism Launcher](https://prismlauncher.org/) and sign in with your Microsoft account.
2. Click **Add Instance**, then **Import**, and choose the `.mrpack` file (or drag it in).
3. Prism creates an instance with Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.162.0, Sodium 0.9.2, Lithium 0.26.2 and Deep Charter. It downloads the mods from Modrinth.
4. Java 25 is required. Prism can download it: **Settings, Java, Auto-detect or Download**.
5. Launch the instance.

## Join

1. **Multiplayer, Add Server**, then enter the address the host gave you.
2. The server uses a whitelist. Send the host your Minecraft name first.

## Controls (today)

- **Mount a pod:** right-click it.
- **Dismount:** sneak (default Left Shift).
- **Move:** W A S D steer the pod.
- **Rotor:** hold jump (Space) to lift.
- **Drill:** sprint (default Left Ctrl). Sprint while on the ground drills down; push into a wall to drill sideways.
- **Refuel:** right-click the pod while holding coal or charcoal.
- **Terminals:** right-click one in the colony (fuel pump, ore processor, upgrade terminal, repair station, hangar console). A terminal that is offline takes the parts that repair it: put them in from its screen. Park your pod beside a terminal to use it on the pod.
- **Handbook:** press H. It lists nine chapters of directives that tick off as you do them. Right-click a sheet of paper in the world to file its Note.
- **Tow cable:** ride a pod, hold a tow cable and right-click another pod. Right-click a towed pod with the cable to take it off.
- **Operator commands** (ops only): `/deepcharter pod spawn` puts a pod next to you, `/deepcharter layer goto <n>` moves you to the surface of layer n, `/deepcharter pod dump` empties the ridden pod's cargo.

## Your first hour

There are no trees and no ore on the surface. The Company's salvage crates lie in a ring round the colony, 100 to 160 blocks out: break one by hand for planks, sticks, cloth, scrap iron, scrap copper and a little redstone. Pale stalks grow on the floors of the deeper craters, such as the great pit south of the square: fell them for wood, craft the stalk into planks, and burn planks to melt scrap in a furnace. Regolith rock is your stone. That is enough for tools, a torch and every part the colony's terminals need. The crates do not come back, but your pod's rock does: with a spoil hopper fitted, park at the ore processor and press the spoil it kept into new salvage crates.

## Optional mod: dynamic lights

[LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) makes light sources shine while they move. A lit pod's glow slides smoothly with the pod instead of stepping from block to block, and a Retained's helmet lamp throws a faint amber pool on the floor under it. A torch or lava bucket in your hand lights up too. Nothing else changes: the dark stays dark, and a pod with no lights part throws no light. It is for the client only, so the host does not need it.

- **Version:** use exactly **4.13.0+26.3**, the one we tried. [Download it from Modrinth](https://modrinth.com/mod/lambdynamiclights/version/4.13.0+26.3) and drop the jar in the instance's mods folder (Prism: right-click the instance, **Folder**, **.minecraft/mods**). The build does not include it, because its licence does not let us hand the jar out.
- **What breaks:** nothing we saw with Sodium 0.9.2 and Lithium 0.26.2 in the dark layers: the cockpit gauges, the headlamp beam, the glowing lamp on a Retained's helmet and the tow cable all drew as before. We did not try the colony's terminals or the scanner map with it. A Minecraft update can stop it loading, so keep the version above.
- **Cost:** it rebuilds the chunks near a light each time the light moves. If the game stutters, open **Options, Video Settings**, find its **Dynamic Lights** setting and turn it down or off, or remove the jar.
- **Reporting a bug:** reproduce it without the jar first. A flicker or stutter that only shows with it is LambDynamicLights' and not ours.

## Private audio pack

The original game's sounds are not in the build. If the host gives you the audio pack (a `.zip` they build privately, see issue #57), drop it in your instance's `resourcepacks` folder (Prism: right-click the instance, **Folder**, **.minecraft/resourcepacks**), then enable it under **Options, Resource Packs**. Without it the game uses placeholder sounds. Never share the pack outside the group.

Host only: with the extracted originals in `original_flash_game/extracted/sounds` and `ffmpeg` installed, run `tools/build-audio-pack.sh`. It writes `private/audio/deepcharter-audio-pack.zip` (the folder `private/` is git-ignored, and the script refuses to write anywhere that is not). Hand that zip to friends directly. Never commit it, and never upload it to GitHub or any download page.

## Known issues

- Content is partial: only the surface and layers 1 and 2 exist, with placeholder text, sounds and models. The lore and the creatures are unfinished.
- Pods can run out of fuel and strand you. Bring coal.
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
