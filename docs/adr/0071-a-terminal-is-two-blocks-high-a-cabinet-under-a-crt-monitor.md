---
status: accepted
---

# A terminal is two blocks high: a cabinet under a CRT monitor

Every terminal is a door-like double block: the lower half is the Company cabinet and holds the block entity, the upper half is the CRT monitor with the terminal's glyph. Terminals stand on the ground; the colony has no plinths.

## Context

The first model hung a small monitor on a one-block cabinet standing on a plinth. In the stills the monitors read as small and the plinths as odd (#857).

## Decision

- `TerminalBlock` has `DOUBLE_BLOCK_HALF`. A player places both halves or neither (no room above: no placement). Code places both through `TerminalBlock.place`.
- The block entity is the lower half's. The upper half resolves to it (`TerminalBlock.lowerHalf`, used by `Terminals.open`), so a click on either half opens the terminal.
- Both halves keep `active` and `facing`, so the glow follows the repair on both.
- The upper half's front is the existing 16 px front texture mapped one to one onto a full block face: no scaling, so no glyph is stretched.
- Breaking is a door's: a half without its other half goes (`updateShape`), and creative breaking the upper half takes the lower silently. Terminals have no loot table and cannot be broken in survival.

## Rejected

- One tall model in one block: elements past 16 px would be clipped by chunk meshing, and the shape could not follow.
- Scaling the front texture 2x: it blurs the glyphs the user chose.
