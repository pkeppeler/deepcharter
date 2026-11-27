package io.github.pkeppeler.deepcharter.surface;

import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * What one cell of a giant fungus is (#1202), as pure data: the block and the look it takes, with no world behind it.
 * Each piece knows its own mirror image, so a layout can be turned to fit its chunk. {@link GiantFungus} makes block
 * states of them.
 */
public sealed interface GiantPiece {
    /** This piece as it is seen in a mirror across {@code axis}: a face along that axis turns round, the rest stays. */
    GiantPiece mirrored(Direction.Axis axis);

    /** {@code facing} in a mirror across {@code axis}. */
    private static Direction mirror(Direction facing, Direction.Axis axis) {
        return facing.getAxis() == axis ? facing.getOpposite() : facing;
    }

    /**
     * A corner between {@code facing} and the face clockwise of it, in a mirror across {@code axis}, named the same
     * way: by the face whose clockwise neighbour is the other.
     */
    private static Direction mirrorCorner(Direction facing, Direction.Axis axis) {
        Direction first = mirror(facing, axis);
        Direction second = mirror(facing.getClockWise(), axis);
        return first.getClockWise() == second ? first : second;
    }

    /**
     * A cell of a parasol's stem. {@code facing} is the way it leans, if it leans. The stem is a core with an arm on
     * each side of it (#1218): {@code arm} is which side of the core this cell is on, seen facing {@code facing}, or
     * {@link Arm#NONE} for the core. An arm leans as its core does.
     */
    record Stem(RodPiece piece, Direction facing, Arm arm) implements GiantPiece {
        @Override
        public Stem mirrored(Direction.Axis axis) {
            // A mirror swaps left and right, whichever way the stem faces.
            Arm turned = switch (arm) {
                case LEFT -> Arm.RIGHT;
                case RIGHT -> Arm.LEFT;
                // enum-default: the other arms (none, front, back) lie on the mirror's axis and stay
                default -> arm;
            };
            return new Stem(piece, mirror(facing, axis), turned);
        }
    }

    /**
     * Which side of a thick stem's core a cell of it is on, seen facing the way the stem leans: ahead, behind, to the
     * left (the face counter-clockwise of it, seen from above) or to the right; or the core itself.
     */
    enum Arm implements StringRepresentable {
        NONE,
        FRONT,
        BACK,
        LEFT,
        RIGHT;

        /** The side of a core facing {@code facing} that this arm is on. */
        public Direction side(Direction facing) {
            return switch (this) {
                case NONE -> throw new IllegalStateException("the core is on no side of itself");
                case FRONT -> facing;
                case BACK -> facing.getOpposite();
                case LEFT -> facing.getCounterClockWise();
                case RIGHT -> facing.getClockWise();
            };
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The drooping ring round a parasol's stem: a straight cell of stem with the skirt on it. */
    record Ring() implements GiantPiece {
        @Override
        public Ring mirrored(Direction.Axis axis) {
            return this;
        }
    }

    /**
     * The underside of a parasol's cap, a full block whose gills run away from the stem: toward {@code facing}, or with
     * {@code diagonal} toward the corner between it and the face clockwise of it. A {@code lit} one is a gill lamp, a
     * real light.
     */
    record Gills(Direction facing, boolean diagonal, boolean lit) implements GiantPiece {
        @Override
        public Gills mirrored(Direction.Axis axis) {
            return new Gills(diagonal ? mirrorCorner(facing, axis) : mirror(facing, axis), diagonal, lit);
        }
    }

    /**
     * A cell of a parasol's cap above the gills. Its top is a plane through {@code level} pixels at the middle of the
     * cell, rising {@code riseX} pixels across the cell toward +x and {@code riseZ} toward +z (negative falls); a cell
     * inside the cap is {@code level} 16 and flat. {@code umbo} cells are the dark knob at the middle of the cap.
     */
    record Cap(int level, int riseX, int riseZ, boolean umbo) implements GiantPiece {
        @Override
        public Cap mirrored(Direction.Axis axis) {
            return switch (axis) {
                case X -> new Cap(level, -riseX, riseZ, umbo);
                case Z -> new Cap(level, riseX, -riseZ, umbo);
                case Y -> this;
            };
        }
    }

    /** How a parasol's rim ends: a lip curling down, a deep skirt (a closed bell), or a lip turned up (an old cap). */
    enum Lip implements StringRepresentable {
        DOWN,
        DEEP,
        UP;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A cell of a parasol's rim, the thin edge of the cap: its surface falls from {@code level} pixels on the inside of
     * the cell to the lip on the side open to {@code facing} (with {@code corner}, to the corner between it and the
     * face clockwise of it).
     */
    record Rim(Direction facing, boolean corner, int level, Lip lip) implements GiantPiece {
        @Override
        public Rim mirrored(Direction.Axis axis) {
            return new Rim(corner ? mirrorCorner(facing, axis) : mirror(facing, axis), corner, level, lip);
        }
    }

    /**
     * A root flare: a wedge from the floor up to the trunk on its {@code facing} side (with {@code corner}, toward the
     * corner between it and the face clockwise of it). A {@code tall} one meets a spire's mound, a low one a parasol's
     * stem.
     */
    record Flare(Direction facing, boolean corner, boolean tall) implements GiantPiece {
        @Override
        public Flare mirrored(Direction.Axis axis) {
            return new Flare(corner ? mirrorCorner(facing, axis) : mirror(facing, axis), corner, tall);
        }
    }

    /**
     * A cell of a spire, {@code girth} 1 (thinnest) to 4; a {@code taper} cell narrows to the girth below it on its way
     * up.
     */
    record Spire(int girth, RodPiece piece, Direction facing, boolean taper) implements GiantPiece {
        @Override
        public Spire mirrored(Direction.Axis axis) {
            return new Spire(girth, piece, mirror(facing, axis), taper);
        }
    }

    /**
     * The glowing top of a spire: the {@code needle} that ends it, or a glowing cell of girth 1 under the needle. A
     * {@code lit} needle is a real light.
     */
    record Tip(RodPiece piece, Direction facing, boolean needle, boolean lit) implements GiantPiece {
        @Override
        public Tip mirrored(Direction.Axis axis) {
            return new Tip(piece, mirror(facing, axis), needle, lit);
        }
    }

    /** A block of the dark root mound the spires rise from. */
    record SpireBase() implements GiantPiece {
        @Override
        public SpireBase mirrored(Direction.Axis axis) {
            return this;
        }
    }

    /** A glowcap at the giant's foot, the real light on the floor. */
    record Glowcap() implements GiantPiece {
        @Override
        public Glowcap mirrored(Direction.Axis axis) {
            return this;
        }
    }
}
