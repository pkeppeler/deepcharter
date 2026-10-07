---
status: accepted
---

# Depth is a chain of layer dimensions

Each layer is its own dimension, of moderate and variable height, chained floor to top. The depth readout is calculated, and the world has no horizontal border. Generation cost grows with column height, not world width, and per-dimension lighting, fog and sky give each layer its own atmosphere.

## Considered Options

- **Cubic Chunks on 1.12.2**: rejected. There is no production-grade cubic-chunks option on modern versions.
- **One 4064-tall dimension**: rejected. Vanilla dimensions are capped at 4064 blocks, which caps depth, and tall columns are expensive to generate.
- **Seamless layers with no boundary**: rejected. Impossible past 4064 blocks.

## Consequences

- Crossing a layer is a teleport, hidden behind the breach event.
- Story layers splice into existing worlds because layers are links in a chain.
