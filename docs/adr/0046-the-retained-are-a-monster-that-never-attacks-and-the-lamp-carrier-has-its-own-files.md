---
status: accepted
---

# The Retained are a monster that never attacks, and the lamp-carrier has its own files

Builds on [ADR 0044](0044-the-lampless-figure-is-a-geckolib-model-and-the-reacher.md), which drew the lampless figure, and keeps [ADR 0031](0031-the-finale-is-won-by-renunciation.md): the boss is the Chairman. Replaces the placeholder behaviour of #83. Issue #449. The user's call (pkeppeler, 2026-10-11): the Retained are "a very good example of a normal enemy mob", "a scary mob that you definitely want to avoid", never a boss and never an attack; "them grabbing and draining a pod, its gas, but not attacking is good"; "in caverns and shafts (or even creepier, if they had a small glowing lamp so you could see them from a distance in the dark)". The boss stays the Chairman.

## Decision

- **The Retained are a `MobCategory.MONSTER` entity that has no attack.** The game's own spawner places them, from the six biome files of layers 1 and 2 (`spawns_by_category.monster`, weight 1, count 1), so the surface biomes list none and a pack or a later layer retunes the spawns as data. The rules a spawn must pass are the entity's (`LamplessFigure.checkSpawnRules`, `LamplessFigures.canAppearAt`): layer 1 or 2, block light under the light at which a figure fades, a solid floor with room to stand, a roll that is rarer in layer 1, and room in the level (3 of the game's own figures at most, a delay after a fade). The rail line of layer 2 keeps its own spawner, with a cap and a delay of its own, so that the figure of Prospector's Run (fixed lore) is never held back by the caverns' figures. The colony, the hangar and the founding Mole's bay are in the surface level; an outpost is lit.
- **A dark pod with a pilot draws a figure that grabs the hull and drains the tank.** Dark means no lights part that counts, or no power ([`PodLights.isLit`](../../src/main/java/io/github/pkeppeler/deepcharter/pod/PodLights.java)). The numbers and the counterplay are in [mechanics.md](../design/mechanics.md#the-retained-at-dark-pods-449-a); they are tuning and may change without a new decision.
- **A figure that carries a lamp has a model, a texture and a glowmask of its own**, `creature/lampless_figure_lamp` and `entity/lampless_figure_lamp` with `_glowmask`, beside the three files of the lampless figure, which are unchanged. The lamp is one bone, `lantern`. The glass is dark in the texture and lit in the glowmask, drawn at full bright, so it works with vanilla lighting. A pack replaces the three lamp files to change how the lamp is carried; the shipped way is a lamp on a pole, and the lantern in the hand and the lamp in the ribcage are the test packs `retained_lamp_hand` and `retained_lamp_ribs`.
- **On foot, a person near a figure still makes it fade; a pilot does not.** A pilot in a pod is what draws it. A figure that has hold of a pod ignores people on foot: only a light, the pod moving (the rotor, the treads or the drill), a full belly or an empty tank lets go.

## Considered Options

- **Keep `MobCategory.MISC` and spawn by our own tick.** Rejected: it is Java where the platform has data, and a pack could not retune it. The rail line keeps its own tick, because a site is not a biome.
- **One model with the lantern bone hidden on a figure without a lamp.** Built first and rejected. The Reacher is the user's pick: a lamp-carrier whose arm is held out to carry the lantern changes the shape of every figure, and the lampless files would no longer be what the user chose. Two sets of files keep the lampless figure byte for byte as it was.
- **A dynamic light that moves with the lamp-carrier.** Not built: it needs a light block that follows the figure and a ledger to clean up, as a pod's lights have ([ADR 0024](0024-pod-light-blocks-are-recorded-in-a-ledger-before-they-are-placed.md)), and the glowmask already shows from afar. It is a follow-up.
- **A lamp toggle, so that a pilot can run dark.** Not built: it needs an input, a packet and a cost, and the lights part already is the choice. A follow-up.

## Consequences

- On Peaceful difficulty the game's spawner makes no monsters, so a Peaceful world has only the rail line's figure and a player there does not meet the Retained in the caverns.
- A Retained that is never freed takes 2.5 litres, a quarter of a stock tank, and never takes the tank under 5 per cent, so it cannot strand a pod by itself.
- The lamp-carrier's three file ids are a contract with skins, as the lampless figure's are: a pack keeps the bone names the animations use, and the lamp model has `lantern` as well.
- The cull box of the figure is 2 blocks to each side (it was 1.5), because the cling pose reaches out.
- An evidence scenario that walks a figure to a pilot in a dark pod now sees a grab, not a fade.
