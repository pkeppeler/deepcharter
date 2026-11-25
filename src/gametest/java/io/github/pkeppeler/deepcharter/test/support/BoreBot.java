package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;

/**
 * What the bore bots share: the pilot's inputs, the stuck-step constants, the four sides a bot steps to, and the
 * pilot's look at the lining. A bot steps to a side by turning the pod to face it and driving forward, as a pilot does
 * with A and D and W; the turn is staged ({@link Side#face}) and not steered, so the bore tables keep the timing they
 * were measured with.
 */
public final class BoreBot {
    private BoreBot() {}

    /** The rotor key (the jump key, held in the air). */
    public static final Input ROTOR = Inputs.JUMP;

    /** Ticks a pod on the ground that is not drilling, or a sidestep that does not move, waits before the bot gives up on it. */
    public static final int STUCK_TICKS = 12;
    /** How far a sidestep goes, in blocks. */
    public static final int SIDE_STEP = 2;

    /**
     * A way to step aside: the input that drives the pod that way once it faces it, and the axis it moves along. The
     * Kells bot steps with {@link #nextInDeclarationOrder()}, the Lava bot with {@link #turnedRight()}, {@link
     * #turnedLeft()} and {@link #opposite()}; changing either order moves that bot's measured table.
     */
    public enum Side {
        EAST(1, 0),
        WEST(-1, 0),
        SOUTH(0, 1),
        NORTH(0, -1);

        public final int dx;
        public final int dz;
        public final Input input;

        Side(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
            this.input = Inputs.FORWARD;
        }

        /** Turns the pod to face this side, so that {@link #input} drives it that way. */
        public void face(PodEntity pod) {
            pod.setYRot(Mth.wrapDegrees(direction().toYRot()));
        }

        /** The compass direction of this side, as the sounder's marks name it. */
        public Direction direction() {
            return switch (this) {
                case EAST -> Direction.EAST;
                case WEST -> Direction.WEST;
                case SOUTH -> Direction.SOUTH;
                case NORTH -> Direction.NORTH;
            };
        }

        /** How far the pod is along this side's direction, in blocks from the world's origin. */
        public double along(PodEntity pod) {
            return pod.getX() * dx + pod.getZ() * dz;
        }

        /** The next side to try in declaration order: east, west, south, north, and round. */
        public Side nextInDeclarationOrder() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Side opposite() {
            return switch (this) {
                case EAST -> WEST;
                case WEST -> EAST;
                case SOUTH -> NORTH;
                case NORTH -> SOUTH;
            };
        }

        /** The next side round the compass clockwise from above: east, south, west, north. */
        public Side turnedRight() {
            return switch (this) {
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                case NORTH -> EAST;
            };
        }

        public Side turnedLeft() {
            return turnedRight().opposite();
        }
    }

    /** The input with the rotor key held as well. */
    public static Input withRotor(Input input) {
        return new Input(
                input.forward(), input.backward(), input.left(), input.right(), true, input.shift(), input.sprint());
    }

    /** Whether the lining has a lava cell to take: the pilot sees it glowing in the wall. */
    public static boolean lavaToLine(PodEntity pod) {
        return PodLining.cellsToLine(pod).stream()
                .anyMatch(cell -> pod.level().getFluidState(cell).is(FluidTags.LAVA));
    }
}
