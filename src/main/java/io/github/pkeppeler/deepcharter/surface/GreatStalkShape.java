package io.github.pkeppeler.deepcharter.surface;

import java.util.function.IntUnaryOperator;

/**
 * The silhouettes a great stalk can grow in, besides the straight one: each is a generator of a {@link GreatStalkLayout} from a source of
 * random numbers ({@code random.applyAsInt(n)} is a number from 0 up to but not including {@code n}). The three are options of #910: one
 * stays and the others go.
 *
 * <p>Every shape stands on trunk cells at its lowest level and keeps its independent trunks from touching each other face to face, so a
 * collapse ({@link StalkCollapse}) takes down the trunk that broke and what hangs from it, and no other.
 */
public enum GreatStalkShape {
    /**
     * Three thin trunks that start splayed from a root flare and twist round each other, ending ragged and uncapped, with a bracket
     * shelf jutting from each trunk at its own height and side.
     */
    BRAID {
        @Override
        public GreatStalkLayout layout(IntUnaryOperator random) {
            return braid(random);
        }
    },
    /**
     * A trunk two blocks thick that leans and kinks, with a root flare, shelf caps stepping up one side, and a flat torn crown that sits
     * off centre.
     */
    CROOK {
        @Override
        public GreatStalkLayout layout(IntUnaryOperator random) {
            return crook(random);
        }
    },
    /**
     * A thick stem that forks into three crooked branches, each ending in a drooping, lopsided cap with strands hanging from it.
     */
    FORK {
        @Override
        public GreatStalkLayout layout(IntUnaryOperator random) {
            return fork(random);
        }
    };

    /** The ring of cells round the middle of the braid's 3x3 footprint, in order. */
    private static final int[][] RING = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};

    private static final int BRAID_TRUNKS = 3;

    /** The four horizontal directions as x and z steps. */
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** The layout of one stalk of this shape. */
    public abstract GreatStalkLayout layout(IntUnaryOperator random);

    private static GreatStalkLayout braid(IntUnaryOperator random) {
        GreatStalkLayout.Builder cells = new GreatStalkLayout.Builder();
        int height = 13 + random.applyAsInt(5);
        int start = random.applyAsInt(RING.length);
        int[] ringAt = {start, (start + 3) % RING.length, (start + 5) % RING.length};
        int[] top = {height - 1, height - 2, height - 4};
        int[] shelf = new int[BRAID_TRUNKS];
        shelf[2] = 4 + random.applyAsInt(2);
        shelf[1] = shelf[2] + 3;
        shelf[0] = shelf[1] + 3;
        int[][] ring = new int[BRAID_TRUNKS][height];
        for (int y = 0; y < height; y++) {
            for (int t = 0; t < BRAID_TRUNKS; t++) {
                ring[t][y] = ringAt[t];
            }
            // One trunk steps round the ring a level, never at a shelf and only while the next trunk ahead stays two
            // cells off.
            for (int t = 0; t < BRAID_TRUNKS; t++) {
                boolean onShelf = y == shelf[0] || y == shelf[1] || y == shelf[2];
                if (y >= 3 && y % 4 == (t + 2) % 4 && y < top[t] && !onShelf && gapAhead(ringAt, t) >= 3) {
                    ringAt[t] = (ringAt[t] + 1) % RING.length;
                }
            }
        }
        for (int t = 0; t < BRAID_TRUNKS; t++) {
            int owner = 1 + t;
            for (int y = 0; y <= top[t]; y++) {
                int[] at = RING[ring[t][y]];
                cells.put(
                        at[0], y, at[1], y == top[t] ? GreatStalkLayout.Part.CAP : GreatStalkLayout.Part.TRUNK, owner);
                if (y < top[t] && ring[t][y + 1] != ring[t][y]) {
                    int[] next = RING[ring[t][y + 1]];
                    cells.put(next[0], y, next[1], GreatStalkLayout.Part.TRUNK, owner);
                }
            }
            int[] base = RING[ring[t][0]];
            int[] out = outward(base, 0);
            for (int reach = 1; reach <= 2; reach++) {
                cells.putOptional(
                        base[0] + out[0] * reach, 0, base[1] + out[1] * reach, GreatStalkLayout.Part.TRUNK, owner);
            }
            cells.putOptional(base[0] + out[0], 1, base[1] + out[1], GreatStalkLayout.Part.TRUNK, owner);
            bracket(cells, RING[ring[t][shelf[t]]], shelf[t], t, owner, random);
        }
        return cells.build();
    }

    /** How far round the ring, ahead, the nearest other trunk is. */
    private static int gapAhead(int[] ringAt, int trunk) {
        int gap = RING.length;
        for (int other = 0; other < ringAt.length; other++) {
            if (other != trunk) {
                gap = Math.min(gap, (ringAt[other] - ringAt[trunk] + RING.length) % RING.length);
            }
        }
        return gap;
    }

    /** The way off the ring's cell, away from the middle: along x for a corner, straight out for an edge. */
    private static int[] outward(int[] ringCell, int alternate) {
        boolean corner = ringCell[0] != 1 && ringCell[1] != 1;
        if (ringCell[0] != 1 && (!corner || alternate % 2 == 0)) {
            return new int[] {ringCell[0] == 0 ? -1 : 1, 0};
        }
        return new int[] {0, ringCell[1] == 0 ? -1 : 1};
    }

    /** A bracket shelf off a trunk cell: three cells across the first row, two in the second and a lip hanging under the outer one. */
    private static void bracket(
            GreatStalkLayout.Builder cells,
            int[] trunk,
            int level,
            int trunkIndex,
            int owner,
            IntUnaryOperator random) {
        int[] out = outward(trunk, level + trunkIndex);
        int[] across = {out[1], out[0]};
        int lean = random.applyAsInt(2) * 2 - 1;
        for (int sideways = -1; sideways <= 1; sideways++) {
            cells.put(
                    trunk[0] + out[0] + across[0] * sideways,
                    level,
                    trunk[1] + out[1] + across[1] * sideways,
                    GreatStalkLayout.Part.CAP,
                    owner);
        }
        int outerX = trunk[0] + out[0] * 2;
        int outerZ = trunk[1] + out[1] * 2;
        cells.put(outerX, level, outerZ, GreatStalkLayout.Part.CAP, owner);
        cells.put(outerX + across[0] * lean, level, outerZ + across[1] * lean, GreatStalkLayout.Part.CAP, owner);
        cells.put(outerX, level - 1, outerZ, GreatStalkLayout.Part.CAP, owner);
    }

    /** The 2x2 trunk's lowest level and where each level of it stands: the offsets of its low corner. */
    private static int[][] kinks(IntUnaryOperator random, int levels, int firstKink, int[] lean) {
        int[][] offsets = new int[levels][2];
        int x = 0;
        int z = 0;
        int next = firstKink;
        boolean alongX = random.applyAsInt(2) == 0;
        for (int y = 0; y < levels; y++) {
            offsets[y][0] = x;
            offsets[y][1] = z;
            if (y == next) {
                x += alongX ? lean[0] : 0;
                z += alongX ? 0 : lean[1];
                alongX = !alongX;
                next += 3 + random.applyAsInt(2);
            }
        }
        return offsets;
    }

    private static void thickTrunk(GreatStalkLayout.Builder cells, int[][] offsets, int upTo) {
        for (int y = 0; y <= upTo; y++) {
            for (int dx = 0; dx < 2; dx++) {
                for (int dz = 0; dz < 2; dz++) {
                    cells.put(
                            offsets[y][0] + dx,
                            y,
                            offsets[y][1] + dz,
                            GreatStalkLayout.Part.TRUNK,
                            GreatStalkLayout.SHARED);
                }
            }
        }
    }

    /** A skirt of roots round a 2x2 base: most of the cells beside it, and some of them a block higher. */
    private static void flare(GreatStalkLayout.Builder cells, IntUnaryOperator random) {
        for (int along = 0; along < 2; along++) {
            for (int[] side : SIDES) {
                int x = side[0] == 0 ? along : (side[0] > 0 ? 2 : -1);
                int z = side[1] == 0 ? along : (side[1] > 0 ? 2 : -1);
                if (random.applyAsInt(4) != 0) {
                    cells.putOptional(x, 0, z, GreatStalkLayout.Part.TRUNK, GreatStalkLayout.SHARED);
                    if (random.applyAsInt(3) == 0) {
                        cells.putOptional(x, 1, z, GreatStalkLayout.Part.TRUNK, GreatStalkLayout.SHARED);
                    }
                }
            }
        }
    }

    private static GreatStalkLayout crook(IntUnaryOperator random) {
        GreatStalkLayout.Builder cells = new GreatStalkLayout.Builder();
        int height = 9 + random.applyAsInt(2);
        int[] lean = {random.applyAsInt(2) * 2 - 1, random.applyAsInt(2) * 2 - 1};
        int[][] offsets = kinks(random, height, 3 + random.applyAsInt(2), lean);
        thickTrunk(cells, offsets, height - 1);
        flare(cells, random);
        int[] side = SIDES[random.applyAsInt(SIDES.length)];
        // Shelves step up the one side, each a block narrower than the one below.
        for (int tier = 0; tier < 3; tier++) {
            int level = 2 + 2 * tier;
            int[] low = offsets[level];
            for (int reach = 1; reach <= 3 - tier; reach++) {
                for (int along = 0; along < 2; along++) {
                    int x = side[0] == 0 ? low[0] + along : low[0] + (side[0] > 0 ? 1 + reach : -reach);
                    int z = side[1] == 0 ? low[1] + along : low[1] + (side[1] > 0 ? 1 + reach : -reach);
                    cells.put(x, level, z, GreatStalkLayout.Part.CAP, GreatStalkLayout.SHARED);
                    if (reach == 3 - tier && along == tier % 2) {
                        cells.put(x, level - 1, z, GreatStalkLayout.Part.CAP, GreatStalkLayout.SHARED);
                    }
                }
            }
        }
        // The crown: a flat torn disc a block thick, off the trunk's middle, with a frill hanging under some of its
        // edge.
        int[] top = offsets[height - 1];
        int centreX = top[0] + random.applyAsInt(2) + random.applyAsInt(3) - 1;
        int centreZ = top[1] + random.applyAsInt(2) + random.applyAsInt(3) - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int radius = dx * dx + dz * dz;
                int x = centreX + dx;
                int z = centreZ + dz;
                boolean overTrunk = x >= top[0] && x <= top[0] + 1 && z >= top[1] && z <= top[1] + 1;
                if (radius > 5 || (!overTrunk && radius >= 4 && random.applyAsInt(5) < 2)) {
                    continue;
                }
                cells.put(x, height, z, GreatStalkLayout.Part.CAP, GreatStalkLayout.SHARED);
                if (!overTrunk && radius >= 4 && random.applyAsInt(4) == 0) {
                    cells.put(x, height - 1, z, GreatStalkLayout.Part.CAP, GreatStalkLayout.SHARED);
                }
            }
        }
        return cells.build();
    }

    /** Where each of the fork's three branches rises from, its kinks and its way out. */
    private static final int[][] BRANCH_START = {{0, 0}, {1, 1}, {2, 0}};

    private static final int[][][] BRANCH_LEAN = {{{-1, 0}, {0, -1}}, {{0, 1}, {1, 0}}, {{1, 0}, {0, -1}}};

    private static GreatStalkLayout fork(IntUnaryOperator random) {
        GreatStalkLayout.Builder cells = new GreatStalkLayout.Builder();
        int forkLevel = 4 + random.applyAsInt(2);
        int[] lean = {random.applyAsInt(2) * 2 - 1, random.applyAsInt(2) * 2 - 1};
        int[][] offsets = kinks(random, forkLevel + 1, 2 + random.applyAsInt(2), lean);
        thickTrunk(cells, offsets, forkLevel);
        flare(cells, random);
        int[] stem = offsets[forkLevel];
        for (int branch = 0; branch < BRANCH_START.length; branch++) {
            int owner = 1 + branch;
            int x = stem[0] + BRANCH_START[branch][0];
            int z = stem[1] + BRANCH_START[branch][1];
            if (branch == 2) {
                // The third branch springs sideways from the stem's top, so that it clears the other two.
                cells.put(x, forkLevel, z, GreatStalkLayout.Part.TRUNK, owner);
            }
            int length = 4 + random.applyAsInt(2);
            int[][] leans = BRANCH_LEAN[branch];
            int kinksDone = 0;
            int nextKink = forkLevel + 1 + random.applyAsInt(2);
            int topLevel = forkLevel + length;
            for (int y = forkLevel + 1; y < topLevel; y++) {
                cells.put(x, y, z, GreatStalkLayout.Part.TRUNK, owner);
                if (y == nextKink && kinksDone < 2) {
                    x += leans[kinksDone][0];
                    z += leans[kinksDone][1];
                    cells.put(x, y, z, GreatStalkLayout.Part.TRUNK, owner);
                    kinksDone++;
                    nextKink += 1 + random.applyAsInt(2);
                }
            }
            drooping(cells, x, topLevel, z, branch, owner, random);
        }
        return cells.build();
    }

    /** A cap on a branch's top: a plus with an arm missing, one arm sagging in a lobe, and strands hanging from the lobe and another arm. */
    private static void drooping(
            GreatStalkLayout.Builder cells, int x, int level, int z, int branch, int owner, IntUnaryOperator random) {
        cells.put(x, level, z, GreatStalkLayout.Part.CAP, owner);
        int missing = random.applyAsInt(SIDES.length);
        int[][] outward = BRANCH_LEAN[branch];
        int[] sag = outward[random.applyAsInt(outward.length)];
        for (int[] arm : SIDES) {
            boolean isSag = arm[0] == sag[0] && arm[1] == sag[1];
            if (isSag || SIDES[missing] != arm) {
                cells.put(x + arm[0], level, z + arm[1], GreatStalkLayout.Part.CAP, owner);
            }
        }
        cells.put(x + sag[0], level - 1, z + sag[1], GreatStalkLayout.Part.CAP, owner);
        cells.put(x + sag[0] * 2, level - 1, z + sag[1] * 2, GreatStalkLayout.Part.CAP, owner);
        cells.put(x + sag[0] * 2, level - 2, z + sag[1] * 2, GreatStalkLayout.Part.CAP, owner);
        int strands = 1 + random.applyAsInt(2);
        for (int strand = 1; strand <= strands; strand++) {
            cells.putOptional(x + sag[0] * 2, level - 2 - strand, z + sag[1] * 2, GreatStalkLayout.Part.STRAND, owner);
        }
        int[] other = {-sag[0], -sag[1]};
        if (SIDES[missing][0] != other[0] || SIDES[missing][1] != other[1]) {
            cells.putOptional(x + other[0], level - 1, z + other[1], GreatStalkLayout.Part.STRAND, owner);
            if (random.applyAsInt(2) == 0) {
                cells.putOptional(x + other[0], level - 2, z + other[1], GreatStalkLayout.Part.STRAND, owner);
            }
        }
    }
}
