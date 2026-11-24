---
status: accepted
---

# A seam is crossed by a swap to a client world built ahead, and the teleport with its fade is the fallback

Issue [#217](https://github.com/pkeppeler/deepcharter/issues/217), slice 1 in [#1159](https://github.com/pkeppeler/deepcharter/issues/1159). Settles what [ADR 0085](0085-depth-is-one-axis-over-a-stack-of-tall-dimensions-and-the-dimension-machinery-stays.md) left open ("how seamless the seam must be, and whether the fade and the arrival pocket stay"). Builds on the seam of [ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md), whose rejection of a fade as the plan still holds.

## Decision

- **The target is a seamless swap.** The server streams the next world's arrival area ahead of the crossing, the client builds a second world from it, and at the seam it switches to that world without vanilla's respawn path. Position, velocity, rotation and interpolation carry over.
- **Today's teleport and fade are the fallback, not the plan.** `BreachService`'s crossing, its fading `BreachPayload` and its carved arrival pocket stay as the path a crossing takes when the swap cannot run: the client has no pre-built world, or the arrival is obstructed.
- **A refusal sends the whole vehicle through the fallback.** The swap is claimed for a vehicle and its riders together. If any rider's client refuses, the whole vehicle crosses by the teleport: it does not bounce back up, and it never splits, with some riders below and some above.
- **The arrival pocket is a safety net.** It is carved only on the fallback path and when the swap's arrival point is obstructed. It is no longer the normal way to arrive.
- **The server streams the arrival area first, in every case.** This is slice 1, and it ships on its own because it also removes the stall the fallback has today: a layer-dimension crossing no longer generates the chunks it lands in on the server thread.
  - **The ticket.** `deepcharter:seam_arrival` is a ticket type registered in `BuiltInRegistries.TICKET_TYPE`: it loads and simulates its chunks and keeps the dimension active, with no `PERSIST` flag, so a restart takes none and the approach starts again. It times out 100 ticks after its last refresh. It is taken with `ServerChunkCache.addTicketAndLoadWithRadius`, which schedules the chunks on the worker threads and returns a future, and refreshed every 20 ticks with `addTicketWithRadius`.
  - **The area.** Radius 4 around the chunk under the root vehicle: 81 chunks, at full status.
  - **Whose approach.** One approach per root vehicle carrying a player, keyed by the root's UUID and listing the riders. A pod with two riders is one approach, not two. The approach has no level or player reference.
  - **The trigger.** The local distance `d` above the level's floor, in blocks, so it needs no global depth ([#1113](https://github.com/pkeppeler/deepcharter/issues/1113)). A vehicle streams when `d` is inside 160 blocks of the floor, or when `d` is inside the lead and its descent speed would cover the lead's distance in the time the design fall speed does. The lead is 0.32 blocks per row of the world below, rounded up: 656 blocks for 2048 rows, 1301 for 4064. The design fall speed is terminal speed, 3.9 blocks a tick (78 a second). The descent speed is read over the last 10 ticks, so a vehicle that sits or climbs streams only inside the 160 blocks.
  - **The last level, the campaign world and the surface.** A level with no level below it takes no ticket, and neither does the campaign world: its layers are bands of one level and nothing is crossed ([ADR 0061](0061-a-crossing-in-the-campaign-world-is-an-event-in-place-found-by-the-root-vehicles-y-with-the-crust-rows-as-hysteresis.md)). The surface floor is not a seam ([ADR 0029](0029-the-campaign-is-one-tall-world-and-the-uncharted-chain-joins-through-seams.md)).
  - **The release.** An approach ends when its vehicle leaves the zone, changes level, is removed or disconnects. The ticket is not removed: it lapses on its timeout. A crossing that finds the area not loaded still works, waits for the chunks on the server thread as before and logs how many of the 81 were loaded.
- **Nothing is sent to the client in slice 1.** The player sees no change. Slice 1 streams the descent only: a rise through a level's top still lands by the fallback crossing, as before.
- **The Sodium outcome.** If Sodium's per-level meshing (Sodium is in the shipped pack) makes a pre-built second client world impossible, the shipped pack has the fallback crossing only, and the seamless swap is for a client without Sodium. A Sodium spike runs before any slice 2 design: it decides whether slice 2 is the swap, or a faster fallback.
- **The crust at a mid-layer seam is open.** Whether a seam that falls inside a layer carries a crust, or none, is with the user. Slice 1 does not depend on it: the trigger reads the distance to the level's floor and nothing about what is there.

## Considered Options

- **(a) A seamless swap alone, with no fallback.** It is the target, but it cannot be the only path. It needs Sodium's per-level `RenderSectionManager` to hold a second world, and mixins on the respawn path (`ClientPacketListener`'s dimension change, the level renderer, sound and the player's interpolation). A crossing then costs about 4.8 to 9.5 MB of chunk data per rider (81 chunks at 256 to 4064 rows). Every client without the pre-built world, every refusal and every obstructed arrival would have no way through. Rejected as the sole path.
- **(b) The teleport with its fade as the plan.** Cheap and already built, but it is what ADR 0029 rejected: vanilla's respawn path rebuilds every mesh, stops all sound and freezes the player for about 1.2 seconds, at the speed of a fall of 78 blocks a second. Kept as the fallback only. That rejection still covers it.
- **(c) The arrival pocket as the way to arrive.** A carved room under the ceiling hides the stall, but it cuts the world the player lands in, and the carve needs the chunks loaded on the server thread. Kept as a safety net.
- **Sending the chunks to the client in slice 1.** It would let the client build ahead at once, but it ships 4.8 to 9.5 MB per rider for a swap that does not exist yet, and it cannot be tested without the Sodium spike. Deferred to slice 2.
- **A fixed 600-block lead.** ADR 0029's figure holds for a 256-tall world only. At 0.32 blocks a row a 4064-tall world needs 1301 blocks, and a fixed lead is too short for it and wastes streaming on a short one. Rejected for a lead that scales with the height below.
- **A vanilla ticket type** (`PORTAL` or `ENDER_PEARL`). Their timeouts (300 and 40 ticks) are not ours, and portals and pearls share them: a refresh by one is a refresh of the other. A type of our own keeps the timeout in `LayerTuning` and the ticket findable in a test.

## Consequences

- **No player-visible change in slice 1.** The campaign world has no seam below it yet. In the layer dimensions, a crossing stops stalling on generation when the player has lingered near the floor.
- **Cost.** Each approach holds 81 chunks of the world below. N approaches cost N times that, and a world with players at N places near a floor loads N areas. The ticket simulates its chunks, so mobs of the lower world tick under it.
- **Registry sync.** `deepcharter:seam_arrival` is a registry entry of a vanilla registry. Fabric's registry sync needs nothing from the mod beyond registering it in `init`: the entry is registered on a client and a server alike, in the same order, and the client never reads it. A client without the mod cannot join anyway, as with the mod's blocks.
- **Slices 2 and 3.** Slice 2 sends the chunks and builds the client world, after the Sodium spike. Slice 3 is the swap itself and the claim, accept or refuse handshake. A refusal sends the whole vehicle through the fallback, as above.
