# Lampless figure concepts, round 1

The lampless figure walks the dark rails of the deep layers, and today it is a black zombie ([#250](https://github.com/pkeppeler/deepcharter/issues/250)). [Art direction section 10](art-direction.md#10-the-lampless-figure) decided what it is: a counterfeit of a miner, almost right and wrong in its proportions. This round gives you **four figures to pick from**. Each is a real model in the game, with its own texture and its own animations, and every picture below was shot in the game by the `figure-concepts` evidence scenario. The build of the one you pick comes in a later pull request.

The figure is a counterfeit in the old sense of the word: it copies the shape of a man and gets it wrong, and it makes nothing of its own. It is hollow, and it is not strong. That is why every option keeps the human parts (a coat, a hard hat, boots, two arms, two legs) and moves them: the wrongness is in how the parts join. Nothing on it is a real-world symbol of any kind, and there is no gore.

## What every option shares (already decided)

These come from section 10, so none of them is a choice.

1. **Wrong proportions.** Each is 43.7 to 44.0 pixels tall (2.7 blocks) and about 9 pixels across the body. A miner built from the same parts is 36 pixels tall (2.25 blocks) and 16 across at the shoulders. Each has joints where a miner has none, or none where a miner has one.
2. **Matte black.** Every texel of every texture is darker than 36 of 255, so the figure is black in the lamp beam too. There is no glowmask and no lit shading to speak of.
3. **An empty lamp bracket.** A square frame stands out from the front of the hard hat, with a bar above and below and a post at each side. Nothing is in it.
4. **No face.** The head is a plain black block. The sides of the head differ by less than 6 levels of brightness.
5. **Smeared edges.** Sixteen thin slivers hang past the hem of the coat and the sleeve ends, so no edge is clean.
6. **Look only.** The figure still walks, never attacks, and fades as before. Only how it is drawn changes.

## What differs

The options differ in **where the wrongness lives** and in **how the figure stands when it is idle**. They do not differ in colour: all four use one black.

| | Where the wrongness lives | Idle |
|---|---|---|
| **A. Candle** | A neck as long as the head is tall, so the helmet sits high. Shoulders set narrow: 8 pixels between the joints, where a miner's are 12. | Still and too upright. Only a drift of less than half a degree in the neck. |
| **B. Heron** | Knees that bend backward. A torso 7 pixels tall on legs 25 pixels long. | Leans forward from the hips as if listening, the head cocked and pushed out. |
| **C. Reacher** | Arms that reach the knees (20 pixels from the shoulder), with an extra joint in each forearm and in each shin, bent the wrong way. | The head tilted a quarter turn over to one side. The arms sway. |
| **D. Misfit** | Joints in the wrong places: the shoulders set in the middle of the chest, the elbows below the hips, the hips inside the torso, the knees high under them. | The head thrown back, looking at the ceiling, and the body swaying a little from the hips. |

Between any two options, the front silhouettes share at most 79 percent of their area and the side silhouettes at most 78 (the model test requires less than 85).

## The four, wide

The pod's lamp lights the near end of a sealed, dark chamber, and the figure stands where its reach thins out. A is top left, B top right, C bottom left and D bottom right. The view is from behind the pod, down the chamber.

![The four figures at the edge of the light, wide: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-concepts-wide-grid.png?raw=true)

## The four, closer

![The four figures, closer: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-concepts-close-grid.png?raw=true)

## The four, side-on

The side-on view is where a figure reads as a silhouette against the lit wall behind it. It is also where the idles differ most: A stands straight, B bows forward on bent-back knees, C stands straight with its head tipped (the tilt is toward the camera here, so it shows less), and D leans its head back.

![The four figures side-on: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-concepts-side-grid.png?raw=true)

## The four, beside a miner

A miner, the player, stands beside each figure for scale.

![A miner beside each of the four figures: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-concepts-scale-grid.png?raw=true)

## One by one

| | Idle | Walking | The idea |
|---|---|---|---|
| **A. Candle** | ![Candle idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-candle-idle.gif?raw=true) | ![Candle walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-candle-walk.gif?raw=true) | The quietest and the tallest-looking. A lamp post with a hard hat on top: a thin neck, a thin coat, narrow shoulders. It does nothing, and that is what is wrong with it. 18 bones, 36 cubes. |
| **B. Heron** | ![Heron idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-heron-idle.gif?raw=true) | ![Heron walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-heron-walk.gif?raw=true) | A man on a bird's legs. The knee is behind the hip and the ankle, and the torso is a short box on top. It leans toward you and listens. The walk lifts the shin forward, which no man's leg does. 18 bones, 36 cubes. |
| **C. Reacher** | ![Reacher idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-reacher-idle.gif?raw=true) | ![Reacher walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-reacher-walk.gif?raw=true) | The widest silhouette. Arms hang to the knees and kink twice, and the shins kink as well, so every limb has one joint more than it should. The head is cocked over. 22 bones, 40 cubes. |
| **D. Misfit** | ![Misfit idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-misfit-idle.gif?raw=true) | ![Misfit walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/{PR}/figure-misfit-walk.gif?raw=true) | The most like a puppet put together wrong. The arms hang from the middle of the chest, in front of the coat, and reach below the hips. The legs are two short thighs and two long shins. The head is thrown back. 18 bones, 36 cubes. |

The idle clips are the close view. The walking clips are shot in the dark with the camera on night vision, 10 blocks away, because a figure that is lit or approached fades: the figure walks across the chamber under its own AI.

## What each costs to build

All four share one build: a GeckoLib renderer in place of the zombie renderer, a fade (see-through, as the placeholder does it), the chosen model moved into Blockbench sources, and the other three models, the dev switch and the renderer of the placeholder deleted. The costs below are on top of that, in the work of one implementer.

| | Cubes and bones | Animation work | Risk |
|---|---|---|---|
| **A. Candle** | 36 cubes, 18 bones | Smallest. Two animated bones in the idle and nine in the walk. | The silhouette is closest to the Enderman (black, thin, about 2.7 blocks tall). The hard hat and the bracket are what set it apart, and from the front they read little. |
| **B. Heron** | 36 cubes, 18 bones | Medium. The reverse knee needs its foot plant checked by hand in the walk: the foot must lift forward and not drag. | The bowed idle needs head and neck tuned together. |
| **C. Reacher** | 40 cubes, 22 bones | The most. Four extra bones, two extra bend tracks in the walk, and arms that can pass through the legs. | The widest figure (up to 20 pixels from tip to tip): it needs a wider cull box and a wider margin in the narrowest rail rooms. |
| **D. Misfit** | 36 cubes, 18 bones | Medium. The hips sit inside the torso and the arms in front of the chest, so the cubes overlap and a hand can clip the coat as the figure walks. | The wrongness is the least readable from the front. Only the side view and the walk show it. |

Costs are my estimate from the models and animations of this round. Nothing was built to measure them.

## How to pick

Reply on [#250](https://github.com/pkeppeler/deepcharter/issues/250) or on the pull request in a few words. For example:

- "B, the Heron."
- "C, but with the head of A."
- "A, and put the arms of C on it."
- "None of these: more like X."

Then the build gives the one you pick its final animations, its fade and its place in the game.

## Try one in the game

```sh
JAVA_TOOL_OPTIONS=-Ddeepcharter.figureConcept=heron tools/play.sh
```

The four switches are `-Ddeepcharter.figureConcept=candle`, `-Ddeepcharter.figureConcept=heron`, `-Ddeepcharter.figureConcept=reacher` and `-Ddeepcharter.figureConcept=misfit`. Without the switch, the figure looks as it does today. A name that is none of the four fails at startup and lists the four. In the game, `/summon deepcharter:lampless_figure ~ ~ ~` puts one next to you (it fades when you stand within 8 blocks, as it does today), and the rails of layer 2 have them.

The files of an option are `geckolib/models/creature/figure/<id>.geo.json`, `textures/entity/creature/figure/<id>.png` and `geckolib/animations/creature/figure/<id>.animation.json`. A resource pack replaces any of them and F3+T applies it ([skins.md](skins.md)). `python3 -I tools/figure_concepts.py` writes them, and `--check` fails on a stale file.

## References

These are the works the options borrow from. We take principles only. No image, model or texture from any of them is in the repo. The works are named as published, and none of them was re-read for this round.

- **The uncanny valley.** Masahiro Mori, "Bukimi no tani" (*Energy* 7(4), 1970), in English by MacDorman and Kageki, "The Uncanny Valley" (*IEEE Robotics & Automation Magazine* 19(2), 2012). A thing that is nearly human with a few details wrong is more disturbing than a thing that is plainly not human, and motion sharpens it. *We take:* keep the human parts and move the joints, and give each option a walk that is almost a walk.
- **Slender-type silhouettes.** Slender Man (Eric Knudsen, as "Victor Surge", Something Awful, 2009) and *Slender: The Eight Pages* (Parsec Productions, 2012): a tall, thin figure with no face, seen at a distance and not moving. *We take:* no face, and stillness (A's idle). *We avoid:* a bare tall black stick. Minecraft's own Enderman is that already, so every option wears a hard hat and an empty bracket, and the three that are not A leave the stick behind in their shape.
- ***Pathologic* and *Pathologic 2*** (Ice-Pick Lodge, 2005 and 2019). People in town who are almost ordinary and wrong by a mask, a costume or a stiff theatrical gesture. *We take:* the wrongness is in the costume and the posture, not in a monster's body (B's lean, D's thrown-back head).
- ***Inside*** (Playdead, 2016). The mind-controlled workers: slack limbs, blank faces, and a gait that is a little off, seen in silhouette against dim light in fog. *We take:* a silhouette against a lit wall, and a walk that does not look like effort (C's arms swing too far).
- ***Darkwood*** (Acid Wizard Studio, 2017). The only way to see is the light, and what stands at the edge of the flashlight cone is half seen. *We take:* the figure belongs to the edge of the light, and the stills are shot there.
- **Mining photography.** Lewis Hine's photographs of boys in the coal breakers and mines (National Child Labor Committee, 1908 to 1912, Library of Congress) and Sebastião Salgado's Serra Pelada series (1986). The helmet with its lamp on a bracket, the long coat, the boots. *We take:* the shape the counterfeit copies, so that "almost a miner" reads at once. The bracket is what is missing.
- **Digitigrade legs.** The backward knee of a heron, a dog or a horse is its ankle, and the animal walks on its toes. *We take:* B's legs put an ankle where a knee should be.
- **Long arms.** A gibbon's arm reaches its ankle, and the elbow is high. *We take:* C's reach, with a second elbow.
- **Mannequins and lay figures.** A wooden artist's lay figure has a joint at every limb and none of a body. *We take:* D's misplaced joints.
- ***Motherload*** (XGen Studios, 2004). Almost nothing in it is a creature. The menace is the dark below, the fuel running down and the lava one block over. *We take:* sparseness. The figure is rare and does nothing, and the stills show one figure in a dark room, not a crowd.
- **Evil as a counterfeit.** Treebeard in *The Two Towers* (Tolkien, 1954): "Trolls are only counterfeits, made by the Enemy in the Great Darkness, in mockery of Ents, as Orcs were of Elves." Lewis, *Mere Christianity* book 2 chapter 2: "Badness is only spoiled goodness." *We take:* the figure is a copy of a miner that makes nothing and is not strong. This is why the wrongness is in the proportions and not in teeth or blood.
