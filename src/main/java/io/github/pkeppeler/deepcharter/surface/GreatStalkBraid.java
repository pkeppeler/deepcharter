package io.github.pkeppeler.deepcharter.surface;

import java.util.function.IntUnaryOperator;

/**
 * The shape of a great stalk (#910): a braid. Three thin trunks start splayed from a root flare and twist round each other, ending
 * ragged and uncapped, with a bracket shelf jutting from each trunk at its own height and side. The generator makes the blocks as a
 * {@link GreatStalkLayout}, pure data, so the shape can be inspected without a world.
 *
 * <p>The trunks stand on the cells of a 3x3 ring and never touch each other face to face, so a collapse ({@link StalkCollapse}) takes
 * down the trunk that broke and what hangs from it, and no other. Every cell of a stalk is made of the stalk's own blocks.
 */
public final class GreatStalkBraid {
    /** The ring of cells round the middle of the braid's 3x3 footprint, in order. */
    private static final int[][] RING = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};

    /** The fewest levels any trunk of a braid has: its shortest, whose top is four below the tallest, of 13 at the least. */
    public static final int LEAST_HEIGHT = 9;

    private static final int BRAID_TRUNKS = 3;

    private GreatStalkBraid() {}

    /** The layout of one great stalk, from a source of random numbers ({@code random.applyAsInt(n)} is from 0 up to but not including n). */
    public static GreatStalkLayout layout(IntUnaryOperator random) {
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
                cells.putOptional(base[0] + out[0] * reach, 0, base[1] + out[1] * reach, owner);
            }
            cells.putOptional(base[0] + out[0], 1, base[1] + out[1], owner);
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
}
