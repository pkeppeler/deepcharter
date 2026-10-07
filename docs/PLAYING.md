# Playing Deep Charter {{VERSION}}

Deep Charter is a co-op Minecraft mod in the spirit of Motherload: drive a pod down through the layers, drill ore, sell it, upgrade. This is an early friends build. Expect missing parts.

You get two files from the host: `deepcharter-friends-{{VERSION}}.mrpack` (the client) and an address to join. The host also runs `deepcharter-server-{{VERSION}}.zip`.

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
- **Operator commands** (ops only): `/deepcharter pod spawn` puts a pod next to you, `/deepcharter layer goto <n>` moves you to the surface of layer n, `/deepcharter pod dump` empties the ridden pod's cargo.

## Private audio pack

The original game's sounds are not in the build. If the host gives you the audio pack (a `.zip` they build privately, see issue #57), drop it in your instance's `resourcepacks` folder (Prism: right-click the instance, **Folder**, **.minecraft/resourcepacks**), then enable it under **Options, Resource Packs**. Without it the game uses placeholder sounds. Never share the pack outside the group.

Host only: with the extracted originals in `original_flash_game/extracted/sounds` and `ffmpeg` installed, run `tools/build-audio-pack.sh`. It writes `private/audio/deepcharter-audio-pack.zip` (the folder `private/` is git-ignored, and the script refuses to write anywhere that is not). Hand that zip to friends directly. Never commit it, and never upload it to GitHub or any download page.

## Known issues

- Content is partial: placeholder text, sounds and models; the drill, shops and most layers are not in yet.
- Pods can run out of fuel and strand you. Bring coal.
- Sodium and Lithium are speed mods and are not ours. If something looks wrong, remove them from the instance (**Edit, Mods**) and check whether the problem goes away. Say so in your report.
- Do not add other mods. Iris does not support this Sodium version yet, and old Sodium add-ons are blocked by it.
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
