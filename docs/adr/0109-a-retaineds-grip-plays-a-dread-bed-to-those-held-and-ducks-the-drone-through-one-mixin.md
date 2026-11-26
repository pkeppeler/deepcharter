---
status: accepted
---

# A Retained's grip plays a dread bed to those held, and ducks the drone through one mixin

Issue #1450. A playtester asked that the creature that grabs the pod bring scary music, and that a Tally's theft have a sound of its own. Moloch's things take and never kill, so the music is dread, not a scare.

## Decision

- **The bed is client-side, one per client** (`client/sound/DreadBed`). It plays while a living Retained holds the player at that client, on foot, or the pod they ride, as pilot or passenger. A figure's hold is synced entity data (`LamplessFigure.HOLDING`, the network id of the pod or person it holds, which replaced the `CLINGING` flag), and `LamplessFigure.holds(level, person)` reads it, so every rider of a held pod hears the bed and nobody else does. Two figures on one player (one on the person, one on their pod) play one bed.
- **It follows the grip, and only the grip.** It rises over `SoundTuning.dreadFadeInTicks` while held and falls over `dreadFadeOutTicks` once not, then stops; a grip that returns while it fades turns the same bed up again. A pod's lights do not end it: a light keeps the next figure off but frees no one already held ([CREATURES.md](../CREATURES.md#the-retained), ADR 0046), and music that faded for a light would tell the player a lie. What ends a grip ends the bed: the figure struck off or cut away, the pod gone.
- **It is heard from the cab** (relative, no attenuation): the bed is what holding feels like to those held. It is in the hostile creatures' slider, so a player who turns the music off still hears that something has hold of them.
- **The drone ducks under it through one mixin**, `client/sound/mixin/BiomeLoopDuckMixin`. Target: the tail of `BiomeAmbientSoundsHandler.LoopSoundInstance.tick()`. The drone is a biome's `ambient_sounds` loop ([ADR 0057](0057-every-shipped-sound-is-built-from-a-committed-recipe-and-a-layers-drone-lives-in-its-biome-data.md)), played by vanilla as that instance, which sets its own volume from its crossfade each tick; the mixin multiplies it by `DreadBed.droneGain()`, which falls to `SoundTuning.droneDuck` as the bed rises and comes back as it falls. No Fabric event reaches a playing sound's volume, and the category volume is the player's own setting.
- **The Tally's take has its own cue**, `creature.tally_take`: the counter ticks over, a receipt is stamped, a worn desk bell rings once. It plays at the Tally, on both take paths (the pod's bay and the pack on foot), so the person robbed hears it at their hatch or their back, and anyone within the event's 16 blocks learns what a Tally does. The Tally's click, clamp and break, vanilla stand-ins until now, are recipes too.
- **Every one of these is a recipe** in `tools/sounds/recipes.py`, synthesis from a seed and no source file (ADR 0057). The take, click, clamp and break play at a place, so they are mono; the bed is stereo.

## Consequences

- A Minecraft update that renames the target stops the game at launch (`defaultRequire: 1`): fix the target.
- Every biome loop ducks, the surface's wind included, since a figure can hold a person anywhere.
- The bed and the take have no original, so `tools/audio-pack-map.txt` leaves them out and the private audio pack leaves them alone.
