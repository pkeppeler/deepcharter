---
status: accepted
---

# Build our own Employee Handbook

We build the Employee Handbook ourselves: hidden vanilla advancements detect events, and we write our own charter-shared state and our own screen. The look is the product, and charter-shared state is native in our design. Research: [research/handbook-build-vs-reuse.md](../research/handbook-build-vs-reuse.md).

## Considered Options

- **FTB Quests**: rejected. FTB's policy forbids it in packs outside FTB and CurseForge, so it can't go in a Modrinth pack, and it has no 26.3 build.
- **Odyssey (Heracles), Better Questing**: rejected. No Fabric 26.3 build.
- **Patchouli, Lavender, GuideME**: rejected. No Fabric 26.3 build.
- **Modonomicon**: rejected, but the runner-up. It is the only Fabric 26.3 book mod. Its page layout and buttons are fixed, its state is per player only (charter sharing would need a workaround), and its API changes daily. It would save only about 2-4 weeks, and only if a prototype passed.
