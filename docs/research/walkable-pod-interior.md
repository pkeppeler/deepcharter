# Feasibility report: walkable moving drill base (Fabric, MC 26.3, dedicated server)

Research date: 2026-10-06. Items marked **[unverified]** come from reasoning or knowledge of older versions, not from a source I checked. Effort figures are my estimates for one experienced modder.

## Bottom line

- **Nothing on Fabric 26.3 can be used as a dependency today.**
  - Create's newest official build is NeoForge 1.21.1.
  - Sable is 1.21.1 only.
  - The Valkyrien Skies port for newer versions is unofficial and only reaches NeoForge 26.2.
  - Immersive Portals is archived. Its 26.x fan port has no public source.
- **Every approach that physically carries players (1, 2, 3) has documented failures in multiplayer.** Players fall through, rubber-band or desync, and it gets worse with ping and speed.
- **Recommendation: approach 4, with the interior in a reserved area of the *same* dimension, not a separate dimension.** The interior is real blocks that never move. Only the outside shell moves. Walking, using blocks and sleeping work like vanilla at any speed and any ping. All the risk sits in client-side rendering, which can fail gracefully.

## Platform facts that matter

- **Release cycle.** MC 26.3 "Wilderness Bound" came out on 2026-09-15, with Fabric Loader 0.19.5 and Loom 1.17. Drops come about every quarter (26.1 in March, 26.2 in June, 26.3 in September). Carpet's master branch already targets 26.4-snapshot-2. Every mixin you write has to be re-ported each quarter, so how deeply an approach patches the game is a recurring cost.
- **Unobfuscated game.** Since 26.1 there is no remapping and Java 25 is required.
- **Rendering.** 26.2 added an experimental Vulkan backend. Raw OpenGL calls must go through Blaze3D. This affects contraption rendering, portal stencil tricks and render-to-texture.
- **26.3 straw beds.** They skip the night *without* setting the spawn point. That is useful for any bed that moves.
- **Sodium and Iris.** Sodium 0.9.3-alpha.1 (2026-09-20) and Iris 1.11.7 (2026-09-30) both exist for Fabric 26.3.

## 1. Create-style contraptions

**How it works.** A moving entity holds a copy of the blocks. Create's `ContraptionCollider` (branch mc1.21.1/dev, read 2026-10-06) works like this:

- It moves the entity's box and motion into the contraption's local space and runs oriented-box collision there.
- It shrinks the player's box by 2/16 so they get stuck less.
- It runs collision for the local player **on the client only**. The server skips server-side players.
- It then sends a `ClientMotionPacket`. On receipt the server sets onGround, applies fall damage and resets the flying-kick counter.
- Other players are kept on by a raycast "safety lock" snap, and only on translating contraptions.

So the model is: the client is trusted, and the server is made lenient.

**Multiplayer.** These Create issues show the failure pattern:

- #3808, piston elevators drop players, open since 2022-09-21.
- #3576, deadly rubber-banding on trains, 2022-08-03.
- #9309, falling through a contraption depending on ping, 2025-09-28.

**Availability on 26.x.**

- Create: newest is 6.0.10, NeoForge 1.21.1 (Modrinth, 2026-04-21). The dev-status wiki says a 26.1 port is in progress with no ETA (page undated).
- Create Fabric: stops at 1.20.1 (6.0.8.1, 2025-12-02). The port team said on 2025-11-29 it will skip 1.21.1 (Fabricators-of-Create#1849).
- "Create Fly": a Fabric fork that its author says was finished with AI. It runs up to 26.2 (2026-06-14), has no 26.3 build, and its own page calls it unstable.

**Licence.** Create's code has been MIT since 2025-09-21; its assets are all rights reserved. We could borrow the collider's ideas or code with attribution.

**Effort.** Our own version is 3–6 months. Moving only along axes, with rotation only when parked, cuts the collision work a lot. Even so, every block inside needs a virtual-level wrapper to be usable, and sleeping needs custom code because the bed isn't really in the world. I believe Create has no beds on contraptions **[unverified]**.

**Failure modes.** Falling through at high ping, other players jittering, flying kicks, per-block interaction bugs, and heavy rendering and collision patches to re-port every quarter.

## 2. Physics sub-levels ("ships")

**Sable** (by ryanhcode; powers Create Aeronautics):

- **How it works.** "Sub-levels contain normal Minecraft chunks, entities, and block-entities" at a moving position and orientation. Physics runs in Rapier (Rust natives).
- **Players.** Entities standing on a sub-level are marked as "tracking" it. They are then "networked relative to the sub-level", "interpolated relative to the sub-level", and log in and out at a position relative to it (Sable wiki). This is the right networking model.
- **Versions.** Releases 2.0.x for Fabric and NeoForge, **1.21.1 only**, Java 21. Latest is 2.0.6 (2026-10-03). First public versions came out 2026-04-17/18. The README calls it "incredibly intrusive… prone to extensive compatibility issues".
- **Licence: PolyForm Shield 1.0.0.** You can depend on it or modify it, except for "any product that competes with the software or any product the licensor… provides using the software". A vehicle mod could plausibly be read as competing with Aeronautics. Get written permission before relying on it. This is my reading, not legal advice.
- **Open bugs:**
  - #1304 (2026-06-30): a standing player desyncs on an elevator inside a sub-level, with "moved too quickly!".
  - #1560 (2026-09-13): fast sub-levels phase through terrain, more than 50% of the time.
  - #1582 (2026-09-22): desync that follows server tick lag.
  - #1546 (2026-09-08): precision problems far from the origin.
  - Aeronautics #137: hanging under a moving contraption counts as flying.
- **Upgrade outlook.** Aeronautics staff on 2026-05-03: it will "most likely stay on 1.21.1 for the foreseeable future", tied to Create. Aeronautics itself is NeoForge 1.21.1; its code is MIT and its assets are all rights reserved.

**Valkyrien Skies 2:**

- **Versions.** The mod is LGPL-3.0. The latest official release is 2.4.11 for 1.20.1 Forge/Fabric (2026-04-10). A 1.21.1 branch exists but is unreleased; it got Sable compatibility on 2026-05-02 (PR #1806). An unofficial port has 2.5.0 for **26.2, NeoForge only, beta** (2026-08-24), and 1.21.11 for Fabric.
- **Physics core.** The core (`vs-core` / Krunch) is a separate build, not public on GitHub. Its licence is **[unverified]**.
- **Players.** `EntityDragger.kt` applies the ship's per-tick change in position to standing entities. Its own comment says: "TODO: Do collision on [addedMovement], as currently this can push players into blocks".

**Effort.** Porting Sable to Fabric 26.3 ourselves is more than 6 months, with a heavy re-port every quarter. Waiting for upstream has no ETA.

## 3. Actually moving real blocks, step by step

**How it works.** This is a generalised vanilla piston. Vanilla pistons move blocks over 2 game ticks, carry the entities standing on them, and the client simulates the same push, so players aren't rubber-banded. Carpet (MIT, already on 26.x) has mixins for moving block entities with pistons, which we could borrow.

**Good at 0.5–2 blocks/s.** That is one step every 10–40 ticks. Chests, terminals and beds stay real blocks. A short piston-style transition per step gives a "thump-thump" inchworm motion that suits a drill. Effort is 4–8 weeks.

**What breaks in fast flight** **[mostly unverified, reasoning]**:

- Each step rewrites around 400–900 blocks. That means moving block-entity data, light updates, and redrawing 8–12 chunk sections on every client, 10–20 times a second. Expect flicker and holes.
- Carrying players by teleport triggers the teleport-confirm handshake every step. The server ignores movement packets until the client confirms, so players freeze or jerk.
- Piston-style carrying drops players under lag. The wiki says: "due to lag, slime engines can bug allowing the player to fall through the machine."
- Blocks that depend on neighbours (beds, doors, torches, redstone) pop off unless updates are suppressed and blocks are placed in a safe order.
- Chunks ahead must be force-loaded, and a crash mid-step tears the structure apart unless moves are journaled.
- A sleeping player's bed position has to be re-pointed every step. Vanilla wakes you if the bed is gone.

## 4. Static interior with windows

- **Separate dimension (TARDIS-style).** Vanilla's client can only hold one dimension at a time. A *live* view of a different dimension needs a second client-side world, which is what Immersive Portals built.
  - qouteall's Immersive Portals (Apache-2.0) is archived. Its README has said "not being maintained now" since 2026-04-21, and it stops at 1.21.1.
  - The 26.x fan port "Immersive Portal" (CurseForge, covers 26.1.2–26.3, updated 2026-09-30) is early access. Its repo has no source ("Where's the code?", 2026-09-03), and it crashed on dedicated servers ("Does not work on dedicated servers", 2026-09-14; a fix was claimed 2026-09-27).
  - Entering or leaving the interior is a dimension change. In-dimension sleep and time behaviour on 26.x is **[unverified]**.
  - Effort with a true portal is more than 4 months.
- **Same-dimension interior (recommended).** The interior sits in a reserved, enclosed area of the vehicle's own dimension. Then a render-to-texture camera only needs the one world the client already has.
  - SecurityCraft (MIT, has a **26.3 branch**, NeoForge beta, Java 25, commit 2026-10-03) already shows live camera views on frames. Its code swaps the main render target, calls `gameRenderer.renderLevel()` from a marker-entity camera, and caps the frame rate.
  - It also includes the two patches this design needs. One stores chunks outside the player's normal range on the client (`ClientChunkCacheMixin`). The other sends chunks around a far-away camera from the server (`ChunkMapMixin`).
  - **Cost compared with the other approaches:**
    - No physics networking at all.
    - All vanilla interactions and beds work for free.
    - One extra world render per frame while windows are visible. Lower resolution or frame rate keeps that down.
    - The server streams chunks around the exterior for each occupied vehicle, roughly like one extra player at reduced radius.
    - Only medium-depth patching (rendering and chunk cache) to re-port each quarter.
    - Effort 6–12 weeks.
  - **Known render-to-texture failures:** SecurityCraft #616 (feed not shown with Iris shaders, 2026-06-15), #596 (feeds freezing, 2025-10-25), #571 (several feeds at once, 2025-05-01).

## 5. Vanilla primitives

- **Happy ghast.** Mojang avoided the problem entirely. While players stand on it, the ghast "becomes stationary"; `still_timeout` is set to 10 when a player is less than 2 blocks above. When it becomes mobile again it "loses this collision" and anything on top is flung off (minecraft.wiki, read 2026-10-06).
- **Boats and shulkers.** They have solid hitboxes, but nothing carries a standing player along **[unverified on 26.3; consistent with the happy-ghast design]**.
- **What vanilla does offer:**
  - Riding as a passenger (smooth, but no walking).
  - The piston push (the basis of approach 3).
  - `block_display` entities riding a root entity: the client interpolates them, which makes a cheap moving hull with no collision.
  - Relative teleports.
- **Server movement checks.** Vanilla validates player movement on the server: "moved too quickly", "moved wrongly" and the flying kick. Any carrying scheme has to relax these, as Create does. The exact limits are known from 1.21.x and **[unverified on 26.3]**.
- **Existence proof on 26.3.** "Sails and Airships" (Fabric 26.3, all rights reserved, 2026-09-28) says: "Players can walk the deck while sailing, though deck walking on a moving craft is at your own risk."

## Comparison

| | 1 Contraption (own build) | 2 Sable / VS | 3 Real-block steps | 4 Same-dimension interior + windows | 5 Vanilla |
|---|---|---|---|---|---|
| Walk while moving | Yes (custom collider) | Yes | Yes, in lurches | Yes (the floor never moves) | No |
| Use blocks / terminals | Needs a wrapper per block type | Works natively (real chunks) | Works natively | Works natively | – |
| Sleep | Custom code | Probably **[unverified]** | Re-point the bed each step | Works natively; spawn point stays valid | – |
| Multiplayer | Trusts the client; falls through at high ping | Relative networking; open desync and phasing bugs | Drops players under lag | Vehicle motion causes no player error | – |
| Fast flight | Falling-through risk grows | Phases through terrain (#1560) | Breaks down | Unaffected (only window chunk streaming) | – |
| On Fabric 26.3 today | Nothing to depend on | No | Build it ourselves (Carpet as reference) | Build it ourselves (SecurityCraft as reference) | Yes |
| Code we could borrow | Create (MIT) | Sable non-compete licence; VS LGPL plus closed core | Carpet (MIT) | SecurityCraft (MIT); Immersive Portals (Apache-2.0, 1.21.1) | – |
| Effort (est.) | 3–6 months | Over 6 months, or blocked | 4–8 weeks (drilling speeds only) | 6–12 weeks | Under 2 weeks (seats or parked only) |
| Quarterly re-port cost | High | Very high | Low | Medium | None |

## Recommended architecture

- **Interior:** the real 7×5×12 build in a reserved area of the vehicle's dimension, sealed and on a grid of slots (the "shipyard" idea from VS and Sable, without any transforms). It never moves.
- **Exterior:** a server-side hull entity. Its collision box stays aligned to the axes and it turns only in 90° steps. Drilling and collision are a simple server-side sweep. Its look is either `block_display` passengers (smoothed by vanilla) or a pre-built mesh of the hull.
- **Doors and hatches:** teleport within the same world, so there is no loading screen. Players leaving mid-flight inherit the vehicle's speed. If the vehicle changes dimension, the interior is copied to a slot in the new dimension (a rare event).
- **Windows, phase 1:** flat external-camera screens, like the Cyclops cameras.
- **Windows, phase 2:** parallax windows. Render the world once more, from the player's camera mapped from interior space to exterior space, into a render target. Draw that onto the window quads using screen-space coordinates. Hide the hull, or use an oblique near plane, so the hull doesn't block the view. This is my own design and is untested.
- **Streaming:** follow SecurityCraft's chunk-cache, chunk-map and tracked-entity patches so the exterior's chunks and mobs reach players inside.
- **Pilot seat:** sends control packets to the hull. The pilot can switch to a full-screen exterior camera.

**Why this one:**

- It is the only option where walking, using blocks and sleeping don't depend on speed or ping.
- It can't drop or rubber-band players. Its hard parts are client rendering, which can degrade to a lower-quality view instead of breaking gameplay.
- It needs no 26.3 dependency, and the TARDIS mods are precedent for the pattern.
- **Trade-offs:**
  - Crew are not physically on the hull. You can't step onto the roof mid-flight, and people outside don't see the crew (ghost avatars could fix that).
  - Map mods show players at the interior slot.
  - Moving to another dimension means relocating the interior.
  - Rendering compatibility risk with Sodium, Iris and Vulkan.
  - If "real" must mean physically on the hull, only approaches 1–3 deliver that.

## Tech-spike plan (10 working days)

**Build:**

1. One reserved interior slot with a chest, a furnace, a bed, a 26.3 straw bed and one custom terminal, plus a door that teleports in and out.
2. A hull entity on a scripted path (straight line, a 90° turn while parked, and a vertical shaft) at 0.5, 2, 8 and 16 blocks/s, drilling a 3×3 face at the slow speeds.
3. Exterior chunk and entity streaming to the players inside (chunk radius 4–6).
4. One window wall: first a flat feed, then the parallax version at half resolution with a frame cap.

**Test setup:** a dedicated server, 4 clients, 2 vehicles, and injected latency of 0, 150 and 300 ms with ±30 ms jitter.

**Measure:**

- "moved wrongly" / "moved too quickly" log lines and any server corrections to player position.
- Door-transit glitches and fall damage.
- Client FPS and frame-time percentiles (p50/p95/p99) with windows off and on, on OpenGL and on the Vulkan backend, with Sodium and Iris present.
- Seconds of missing chunks in the window per 30 s, at each speed.
- How far the window image lags the hull's position.
- Extra server tick time per vehicle.
- Bandwidth per player inside, compared with an elytra flight at the same speed.
- Whether night skipping works.

**Pass (proposed thresholds, calibrate as you go):**

- Zero movement errors or corrections across every speed and latency combination over 30 minutes.
- Door transit with no loading screen and no damage.
- Window costs at most 35% of average FPS, and p99 frame time stays at or under 2× the baseline.
- No missing chunks at 2 blocks/s or slower after 2 s of warm-up, and at most 1 s per 30 s at 16 blocks/s.
- At most +2 ms server tick time per moving vehicle.
- Streaming bandwidth at most 1.5× the elytra baseline.
- The bed sets spawn and skips the night; the straw bed skips the night without setting spawn.
- No client classes load on the dedicated server.

**Fail rules:**

- Window rendering fails on performance or compatibility: keep the architecture and fall back to flat screens at low frame rate, or to a stylised "sonar" view built from streamed block data.
- Movement errors appear inside: this kills the premise. I expect it to pass.

**Optional 2-day control spike:** approach 3 at 2 blocks/s with 150 ms ping. Measure step cost and how many of 100 steps the player stays aboard. This keeps the "physically real" option honest.

## Fallback ranking

1. Approach 4 with flat camera screens only.
2. Approach 3 (inchworm real blocks) at drilling speeds, with seats during fast flight.
3. Approach 1: our own axis-only contraption where player movement is simulated in the vehicle's local frame, borrowing Create's MIT ideas.
4. Approach 2: adopt Sable if a Fabric 26.x build ships *and* the licensor gives written permission.
5. Your stated fallback: walking only while parked or anchored, with vanilla seats while moving.

## Sources

- Fabric for 26.3 / 26.2 / 26.1 (2026-09-15 / 2026-06-15 / 2026-03-14): https://fabricmc.net/2026/09/15/263.html · https://fabricmc.net/2026/06/15/262.html · https://fabricmc.net/2026/03/14/261.html
- 26.3 features (2026-09-15): https://beebom.com/minecraft-26-3-wilderness-bound-update-live-now/
- Create repo, licence (2025-09-21) and ContraptionCollider: https://github.com/Creators-of-Create/Create (read 2026-10-06). Issues #3808, #3576, #9309.
- Create dev status (undated): https://wiki.createmod.net/users/development-status
- Create Fabric 1.21.1 decision (2025-11-29): https://github.com/Fabricators-of-Create/Create/issues/1849
- Create versions: https://modrinth.com/mod/create · https://modrinth.com/mod/create-fabric · https://modrinth.com/mod/create-fly
- Sable repo, licence, wiki "Working with Entities", releases (2.0.6, 2026-10-03), issues #1304, #1560, #1582, #1546: https://github.com/ryanhcode/sable
- Aeronautics repo, licence, issues #909 (2026-05-03), #137, #143: https://github.com/Creators-of-Aeronautics/Simulated-Project · https://modrinth.com/mod/create-aeronautics
- VS2 repo (release 2.4.11 2026-04-10, PR #1806, EntityDragger.kt): https://github.com/ValkyrienSkies/Valkyrien-Skies-2 · unofficial port: https://modrinth.com/mod/valkyrien-skies-unnof-port (2026-08-24)
- Immersive Portals (archived 2026-04-21): https://github.com/iPortalTeam/ImmersivePortalsMod · fan port: https://www.curseforge.com/minecraft/mc-mods/immersive-portal (2026-09-30) · https://github.com/fmpapierz/SeamlessPortals
- SecurityCraft 26.3 branch, FrameFeedHandler / ClientChunkCacheMixin / ChunkMapMixin, issues #616, #596, #571: https://github.com/Geforce132/SecurityCraft
- Carpet (MIT, movable block entities): https://github.com/gnembon/fabric-carpet
- Wiki pages (read 2026-10-06): https://minecraft.wiki/w/Happy_Ghast · https://minecraft.wiki/w/Flying_machine · https://minecraft.wiki/w/Piston
- Sails and Airships (2026-09-28): https://modrinth.com/mod/sails-and-airships · Sodium for 26.3: https://modrinth.com/mod/sodium

## Still unverified

- Vanilla movement-check limits and relative-teleport behaviour on 26.3.
- Whether vanilla carries players standing on boats or shulkers.
- Sleeping on Create contraptions or Sable sub-levels.
- The licence of VS's physics core.
- Sleep and time behaviour in custom dimensions on 26.x.
- Whether Vulkan is still experimental in 26.3.
- The parallax-window cost.
- All effort figures.
