#!/usr/bin/env python3
"""Measures how much a rendered still of a flat wall repeats at the size of a block (docs/design/layer-concepts-2.md, #447).

Usage: tools/layer_concepts/grid.py <screenshots directory>

The seam check of seams.py reads a texture, and cannot see a grid that a model or a motif makes: relief that starts and stops at a block edge, a
slot between two blocks, one mottle repeated on every block. Those show only in the render. In the `wide-wall` still the camera looks square at a flat
wall, so a block is the same number of pixels everywhere on it; in the `vanilla-reference` still the layer's rock stands beside vanilla stone and
deepslate under the same light, at the same scale. For a patch of wall the score is the height of the peak of its autocorrelation at the block
size, over the autocorrelation at the lags round it, along X and along Y, after a high-pass filter that removes the light's gradient: a thin line,
or one motif, that comes back every block makes a peak, and noise does not. Each rock patch must score no more than the vanilla deepslate patch in the
reference still of the same option and layer, plus MARGIN. Layer 3 is reported and not judged: its columns are one block wide, so their open joints
come back at every block by design, and the number says how much.

The scale is measured from the stills: the three panels of the reference still are 7 blocks wide each, 31.1 pixels a block at its distance of 9.6
blocks, so 30.2 pixels a block at the 9.9 blocks of the wide wall. Standard library only.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "textures"))

import pngio  # noqa: E402

# Pixels a block covers on the wall, at the distance of each still, and the rectangles (x0, y0, x1, y1) of the wall to measure, a margin inside
# its edges: three patches along the wide wall, and the three panels of the reference still.
WALL = {"pixels": 30.2, "patches": ((110, 80, 302, 330), (331, 80, 523, 330), (552, 80, 744, 330))}
REFERENCE = {"pixels": 31.1, "rock": (115, 80, 308, 330), "stone": (333, 80, 525, 330), "deepslate": (551, 80, 743, 330)}
# The layer whose formation is columns one block wide, which is reported and not judged.
COLUMNS_LAYER = 3
# How far above the score of the vanilla deepslate patch in the same still a rock patch may score. Deepslate, the more regular of vanilla's two, is the
# limit and not stone, which has no pattern to speak of: a 16 pixel texture that repeats with a strong, fixed pattern scores 0.6 and passes the eye of
# every Minecraft player. Measured on the stills of this round, vanilla stone scores 0.30 to 0.32 and deepslate 0.61 to 0.62.
MARGIN = 0.1


def luminance(image: pngio.Rgba) -> list[list[float]]:
    return [[sum(image.pixels[4 * (y * image.width + x):4 * (y * image.width + x) + 3]) / 3 for x in range(image.width)] for y in range(image.height)]


def gradient_energy(lum: list[list[float]], rect: tuple[int, int, int, int], axis: int) -> list[float]:
    """For each column (axis 0) or row (axis 1) of the patch, the mean size of the step to the next pixel along that axis: how much happens at
    that place, whatever it is. A slot or a lip between two blocks is a column of large steps; a mottle is steps at no particular column."""
    x0, y0, x1, y1 = rect
    if axis == 0:
        return [sum(abs(lum[y][x + 1] - lum[y][x]) for y in range(y0, y1)) / (y1 - y0) for x in range(x0, x1)]
    return [sum(abs(lum[y + 1][x] - lum[y][x]) for x in range(x0, x1)) / (x1 - x0) for y in range(y0, y1)]


BINS = 10
# The scale is searched this far either side of the measured pixels a block, in steps: a seam that comes back every block drifts off a pitch that is a
# little wrong by the end of the wall, and noise gets the same search, so a score is never better for the search.
SEARCH = (0.6, 0.1)


def comb(energy: list[float], pixels: float) -> float:
    """How much of the energy of a line of the patch falls at one place in the block: the energy is folded on the block size into BINS places, and the
    score is the largest place over the mean of the places, less 1. Steps that fall anywhere in a block give a flat fold, near 0; a seam gives a peak."""
    best = 0.0
    steps = int(SEARCH[0] / SEARCH[1])
    for k in range(-steps, steps + 1):
        pitch = pixels + k * SEARCH[1]
        folded, counts = [0.0] * BINS, [0] * BINS
        for i, e in enumerate(energy):
            place = int((i % pitch) / pitch * BINS)
            folded[place] += e
            counts[place] += 1
        means = [f / c for f, c in zip(folded, counts) if c]
        best = max(best, max(means) / (sum(means) / len(means)) - 1)
    return best


def score(lum: list[list[float]], rect: tuple[int, int, int, int], pixels: float) -> float:
    """The larger of the comb score along X and along Y."""
    return max(comb(gradient_energy(lum, rect, axis), pixels) for axis in (0, 1))


def read(path: Path) -> list[list[float]]:
    return luminance(pngio.decode(path.read_bytes(), path.name))


def wall_score(still: list[list[float]]) -> float:
    """The mean score of three patches of the wall, each as wide and as tall as a panel of the reference still, so that a patch is as long a stretch of
    wall as the vanilla stone it is compared with: a longer one has more blocks to repeat in, and scores higher for the same texture."""
    return sum(score(still, patch, WALL["pixels"]) for patch in WALL["patches"]) / len(WALL["patches"])


def reference_scores(still: list[list[float]]) -> dict[str, float]:
    return {name: score(still, REFERENCE[name], REFERENCE["pixels"]) for name in ("rock", "stone", "deepslate")}


def main() -> int:
    directory = Path(sys.argv[1])
    failed = 0
    print("| Option | Layer | Wide wall | Reference: rock | Reference: vanilla stone | Reference: vanilla deepslate | Verdict |")
    print("|---|---|---|---|---|---|---|")
    for option in ("grain", "bedded", "mottled"):
        for layer in (1, 2, 3):
            wall = wall_score(read(directory / f"{option}-layer{layer}-wide-wall.png"))
            reference = reference_scores(read(directory / f"{option}-layer{layer}-vanilla-reference.png"))
            limit = reference["deepslate"] + MARGIN
            clean = wall <= limit and reference["rock"] <= limit
            failed += not clean and layer != COLUMNS_LAYER
            verdict = "ok" if clean else "columns" if layer == COLUMNS_LAYER else "grid"
            print(f"| {option} | {layer} | {wall:.2f} | {reference['rock']:.2f} | {reference['stone']:.2f} | {reference['deepslate']:.2f} | {verdict} |")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
