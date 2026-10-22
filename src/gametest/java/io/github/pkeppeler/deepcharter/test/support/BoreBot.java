package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Input;

/**
 * What the bore bots share: the pilot's inputs, the stuck-step constants, the four sides a bot steps to, and the pilot's look at the
 * lining. The pod faces south (yaw 0), so forward is south and left is east.
 */
public final class BoreBot {
    private BoreBot() {}

    public static final Input IDLE = new Input(false, false, false, false, false, false, false);
    public static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    /** The rotor key (the jump key, held in the air). */
    public static final Input ROTOR = new Input(false, false, false, false, true, false, false);

    public static final Input SPRINT = new Input(false, false, false, false, false, false, true);
    private static final Input STRAFE_EAST = new Input(false, false, true, false, false, false, false);
    private static final Input STRAFE_WEST = new Input(false, false, false, true, false, false, false);
    private static final Input DRIVE_NORTH = new Input(false, true, false, false, false, false, false);

    /** Ticks a pod on the ground that is not drilling, or a sidestep that does not move, waits before the bot gives up on it. */
    public static final int STUCK_TICKS = 12;
    /** How far a sidestep goes, in blocks. */
    public static final int SIDE_STEP = 2;

    /**
     * A way to step aside: the input that drives the pod that way (it faces south), and the axis it moves along. The bots pick the next
     * side in different orders, and each bot's measured table depends on its own: {@link #nextInDeclarationOrder()} against
     * {@link #turnedRight()}, {@link #turnedLeft()} and {@link #opposite()}. Neither changes without re-running that bot's table.
     */
    public enum Side {
        EAST(1, 0, STRAFE_EAST),
        WEST(-1, 0, STRAFE_WEST),
        SOUTH(0, 1, FORWARD),
        NORTH(0, -1, DRIVE_NORTH);

        public final int dx;
        public final int dz;
        public final Input input;

        Side(int dx, int dz, Input input) {
            this.dx = dx;
            this.dz = dz;
            this.input = input;
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
