# Lampless figure concepts, round 1

The lampless figure walks the dark rails of the deep layers, and today it is a black zombie ([#250](https://github.com/pkeppeler/deepcharter/issues/250)). [Art direction section 10](art-direction.md#10-the-lampless-figure) decided what it is: a counterfeit of a miner, almost right and wrong in its proportions. This round gives you **four figures to pick from**. Each is a real model in the game, with its own texture and its own animations, and every picture below was shot in the game by the `figure-concepts` evidence scenario, in a sealed chamber of layer-1 rock with a pod's lamp. The build of the one you pick comes in a later pull request.

The figure is a counterfeit in the old sense of the word: it copies the shape of a man and gets it wrong, and it makes nothing of its own. It is hollow, and it is not strong. That is why every option keeps the human parts (a coat, a hard hat, boots, two arms, two legs) and moves them: the wrongness is in how the parts join. Nothing on it is a real-world symbol of any kind, and there is no gore.

## What every option shares (already decided)

These come from section 10, so none of them is a choice.

1. **Wrong proportions.** Each is 44 pixels tall (2.75 blocks) and 9 to 10 pixels across the body. A miner built from the same parts is 36 pixels tall (2.25 blocks) and 16 across at the shoulders. Each has joints where a miner has none, or none where a miner has one.
2. **Matte black.** Every texel of every texture is darker than 36 of 255, so the figure is black in the lamp beam too. There is no glowmask and no lit shading to speak of.
3. **A hard hat with an empty lamp bracket.** The hat is a miner's: a domed shell with a ridge along the top, a brim all round and a peak at the front, and the dome is narrower than the brim so it is not a stovepipe. A frame 4 pixels square stands 2 pixels off the front of the dome on two stays, with a bar above and below and a post at each side, and a hole where the lamp would sit. Nothing is in it. The frame is a little lighter than the hat (still black), so that it shows in the lamp light.
4. **No face.** The head is a plain black block. The sides of the head differ by less than 6 levels of brightness.
5. **Smeared edges.** Sixteen thin slivers hang past the hem of the coat and the sleeve ends, so no edge is clean.
6. **Look only.** The figure still walks, never attacks, and fades as before. Only how it is drawn changes.

## What differs

The options differ in **where the wrongness lives** and in **how the figure stands when it is idle**. They do not differ in colour: all four use one black.

| | Where the wrongness lives | What differs, and in which view | Idle |
|---|---|---|---|
| **A. Candle** | A neck as long as the head is tall, so the hat sits high. Shoulders set narrow: 7 pixels between the joints, where a miner's are 12. | Side-on and front: a thin column with a hat on a stalk. The only option with legs and arms pressed together. | Still and too upright. Only a drift of less than half a degree in the neck. |
| **B. Heron** | Knees that bend backward. A torso 7 pixels tall on legs 25 pixels long. | Side-on: backward knees. Front: stilts, with a gap of 4 pixels between the legs under a narrow torso. | Leans forward from the hips as if listening, the head cocked and pushed out. |
| **C. Reacher** | Arms that reach the knees, with an extra joint in each forearm and in each shin, bent the wrong way. A stoop of 16 degrees. | Front: arms that kink out and in down to the knees. Side-on: bent forward, so it does not stand like A or B. | The head tilted a quarter turn over to one side. The arms sway. |
| **D. Misfit** | Joints in the wrong places: the shoulders set in the middle of a broad coat, the elbows below the hips and bowed out, the hips inside the torso, the knees high under them. | Front: a broad coat with the arms bowed out from mid-chest like parentheses. Side-on: the arms hang in front of the coat. | The head thrown back, looking at the ceiling, and the body swaying a little from the hips. |

Between any two options, the front silhouettes share at most 66 percent of their area (Reacher and Misfit) and the side silhouettes at most 61 (Candle and Heron). The model test requires less than 70. The silhouette that separates the four best is the side-on one, so it comes first below.

## The four, side-on, in the rock

The side-on view separates the four best. The camera stands across the chamber, the pod's lamp lights the rock wall behind the figure, and the figure is a silhouette against it. A is top left, B top right, C bottom left and D bottom right in every picture. A stands straight with a thin neck, B bows forward on backward knees, C stoops with its arms kinked, and D hangs its arms in front of a broad coat with the head thrown back.

![The four figures side-on against the lit rock: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-concepts-rock-lit-grid.png?raw=true)

## The four, from the front

From the far end of the chamber, with the lamp-lit wall of the pod behind the figure. This is where B and D are told apart: B is a narrow torso on stilts, D is a broad coat with arms bowed out.

![The four figures from the front: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-concepts-front-grid.png?raw=true)

## In the rock, at the edge of the light

This is the place and the lighting where the figure is met. The scene is a chamber of layer-1 rock (the stone the layers are made of), with layer 1's fog and ambient light, a Prospector with its lights fitted, and the figure where the lamp's reach runs out (block light 3 at its feet). The camera stands behind the pod. Nothing is lit behind the figure.

![The four figures at the edge of the lamp's light, in the rock: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-concepts-rock-grid.png?raw=true)

What the pictures show, honestly: **each figure is visible, but only as a small dark shape.** Layer-1 rock is not black, so a black figure stands out against it at 8 blocks, with about 2 blocks of the lamp's reach left. But at that size the options differ only a little. A shows a narrow column with a hat, B shows two thin legs with a gap between them, C shows arms reaching the knees, and D shows long arms bowed out from a broad coat. The first meeting is a dark shape that is understood only after, and the side-on and front views above are where the options read. The pictures do not show a figure beyond the lamp's reach (block light 0): that case was not recorded.

## The head, in the lamp light

A hard hat, a ridge, a brim all round and a peak at the front, and the frame of the empty bracket, a block off and a little above, with the pod's light close by. The figure stays black in the light.

![The heads of the four figures in the lamp light: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-concepts-head-grid.png?raw=true)

## The four, beside a miner

A miner, the player, stands beside each figure for scale, side-on across the chamber.

![A miner beside each of the four figures: A, B, C and D](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-concepts-scale-grid.png?raw=true)

## One by one

| | Idle | Walking | The idea |
|---|---|---|---|
| **A. Candle** | ![Candle idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-candle-idle.gif?raw=true) | ![Candle walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-candle-walk.gif?raw=true) | The quietest and the tallest-looking. A lamp post with a hard hat on top: a thin neck, a thin coat, narrow shoulders, arms and legs pressed in. It does nothing, and that is what is wrong with it. 18 bones, 42 cubes. |
| **B. Heron** | ![Heron idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-heron-idle.gif?raw=true) | ![Heron walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-heron-walk.gif?raw=true) | A man on a bird's legs. The knee is behind the hip and the ankle, the legs stand apart like stilts, and the torso is a short narrow box on top. It leans toward you and listens. The walk lifts the shin forward, which no man's leg does. 18 bones, 42 cubes. |
| **C. Reacher** | ![Reacher idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-reacher-idle.gif?raw=true) | ![Reacher walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-reacher-walk.gif?raw=true) | The stooped one. Arms hang to the knees and kink twice, and the shins kink as well, so every limb has one joint more than it should, and the whole body is bent forward. The head is cocked over. 22 bones, 46 cubes. |
| **D. Misfit** | ![Misfit idle](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-misfit-idle.gif?raw=true) | ![Misfit walking](https://github.com/pkeppeler/deepcharter/blob/pr-media/407/figure-misfit-walk.gif?raw=true) | The most like a puppet put together wrong. A broad coat, and arms that hang from the middle of the chest, bow out from it and reach below the hips. The legs are two short thighs and two long shins. The head is thrown back. 18 bones, 42 cubes. |

The idle clips are the front view. The walking clips are shot in the dark with the camera on night vision, 10 blocks away, because a figure that is lit or approached fades: the figure walks across the chamber under its own AI.

## What each costs to build

All four share one build: a GeckoLib renderer in place of the zombie renderer, a fade (see-through, as the placeholder does it), the chosen model moved into Blockbench sources, and the other three models, the dev switch and the renderer of the placeholder deleted. The costs below are on top of that, in the work of one implementer.

| | Cubes and bones | Animation work | Risk |
|---|---|---|---|
| **A. Candle** | 42 cubes, 18 bones | Smallest. Two animated bones in the idle and nine in the walk. | The silhouette is closest to the Enderman (black, thin, 2.75 blocks tall). The hard hat and the bracket are what set it apart, and at a distance they read little: the hat is a small bump. |
| **B. Heron** | 42 cubes, 18 bones | Medium. The reverse knee needs its foot plant checked by hand in the walk: the foot must lift forward and not drag. | The bowed idle needs head and neck tuned together. |
| **C. Reacher** | 46 cubes, 22 bones | The most. Four extra bones, two extra bend tracks in the walk, and arms that can pass through the legs. | Up to 20 pixels from tip to tip, and stooped: it needs a wider cull box and a wider margin in the narrowest rail rooms. |
| **D. Misfit** | 42 cubes, 18 bones | Medium. The hips sit inside the torso and the arms start in front of the chest, so the cubes overlap and a hand can clip the coat as the figure walks. | The widest figure, 24 pixels from hand to hand, which is the edge of the cull box and of the narrowest rail rooms. |

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
