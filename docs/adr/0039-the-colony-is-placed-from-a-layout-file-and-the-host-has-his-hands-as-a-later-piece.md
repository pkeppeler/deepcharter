---
status: accepted
---

# The colony is placed from a layout file, on an 80-block pad, and the Host has his hands as a later piece

Carries out the colony part of [ADR 0030](0030-art-direction-decisions.md) and amends [ADR 0016](0016-the-colony-is-built-once-in-code-and-the-conduit-is-set-when-its-chunk-loads.md) (the colony was a table of offsets in code). Issue #244; the user picked layout A of [colony-concepts-2.md](../design/colony-concepts-2.md) and the Host at 10 blocks (2026-10-09).

## Decision

- **The town is structure files and a layout file in the mod's own data.** `tools/colony/town.py` is the source: it writes `data/deepcharter/structure/colony/*.nbt` and `data/deepcharter/colony/layout.json` (`tools/colony/build.py`, checked by `build.py --check` and the Python tests). `ColonyBuilder` still runs once at `SERVER_STARTED` and still flattens the pad and records it first, then places the layout's pieces in order and the terminals on their plinths. The anchors (`ColonyAnchor`) are offsets in the layout, so a data pack that moves a building moves its anchor. Every offset is from the pad's centre, at ground level, X east and Z south.
- **The pad is 80 x 80 and cleared 36 blocks high** (it was 64 and 24): the town needs the hangar 30 blocks west, the hoist house 36 north, the bunkhouse 37 east, and the headframe's beacon 30 up. A test ties the Python and Java numbers.
- **The Host's hands are a piece of their own that waits.** His body is one block display of the sculpture block and his hands another, the same size and place. The layout lists the hands as a `later` piece; the first work order places it (`FounderStatue.restoreHands`), replacing a pair already there. Before, the hands were two copper slabs at fixed offsets.
- **A rebuild of an interrupted build takes the block displays of the pad away first.** Displays are entities, found only in chunks that are accessible, so a rebuild in chunks that are not yet ticking can leave the first try's displays beside the new ones. The case is a crash within about a second of the first start; the build does not wait for the chunks.
- **A door is an opening, not a block.** A player walks through a doorway of two blocks, between red jambs under a hazard lintel; a pod's bay is 4 wide and 4 high. `winder_door` and `shutter` are deleted. Mine track has no collision, and an ore car stands on its own length of track.
- **A building's door faces the square or a street, and has a clear line of sight out.** The Lamp and Pick, which faced a blank wall in the old colony, now faces the square across the south street. A test fails for a door with a block in its line.

## Considered Options

- **Keep the pad at 64 and shrink the town.** Rejected: the user picked layout A at the size it was drawn; the hoist house alone is 36 blocks from the square.
- **Place the hands with the body and take them away on a rebuild.** Rejected: a world that has had its hands restored would lose them on a rebuild. The later piece keeps one rule for both.
- **Keep the old buildings as they were and add layout A beside them.** Rejected: the old ruins did not share its scale or language, and the user wants one town.
- **A doorway with a closed door that opens.** Rejected for now: nothing opens it, and a decorative closed door in the way of the spawn is worse than none.

## Consequences

- Old worlds keep the old colony (the pad is built once). A new world gets the town.
- The kit holds only what the town uses: the blocks, sculpture pieces, signs and textures that only layouts B, C and D or the other Host sizes used are deleted, and a test keeps it so.
- The layout and the structure files are data a pack can replace. The reload path (a dev command that rebuilds the colony) is not built yet.
- A Minecraft bump re-checks every `.nbt`; `ColonyPlacementTest` places the files and compares every block with the world.
