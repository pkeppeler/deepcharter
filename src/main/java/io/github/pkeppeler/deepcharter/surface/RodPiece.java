package io.github.pkeppeler.deepcharter.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * One cell's piece of a round fungal rod, a parasol's stem or a spire (#1202): straight up, or a step of a lean that
 * moves the rod one cell sideways over {@code n} rows (n odd: 1, 3 or 5) toward the block's {@code facing}. A lean
 * starts and ends at the middle of its cells, so it joins a straight piece without a step. Its middle row crosses the
 * side of the cell half-way up, and that row is split between the two cells it crosses ({@code lo} in the first, {@code
 * hi} beside it), so every cell of a rod touches the next face to face and a collapse ({@link StalkCollapse}) follows
 * it.
 *
 * <p>The geometry, in pixels of the cell: the rod's centre is {@link #from()} pixels off the middle of the cell at row
 * {@link #bottom()}, and {@link #to()} at row {@link #top()}, toward {@code facing}. {@code tools/fungus/make.py} draws
 * the same numbers.
 */
public enum RodPiece implements StringRepresentable {
    STRAIGHT(0, 0, false),
    /**
     * Straight up, with a branch springing from it toward {@code facing}: the lower half of a one-row, 45 degree lean.
     */
    FORK(0, 0, false),
    S1_0LO(1, 0, true),
    S1_0HI(1, 0, false),
    S3_0(3, 0, false),
    S3_1LO(3, 1, true),
    S3_1HI(3, 1, false),
    S3_2(3, 2, false),
    S5_0(5, 0, false),
    S5_1(5, 1, false),
    S5_2LO(5, 2, true),
    S5_2HI(5, 2, false),
    S5_3(5, 3, false),
    S5_4(5, 4, false);

    /** The rows one lean takes to move a cell, or 0 for a piece that stands straight. */
    private final int rows;
    /** Which row of the lean this is, from 0. */
    private final int step;

    /** Whether this is the lower half of a split row: the next piece is beside it, toward {@code facing}, not above. */
    private final boolean lo;

    RodPiece(int rows, int step, boolean lo) {
        this.rows = rows;
        this.step = step;
        this.lo = lo;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether the next piece of the rod is in the cell beside this one, toward {@code facing}, rather than above. */
    public boolean stepsAside() {
        return lo;
    }

    /** Whether this piece fills its cell's full height: neither half of a split row. */
    public boolean fullRow() {
        return !lo && !isHi();
    }

    private boolean isHi() {
        return rows > 0 && step == (rows - 1) / 2 && !lo;
    }

    /** The row, in pixels of the cell, where the piece starts. */
    public double bottom() {
        return isHi() ? 8 : 0;
    }

    /** The row, in pixels of the cell, where the piece ends. */
    public double top() {
        return lo ? 8 : 16;
    }

    /** The rod's centre at {@link #bottom()}, in pixels toward {@code facing} from the middle of the cell. */
    public double from() {
        if (rows == 0) {
            return 0;
        }
        if (isHi()) {
            return -8;
        }
        return offset(step);
    }

    /** The rod's centre at {@link #top()}, in pixels toward {@code facing} from the middle of the cell. */
    public double to() {
        if (rows == 0) {
            return 0;
        }
        if (lo) {
            return 8;
        }
        return offset(step + 1);
    }

    /** Where the rod's centre is at the bottom of row {@code k} of the lean, in the cell that row starts in. */
    private double offset(int k) {
        double across = 16.0 * k / rows;
        return k > (rows - 1) / 2 ? across - 16 : across;
    }

    /**
     * The pieces of one lean of {@code rows} rows, in the order a rod grows them: each full row moves up a cell after
     * it, each {@code lo} moves a cell toward {@code facing}.
     */
    public static List<RodPiece> lean(int rows) {
        List<RodPiece> out = new ArrayList<>();
        for (RodPiece piece : values()) {
            if (piece.rows == rows) {
                out.add(piece);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("a rod leans over 1, 3 or 5 rows, not " + rows);
        }
        return out;
    }
}
