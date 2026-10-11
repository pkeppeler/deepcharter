---
status: accepted
---

# The Retained are a monster that never attacks, and the lamp-carrier has its own files

Builds on [ADR 0044](0044-the-lampless-figure-is-a-geckolib-model-and-the-reacher.md), which drew the lampless figure, and keeps [ADR 0031](0031-the-finale-is-won-by-renunciation.md): the boss is the Chairman. Replaces the placeholder behaviour of #83. Issue #449. Amended by issue #485: the Retained are killed, and do not fade (see [Amendment](#amendment-485-the-retained-are-killed-and-do-not-fade)). The user's call (pkeppeler, 2026-10-11): the Retained are "a very good example of a normal enemy mob", "a scary mob that you definitely want to avoid", never a boss and never an attack; "them grabbing and draining a pod, its gas, but not attacking is good"; "in caverns and shafts (or even creepier, if they had a small glowing lamp so you could see them from a distance in the dark)". The boss stays the Chairman.

## Decision

- **The Retained are a `MobCategory.MONSTER` entity that has no attack.** The game's own spawner places them, from the six biome files of layers 1 and 2 (`spawns_by_category.monster`, weight 1, count 1), so the surface biomes list none and a pack or a later layer retunes the spawns as data. The rules a spawn must pass are the entity's (`LamplessFigure.checkSpawnRules`, `LamplessFigures.canAppearAt`): layer 1 or 2, block light under the light at which a place counts as lit, a solid floor with room to stand, a roll that is rarer in layer 1, and room in the level (3 of the game's own figures at most, a delay after a kill). The rail line of layer 2 keeps its own spawner, with a cap and a delay of its own, so that the figure of Prospector's Run (fixed lore) is never held back by the caverns' figures. The colony, the hangar and the founding Mole's bay are in the surface level; an outpost is lit.
- **A dark pod with a pilot draws a figure that grabs the hull and drains the tank.** Dark means no lights part that counts, or no power ([`PodLights.isLit`](../../src/main/java/io/github/pkeppeler/deepcharter/pod/PodLights.java)). The numbers and the counterplay are in [mechanics.md](../design/mechanics.md#the-retained-at-dark-pods-449-a); they are tuning and may change without a new decision.
- **A figure that carries a lamp has a model, a texture and a glowmask of its own**, `creature/lampless_figure_lamp` and `entity/lampless_figure_lamp` with `_glowmask`, beside the three files of the lampless figure, which are unchanged. The lamp is one bone, `lantern`. The glass is dark in the texture and lit in the glowmask, drawn at full bright, so it works with vanilla lighting. A pack replaces the three lamp files to change how the lamp is carried; the shipped way is a lamp on a pole, and the lantern in the hand and the lamp in the ribcage are the test packs `retained_lamp_hand` and `retained_lamp_ribs`.
- **A figure is killed, and nothing else ends a grip.** Light and people on foot do not fade it (it did before #485). See the amendment below.

## Considered Options

- **Keep `MobCategory.MISC` and spawn by our own tick.** Rejected: it is Java where the platform has data, and a pack could not retune it. The rail line keeps its own tick, because a site is not a biome.
- **One model with the lantern bone hidden on a figure without a lamp.** Built first and rejected. The Reacher is the user's pick: a lamp-carrier whose arm is held out to carry the lantern changes the shape of every figure, and the lampless files would no longer be what the user chose. Two sets of files keep the lampless figure byte for byte as it was.
- **A dynamic light that moves with the lamp-carrier.** Not built: it needs a light block that follows the figure and a ledger to clean up, as a pod's lights have ([ADR 0024](0024-pod-light-blocks-are-recorded-in-a-ledger-before-they-are-placed.md)), and the glowmask already shows from afar. It is a follow-up.
- **A lamp toggle, so that a pilot can run dark.** Not built: it needs an input, a packet and a cost, and the lights part already is the choice. A follow-up.

## Consequences

- On Peaceful difficulty the game's spawner makes no monsters, so a Peaceful world has only the rail line's figure and a player there does not meet the Retained in the caverns.
- A Retained that is never killed takes the tank down to 5 per cent and holds on there, so it cannot strand a pod by itself. A stock run that suffers one nobody answers is lost; one that is answered by drilling costs about 5 per cent of a layer 1 run.
- The lamp-carrier's three file ids are a contract with skins, as the lampless figure's are: a pack keeps the bone names the animations use, and the lamp model has `lantern` as well.
- The cull box of the figure is 2 blocks to each side (it was 1.5), because the cling pose reaches out.
- An evidence scenario that walks a figure to a pilot in a dark pod now sees a grab, not a fade.
- A figure no longer leaves of itself, so the cap of 3 in a level holds until they are killed or the game's usual despawn takes a natural one far from every player.

## Amendment (#485): the Retained are killed and do not fade

The user's call (pkeppeler, 2026-10-11, the M2 demo): the Retained must be killed; otherwise they keep draining the pod's fuel; they do not fade on approach or when lit. Lore stays true: they never attack. The minimum that makes it true, while the creatures and combat session (#13) may rework combat later:

- **No fade.** The fade on light and on a person near is removed, with its tunables (`approachBlocks`, `fadeTicks`). A grab, and its drain, end only when that figure dies, or the pod is gone (destroyed or removed). The fill (2.5 litres) is removed; the drain stops at the floor (5 per cent) and the figure holds on. A pilot who leaves the seat does not end the grip: the figure is killed from the ground. A figure is not saved, so a grip also ends when the figure's chunk goes.
- **It has health, 12, and two things hurt it.** The figure is invulnerable to the world (a fall, fire, an explosion) and still dies to `/kill`; it takes damage from a player (a blow, an arrow) and from a pod's cutter, which bites a clinging figure for 2 each 10 ticks while the pod drills (the first bite as the drill starts). `isInvulnerableTo` says so from the damage source: a player as the cause, or a pod as the direct entity.
- **Where the means live.** In the creature feature (`LamplessFigure`), not in the pod: the pod only says `drilling()`. This keeps the pod free of the creatures and leaves the combat to #13.
- **Lights keep their place as prevention.** A lit pod still draws no figure, and a light at a spot still keeps one from appearing there. A light no longer frees a held pod.
- **Death is a dissolve, not a fall.** It gives a sound, ash and smoke, the pilot's line, and 20 ticks of going see-through where it stands (the render fade of the old fade is reused for it). No gore.
- **The respawn delay counts from a kill** (it counted from a fade).

Considered and not built: a fill that ends the grip (the rule withdrawn); a ram from the seat (a new input, and the cutter does the same with no aim); a weapon item of the mod's own (the creatures session). The numbers are in [mechanics.md](../design/mechanics.md#the-retained-at-dark-pods-449-a). This makes the default harder than before for a pilot who ignores a grab (the whole run, against a quarter), by the user's decision.
