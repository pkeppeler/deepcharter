---
status: accepted
---

# Pods are vehicles, not walkable interiors

Pods are vehicles with seats and no walkable interior while moving. The large chassis gets anchor mode instead, and outposts and pads give the presence at depth: the Cyclops-like mobile-base feel at much lower cost and risk.

[docs/research/walkable-pod-interior.md](../research/walkable-pod-interior.md) recommended a static interior with windows (about 6-12 weeks). We did not build it. Don't "fix" this by reading the research and assuming we did.

## Considered Options

- **Static interior with windows**: rejected for cost, not bugs. Its floor never moves, so it avoids multiplayer desync, but it's about 6–12 weeks of work plus rendering risk (Sodium/Iris/Vulkan) for a feel that outposts and pads already give.
- **Create-style contraption**, **actually moving the blocks**, **Sable or Valkyrien Skies**: rejected. Each carries players on a moving floor, which has documented multiplayer fall-through and desync bugs; none is usable on Fabric 26.3.
