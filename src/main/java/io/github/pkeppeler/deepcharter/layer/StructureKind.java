package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The structures of the first two layers (lore canon, section 11). Each one stands in one zone, and is drawn in its own
 * coordinates by {@link #draw}: {@code u} along its long axis, {@code v} across it, {@code y} up from the floor of its hollow.
 * The Note numbers are the permanent numbers of {@code docs/lore/notes.md} (ADR 0020).
 */
public enum StructureKind {
    /** A hand-dug first-generation shaft through Topsoil Claims, with a candle niche that holds N05, the survey stake tag. */
    TOPSOIL_SHAFT(1, 0, 2, 2, OptionalInt.empty()) {
        @Override
        void draw(StructurePlan p, int height) {
            shaft(p, height, 5);
        }
    },
    /** The same shaft through Stone Benches, with N06, the note in a lunch pail. */
    BENCHES_SHAFT(1, 1, 2, 2, OptionalInt.empty()) {
        @Override
        void draw(StructurePlan p, int height) {
            shaft(p, height, 6);
        }
    },
    /** The same shaft through Deep Claim, with N07, the prayer card. */
    DEEP_SHAFT(1, 2, 2, 2, OptionalInt.empty()) {
        @Override
        void draw(StructurePlan p, int height) {
            shaft(p, height, 7);
        }
    },
    /**
     * A collapsed gallery in the Upper Levels: timber props, ore piled and never hauled, rubble across the far end and the
     * quota board (N08) on the near end wall.
     */
    GALLERY(2, 0, 13, 2, OptionalInt.of(4)) {
        @Override
        void draw(StructurePlan p, int height) {
            p.air(-12, 0, -1, 12, 3, 1);
            p.box(-12, -1, -1, 12, -1, 1, Blocks.COBBLESTONE.defaultBlockState());
            for (int u = -12; u <= 12; u++) {
                if (!p.roll(u, 0, 0, 0.15)) {
                    p.set(u, 0, 0, p.railAlongU());
                }
            }
            for (int u = -8; u <= 8; u += 4) {
                for (int v = -1; v <= 1; v += 2) {
                    p.box(u, 0, v, u, 2, v, Blocks.OAK_FENCE.defaultBlockState());
                }
                p.box(u, 3, -1, u, 3, 1, Blocks.OAK_PLANKS.defaultBlockState());
            }
            for (int u = 5; u <= 12; u++) {
                for (int v = -1; v <= 1; v++) {
                    for (int y = 0; y <= 3; y++) {
                        if (p.roll(u, y, v, (u - 4) / 8.0)) {
                            p.set(u, y, v, Blocks.COBBLESTONE.defaultBlockState());
                        }
                    }
                }
            }
            p.box(-7, 0, 1, -6, 0, 1, Blocks.RAW_IRON_BLOCK.defaultBlockState());
            p.set(-7, 0, -1, Blocks.RAW_IRON_BLOCK.defaultBlockState());
            p.box(-13, 1, -1, -13, 2, 1, Blocks.CONCRETE.black().defaultBlockState());
            p.set(-12, 0, 0, Blocks.SPRUCE_PLANKS.defaultBlockState());
            p.set(-12, 1, 0, NoteBlock.stateOf(8));
        }
    },
    /**
     * The colony's underground rail hub in Shift Change: two tracks, a wall of shelves for the time cards, the punch clock that
     * is still lit and the Note (N09) on the table before the shelves.
     */
    PUNCH_CLOCK(2, 1, 8, 8, OptionalInt.of(5)) {
        @Override
        void draw(StructurePlan p, int height) {
            p.air(-8, 0, -8, 8, 4, 8);
            p.box(-8, -1, -8, 8, -1, 8, Blocks.POLISHED_ANDESITE.defaultBlockState());
            for (int v = -5; v <= 5; v += 10) {
                for (int u = -8; u <= 8; u++) {
                    if (!p.roll(u, 0, v, 0.1)) {
                        p.set(u, 0, v, p.railAlongU());
                    }
                }
            }
            for (int u = -4; u <= 4; u += 8) {
                for (int v = -2; v <= 2; v += 4) {
                    p.box(u, 0, v, u, 4, v, Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
            p.box(7, 0, -6, 8, 3, 6, Blocks.BOOKSHELF.defaultBlockState());
            p.box(-7, 0, 0, -7, 1, 0, Blocks.IRON_BLOCK.defaultBlockState());
            p.set(-7, 2, 0, Blocks.SEA_LANTERN.defaultBlockState());
            p.set(5, 0, 0, Blocks.SPRUCE_PLANKS.defaultBlockState());
            p.set(5, 1, 0, NoteBlock.stateOf(9));
        }
    },
    /** The rail line of Prospector's Run: a long timbered drift with a single track, broken in places, and nothing at the end of it but the Kells' shaft. */
    RAILS(2, 2, 32, 2, OptionalInt.of(4)) {
        @Override
        int margin() {
            // The shaft hangs off the line's end, and its shell is one more block out.
            return KELLS_OFFSET + KELLS_SHAFT.halfU() + 2;
        }

        @Override
        void draw(StructurePlan p, int height) {
            p.air(-32, 0, -2, 32, 3, 2);
            p.box(-32, -1, -2, 32, -1, 2, Blocks.COARSE_DIRT.defaultBlockState());
            for (int u = -32; u <= 32; u++) {
                if (!p.roll(u, 0, 0, 0.12)) {
                    p.set(u, 0, 0, p.railAlongU());
                }
            }
            for (int u = -32; u <= 32; u += 8) {
                for (int v = -2; v <= 2; v += 4) {
                    p.box(u, 0, v, u, 2, v, Blocks.OAK_LOG.defaultBlockState());
                }
                p.box(u, 3, -2, u, 3, 2, Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
    },
    /**
     * The Kells' shaft: a 2 x 2 hollow, 20 to 80 blocks deep, from a drift at the end of the rail line to the first block above
     * the crust. Where it was cut through lava the Kells walled it in slag brick, two to four rings of three rows, and in the
     * middle row of each ring the lining has failed: one block of the wall is lava, with one more behind it, and across the hollow
     * at that row a bulkhead of brick, which a pod lands on, with a hole beside the gap and lava under the hole. A pod can
     * go down, and its hand lining closes the gaps. Timber sets, some fallen, line the rest. At the head, in the walls of the
     * drift, is the reserve they left: {@value #KELLS_RESERVE_PER_GAP} bricks for each gap.
     */
    KELLS_SHAFT(2, 2, 4, 2, OptionalInt.empty()) {
        @Override
        void draw(StructurePlan p, int height) {
            kellsShaft(p, height);
        }
    },
    /**
     * A wreck site in Prospector's Run: a bay with a scorched floor, a rail that ends in it, and debris at the edges. The middle
     * is clear for a pod, which a later issue sets there (the site is {@code origin}).
     */
    WRECK(2, 2, 6, 6, OptionalInt.of(5)) {
        @Override
        void draw(StructurePlan p, int height) {
            p.air(-6, 0, -6, 6, 4, 6);
            p.box(-6, -1, -6, 6, -1, 6, Blocks.DEEPSLATE_TILES.defaultBlockState());
            p.box(-2, -1, -2, 2, -1, 2, Blocks.BLACKSTONE.defaultBlockState());
            p.box(-6, 0, 0, -3, 0, 0, p.railAlongU());
            for (int u = -6; u <= 6; u++) {
                for (int v = -6; v <= 6; v++) {
                    if (Math.max(Math.abs(u), Math.abs(v)) >= 3 && p.roll(u, 0, v, 0.12)) {
                        p.set(
                                u,
                                0,
                                v,
                                p.roll(u, 1, v, 0.5)
                                        ? Blocks.IRON_BLOCK.defaultBlockState()
                                        : Blocks.COBBLESTONE.defaultBlockState());
                    }
                }
            }
        }
    };

    /** Blocks from the centre of the rail line to the centre of the Kells' shaft, along the line: 4 past the end the line stops at. */
    public static final int KELLS_OFFSET = 36;

    /** Blocks from the shaft's floor row to its top row, counted down from the rail line's floor: the drift that joins the line is this tall. */
    static final int DRIFT_HEIGHT = 3;

    private final int layer;
    private final int zone;
    private final int halfU;
    private final int halfV;
    private final OptionalInt fixedHeight;

    /**
     * @param layer       the layer it stands in, counted from 1
     * @param zone        the zone it stands in, 0 for the top third
     * @param halfU       blocks from the centre to the edge along its long axis
     * @param halfV       blocks from the centre to the edge across it
     * @param fixedHeight blocks of hollow; empty for a structure that fills the usable height of its zone
     */
    StructureKind(int layer, int zone, int halfU, int halfV, OptionalInt fixedHeight) {
        this.layer = layer;
        this.zone = zone;
        this.halfU = halfU;
        this.halfV = halfV;
        this.fixedHeight = fixedHeight;
    }

    /** Draws the structure in its own coordinates; {@code height} is the blocks of hollow, from {@link #heightIn}. */
    abstract void draw(StructurePlan p, int height);

    public int layer() {
        return layer;
    }

    public int zone() {
        return zone;
    }

    int halfU() {
        return halfU;
    }

    int halfV() {
        return halfV;
    }

    /** Blocks from the centre to the edge on the longer axis, which the spacing cell must leave room for. */
    int reach() {
        return Math.max(halfU, halfV);
    }

    /** Blocks a site keeps from the edge of its spacing cell, so that its shell lies in the cell too. */
    int margin() {
        return reach() + 1;
    }

    /** Blocks of hollow in {@code usable} heights: the floor and the roof each take one block of them. */
    int heightIn(Zones.Span usable) {
        return fixedHeight.orElse(usable.size() - 2);
    }

    /** The kinds that stand in {@code layer}; none for a layer with no structures yet. */
    public static List<StructureKind> inLayer(int layer) {
        return Arrays.stream(values()).filter(kind -> kind.layer == layer).toList();
    }

    /**
     * A side cell of the Kells' shaft's ring (a pod's lining reaches the cells beside it, not the corners), and the way out from it.
     *
     * @param u    along the line
     * @param v    across it
     * @param outU the step along the line from the hollow's cell beside it to it, and on to the rock behind
     * @param outV the same across the line
     */
    public record RingSide(int u, int v, int outU, int outV) {
        /** The cell of the hollow beside this one, which a bulkhead has the hole in. */
        public int holeU() {
            return u - outU;
        }

        public int holeV() {
            return v - outV;
        }
    }

    private static final List<RingSide> KELLS_SIDES = List.of(
            new RingSide(-2, -1, -1, 0),
            new RingSide(-2, 0, -1, 0),
            new RingSide(1, -1, 1, 0),
            new RingSide(1, 0, 1, 0),
            new RingSide(-1, -2, 0, -1),
            new RingSide(0, -2, 0, -1),
            new RingSide(-1, 1, 0, 1),
            new RingSide(0, 1, 0, 1));

    /** The side cells of the Kells' shaft's ring, in the shaft's own axes: where a gap can be. */
    public static List<RingSide> kellsRingSides() {
        return KELLS_SIDES;
    }

    /** The weeps in the shaft are placed in this many rows clear of the drift at the top and of the bottom. */
    private static final int KELLS_TOP_CLEARANCE = 8;

    private static final int KELLS_BOTTOM_CLEARANCE = 3;
    /** Rows of slag brick each way from the row of a gap. */
    private static final int KELLS_WALL_REACH = 1;

    /** A set of timber every this many rows. */
    private static final int KELLS_SET_SPACING = 3;

    /**
     * Slag bricks in the reserve at the head for each gap. A pilot with no hopper lined a gap of 30 shafts from bricks carried
     * in the pack with 2.1 on average (the first gap 4 at most; #716, {@code KellsShaftBoreTest}), so the reserve holds 3.
     */
    public static final int KELLS_RESERVE_PER_GAP = 3;

    /** The least rows between one gap's row and the next: a gap's wall is three rows, and no gap is in another's wall. */
    private static final int KELLS_GAP_SPACING = 3;

    /**
     * The rows of the gaps of the Kells' shaft at {@code site}, from the lowest up: 2 to 4 of them (fewer in a shaft too short
     * to keep {@link #KELLS_GAP_SPACING} between them), each in a band of its own, at least {@link #KELLS_GAP_SPACING} rows from the next.
     */
    public static int[] kellsGapRows(StructureSite site) {
        int gaps = 2 + StructurePlan.pick(site, 0, 3);
        int room = site.height() - KELLS_TOP_CLEARANCE - KELLS_BOTTOM_CLEARANCE + 1;
        gaps = Math.min(gaps, room / KELLS_GAP_SPACING);
        int span = room / gaps;
        int[] rows = new int[gaps];
        for (int k = 0; k < gaps; k++) {
            rows[k] = KELLS_BOTTOM_CLEARANCE + k * span + 1 + StructurePlan.pick(site, 10 + k, span - 2);
        }
        return rows;
    }

    /** The shaft in its own axes: the hollow is {@code u, v} from -1 to 0, its ring from -2 to 1, and the line comes in from {@code u = -4}. */
    private static void kellsShaft(StructurePlan p, int height) {
        BlockState brick = SlagBrick.BLOCK.defaultBlockState();
        int[] rows = kellsGapRows(p.site());
        int gaps = rows.length;
        p.air(-1, 0, -1, 0, height - 1, 0);
        BlockState lava = Blocks.LAVA.defaultBlockState();
        // Timber sets, a quarter of their blocks fallen.
        for (int y = 1; y < height - DRIFT_HEIGHT - 1; y += KELLS_SET_SPACING) {
            for (int u = -2; u <= 1; u++) {
                for (int v = -2; v <= 1; v++) {
                    if (inRing(u, v) && !p.roll(u, y, v, 0.25)) {
                        p.set(u, y, v, Blocks.OAK_PLANKS.defaultBlockState());
                    }
                }
            }
        }
        for (int k = 0; k < gaps; k++) {
            for (int y = rows[k] - KELLS_WALL_REACH; y <= rows[k] + KELLS_WALL_REACH; y++) {
                for (int u = -2; u <= 1; u++) {
                    for (int v = -2; v <= 1; v++) {
                        if (inRing(u, v)) {
                            p.set(u, y, v, brick);
                        }
                    }
                }
            }
            RingSide side = kellsRingSides().get(p.pick(20 + k, kellsRingSides().size()));
            p.set(side.u(), rows[k], side.v(), lava);
            p.set(side.u() + side.outU(), rows[k], side.v() + side.outV(), lava);
            // The bulkhead: a plug of brick across the hollow at the gap's row, which a pod lands on, so that
            // the gap is one row below its feet, in reach of its hand lining. The plug has a hole beside the
            // gap and lava under the hole, in reach of the lining too: a pod that bores the plug without lining sinks
            // into it.
            p.box(-1, rows[k], -1, 0, rows[k], 0, brick);
            p.set(side.holeU(), rows[k], side.holeV(), Blocks.AIR.defaultBlockState());
            p.set(side.holeU(), rows[k] - 1, side.holeV(), lava);
        }
        // The drift: the line's last column, a rail to the lip, and the reserve in the wall.
        int floor = height - DRIFT_HEIGHT;
        p.air(-4, floor, -1, -2, height - 1, 0);
        p.box(-4, floor, 0, -2, floor, 0, p.railAlongU());
        for (int i = 0; i < gaps * KELLS_RESERVE_PER_GAP; i++) {
            int slot = i / 2;
            p.set(-3 + slot % 2, floor + slot / 2, i % 2 == 0 ? 1 : -2, brick);
        }
    }

    /** Whether a cell of the Kells' shaft is in the ring round its 2 x 2 hollow. */
    private static boolean inRing(int u, int v) {
        return u < -1 || u > 0 || v < -1 || v > 0;
    }

    /** A 3 x 3 shaft with a ladder, timber collars, and in the middle a candle niche in the east wall: the Note, and a lit candle beside it. */
    private static void shaft(StructurePlan p, int height, int note) {
        p.air(-1, 0, -1, 1, height - 1, 1);
        p.box(-1, -1, -1, 1, -1, 1, Blocks.STONE_BRICKS.defaultBlockState());
        // The ladder hangs on this column, so it is solid whatever rock the shaft was cut through.
        p.box(-2, 0, -1, -2, height - 1, -1, Blocks.STONE_BRICKS.defaultBlockState());
        for (int y = 3; y < height; y += 8) {
            for (int u = -2; u <= 2; u++) {
                for (int v = -2; v <= 2; v++) {
                    if (Math.max(Math.abs(u), Math.abs(v)) == 2) {
                        p.set(u, y, v, Blocks.OAK_PLANKS.defaultBlockState());
                    }
                }
            }
        }
        for (int y = 0; y < height; y++) {
            p.set(-1, y, -1, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, p.facing(Direction.EAST)));
        }
        int niche = height / 2;
        p.air(2, niche, 0, 2, niche + 1, 1);
        p.box(2, niche - 1, 0, 2, niche - 1, 1, Blocks.STONE_BRICKS.defaultBlockState());
        p.box(2, niche + 2, 0, 2, niche + 2, 1, Blocks.STONE_BRICKS.defaultBlockState());
        p.set(2, niche, 0, NoteBlock.stateOf(note));
        p.set(2, niche, 1, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true));
    }
}
