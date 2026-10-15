---
status: accepted
---

# Every shipped sound is built from a committed recipe, and a layer's drone lives in its biome data

Issue #249, section 9 of [art-direction.md](../design/art-direction.md). The mod's sounds were all vanilla placeholders. It now ships its own: a drone bed for the surface and each layer, the Lamp Hymn, and the pod, drill and terminal sounds.

## Decision

- **A sound the mod ships is an OGG under `assets/deepcharter/sounds/` made by a recipe in `tools/sounds/recipes.py`.** The recipe is arithmetic and a fixed seed (Python's standard library), and ffmpeg encodes it. No sample, no recording and no audio of the original game is an input. `tools/sounds/manifest.json` holds two hashes per sound. `pcm_sha256` is the hash of the 16-bit PCM the recipe makes. It does not depend on the encoder, so it proves a recipe still reproduces its sound. `ogg_sha256` proves nobody edited the file.
- **`tools/tests/test_soundgen.py` fails** on an OGG with no recipe, a recipe with no OGG, a recipe whose PCM hash changed, a hand-edited OGG, and an OGG that does not decode to something near its recipe (a recipe changed without a rebuild). The decode comparison runs when ffmpeg is on the path; CI installs it.
- **A drone bed is `ambient_sounds` in the biome data**: a `loop`, plus `additions` for the rare sounds (the surface's far groan; a layer's creaks and knocks, more often the deeper the zone). The `mood` is left out, because vanilla fills it with the cave noise. A surface biome also sets an empty `background_music`, so no vanilla music plays on the surface. No code starts a drone.
- **The layers' music slots (`music.layer_1`, `music.layer_2`) are three seconds of silence.** The drone bed is what a layer plays. The slots stay, with their 30 to 90 second delay in the dimension type, so the private audio pack can still put the original score in them by id.
- **The Lamp Hymn is one motif in three versions** (colony, layer 1, layer 2), each slower and more worn than the last. `HymnMoments` plays it to one player at four moments: founding a charter, joining one, going down a breach (the version of the layer reached), and coming up into the colony.
- **A sound with no original is not in `tools/audio-pack-map.txt`.** `SoundEventsTest` pins the `ambient.` and `hymn.` ids as the ones left out. The private pack leaves them alone.

## Why

- A skin changes the sound by changing a file ([#226](https://github.com/pkeppeler/deepcharter/issues/226)); code only names slots.
- Hashing the PCM instead of the OGG, because an encoder writes a random stream serial and its output changes with its version.
- The standard library instead of numpy and scipy: nothing to install, and the recipes run the same in CI. The cost is speed (the whole set renders in about 15 seconds) and a small hand-written filter kit (`tools/sounds/dsp.py`).

## Consequences

- This Mac's ffmpeg writes stereo Vorbis only, so every sound is stereo and Minecraft does not pan a stereo sound in the world. The beds are not positional anyway. A pod's engine does not pan until an encoder with mono is used; a recipe change and a rebuild fix that.
- A float result can differ by one unit in the last place between libm builds. The PCM hash rounds to 16 bits, so a flip needs a sample within 1e-16 of a rounding edge; if CI ever disagrees with a Mac, the fix is to quantise the recipes' last stage, not to loosen the hash.
- A new layer fails `SoundAmbienceTest` until it has a drone bed. `HymnMoments` plays the deepest hymn version in a layer with none of its own, so a breach is never silent.
