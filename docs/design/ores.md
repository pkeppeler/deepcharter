# The ore look

Issue [#367](https://github.com/pkeppeler/deepcharter/issues/367). It is your pick from round 2 ([texture-density-2.md](texture-density-2.md)):

> For ores, do a mix of B1 and B3, and B4. Maybe some are combined versions of these, or some are B1 or some are B3, or some are B4, etc.

Every ore is the host stone with our ore art over it. The art is one of three families, or a blend of two:

- **B1, clumps:** domed nuggets or faceted crystals, each with a dark rim, a lit side and a glint.
- **B3, threads:** a level thread of ore that runs off the sides of the block, with a swell or two.
- **B4, sockets:** clumps sunk in a soft, dark hollow.

## Which ore looks like what

The seven ores form a ladder. The cheap ores are clumps, the middle ores are threads, and the dear ores sit in sockets. Each ore also has its own shape and colour, so that the ores of one layer tell apart at a glance. All seven are in layer 1; the layers below hold the same ones, so the table must work for all of them at once.

| Ore | Look | Why |
|---|---|---|
| ironium | B1 | The cheapest ore: many (4) small brown chunks with a hair-thin vein. Plain clumps, with no thread and no socket, to be the baseline. |
| bronzium | B1 | Two fat, orange domes. It is B1 like ironium, but few and large, so a pair of orange domes does not read as a scatter of brown chips. |
| silverium | B1 + B3 | The top of the clump tier. A thin level thread with two pale crystals on it. The thread is the first step toward the thread tier. |
| goldium | B3 + B1 | A thread that swells into one big, bright nugget. A thread with a nugget on it is what you pan for. The nugget is the largest clump of any ore. |
| platinium | B3 | Two level, thick, lilac-white bars. It is the plain thread: no clump on it. Two thick bars read differently from silverium's one thin line. |
| einsteinium | B4 | Two teal crystals, each in a dark hollow. Sockets are for the dear ores, so a block of it looks set in the rock. |
| cicatrium | B4 + B3 | A dark red hairline that runs into a hollow, with a red clump in it, like a scar with a wound. It is the one blend of the other two dear looks. |

How the table keeps the rules:

- **One tier, one resemblance.** Ironium, bronzium and silverium are clumps. Goldium and platinium are threads. Einsteinium and cicatrium have sockets. A blend sits at the edge of its tier and borrows from the next, as silverium and goldium do, so the ladder is a slope and not three steps.
- **Neighbours differ.** In one layer, no two ores share both the family and the silhouette. Ironium (many chips) and bronzium (two domes) are both B1, and they differ in count, size and colour. Silverium (one thin line, pale crystals) and platinium (two thick bars) are both pale, and they differ in the line. Goldium and cicatrium are both a thread with one clump, and they differ in colour, and in the hollow round cicatrium's clump.
- **Every family is used,** and three ores are blends (silverium, goldium, cicatrium).

The pick is in [tools/textures/recipes/ores.json](../../tools/textures/recipes/ores.json): a B1 look is a `cluster` layer, a B3 look a `seams` layer, a B4 look a `cluster` with a `socket`, and a blend has two layers. Each ore has 4 textures, and the world picks one for each block at random, as vanilla does with its stone. `tools/tests/test_texture_packs.py` fails if this table and the recipes differ.

## The rules from round 2 hold

- **Overlays on the real stone.** Each ore block draws `minecraft:block/stone` by name, and the ore art over it as a cutout layer. The stone shows through everywhere else.
- **No vanilla texture is overridden.** The mod ships no file in `assets/minecraft/` for a block or an item. The one vanilla file it ships is the sky's sun (ADR 0030), and a test pins that.
- **No glow.** A lampless ore is as dark as its stone.
- **Edges blend into plain stone.** Only a thread or a vein of the ore reaches the edge of the block. A test fails on any other pixel on the edge.
- **Everything is generated.** The textures, the blockstates and the models come from `tools/textures/texgen.py`, and `--check` fails on a difference. The list of ore blocks and the host are in [tools/textures/overlays.json](../../tools/textures/overlays.json).

When layer rock gets blocks of its own (#241), an ore names that block's texture as its host in `overlays.json`, and its edge follows it.

## What changed

The B2 option (flecks) and the four round-2 test packs are gone, and with them their scenario. The mod's old ore textures, which were a whole tile of rock with ore on it, are replaced by the overlays. [texture-density-2.md](texture-density-2.md) stays as the record of the pick.
