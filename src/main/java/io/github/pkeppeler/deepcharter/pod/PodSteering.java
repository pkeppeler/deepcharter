package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;

/**
 * Steering a pod as a boat is steered (ADR 0095): W drives along the pod's facing, S backs up without turning, A and D
 * turn the pod's yaw, and the pilot's view does not steer. Where it differs from a boat is the grid: the drill and a
 * bore as wide as the pod turn it square to the compass, so tunnels stay straight. Server only; all state is the pod's
 * own yaw and its turn rate.
 */
final class PodSteering {
    private PodSteering() {}

    /**
     * The speed along the pod's facing the keys ask for: forward, a slower reverse, the creep of a bare A or D, or
     * none.
     */
    static double throttle(Input input, PodStats stats) {
        PodSteeringTuning tuning = PodSteeringTuning.DEFAULT;
        double top = stats.horizontalSpeed();
        if (input.forward() != input.backward()) {
            return input.forward() ? top : -top * tuning.reverseShare();
        }
        if (!input.forward() && input.left() != input.right()) {
            return top * tuning.creepShare();
        }
        return 0;
    }

    /**
     * Turns the pod by one tick of the boat's steering: A and D build up the turn rate, and the rate lingers after the
     * key is let go.
     */
    static void steer(PodEntity pod, Input input) {
        PodSteeringTuning tuning = PodSteeringTuning.DEFAULT;
        float turn = pod.turnRate() * tuning.turnDamping();
        // Loose ore in a rider's pack cramps the cab: the pod answers A and D less.
        float acceleration = tuning.turnAcceleration() * (1f - PodLooseOre.cramp(pod));
        if (input.left()) {
            turn -= acceleration;
        }
        if (input.right()) {
            turn += acceleration;
        }
        pod.setTurnRate(turn);
        pod.setYRot(Mth.wrapDegrees(pod.getYRot() + turn));
    }

    /**
     * The grid's hold on a pod in a bore as wide as itself: it squares itself and ignores steering, except that one
     * press of A or D, made while it is roughly square, turns it a quarter to the next compass direction (D clockwise),
     * eased by the align rate, so a tunnel can branch. False when the grid has no hold and the pod steers freely.
     */
    static boolean boreTurn(PodEntity pod, Input input) {
        Direction target = pod.turnTarget();
        if (target == null) {
            boolean held = input.forward() || input.backward() || input.left() || input.right() || pod.turnRate() != 0f;
            if (!held || !inBore(pod)) {
                return false;
            }
            Direction nearest = nearestCardinal(pod);
            float off = Math.abs(Mth.wrapDegrees(nearest.toYRot() - pod.getYRot()));
            boolean pressed = input.left() != input.right() && !pod.turnKeyHeld();
            if (pressed && off <= PodSteeringTuning.DEFAULT.alignDegrees()) {
                target = input.right() ? nearest.getClockWise() : nearest.getCounterClockWise();
                pod.setTurnTarget(target);
            } else {
                target = nearest;
            }
        }
        if (align(pod, target)) {
            pod.setTurnTarget(null);
        }
        return true;
    }

    /** Records whether A or D is held, for the next tick's press. */
    static void noteTurnKey(PodEntity pod, Input input) {
        pod.setTurnKeyHeld(input.left() != input.right());
    }

    /**
     * Stops the turn: an unpiloted or unpowered pod keeps its yaw, and a pod the grid holds drops the rate it had
     * built up.
     */
    static void stopTurning(PodEntity pod) {
        pod.setTurnRate(0f);
    }

    /** The compass direction the pod faces nearest. */
    static Direction nearestCardinal(PodEntity pod) {
        return Direction.fromYRot(pod.getYRot());
    }

    /**
     * Turns the pod toward {@code cardinal}, one tick of {@link PodSteeringTuning#alignDegrees}; true once it is
     * square to it.
     */
    static boolean align(PodEntity pod, Direction cardinal) {
        PodSteeringTuning tuning = PodSteeringTuning.DEFAULT;
        stopTurning(pod);
        float square = Mth.wrapDegrees(cardinal.toYRot());
        float gap = Mth.wrapDegrees(square - pod.getYRot());
        if (Math.abs(gap) <= tuning.alignTolerance()) {
            pod.setYRot(square);
            return true;
        }
        pod.setYRot(Mth.wrapDegrees(pod.getYRot() + Mth.clamp(gap, -tuning.alignDegrees(), tuning.alignDegrees())));
        return false;
    }

    /**
     * Whether the pod is pressed against rock straight ahead along {@code facing}, the push that starts a sideways
     * bore.
     */
    static boolean pressedAgainstRock(PodEntity pod, Direction facing) {
        AABB ahead = pod.getBoundingBox().move(facing.getStepX() * 0.05, 0, facing.getStepZ() * 0.05);
        return pod.level().getBlockCollisions(pod, ahead).iterator().hasNext();
    }

    /**
     * Whether the pod stands on the ground in a bore as wide as itself: rock beside it on both sides, along the way it
     * faces nearest. Only blocks in loaded chunks count.
     */
    static boolean inBore(PodEntity pod) {
        if (!pod.onGround()) {
            return false;
        }
        boolean alongX = nearestCardinal(pod).getAxis() == Direction.Axis.X;
        PodFootprint foot = PodFootprint.of(pod);
        int sideLow = alongX ? foot.lowZ() : foot.lowX();
        int alongLow = alongX ? foot.lowX() : foot.lowZ();
        LoadedBlocks blocks = new LoadedBlocks(pod.level());
        return rockBeside(blocks, foot, alongX, alongLow, sideLow - 1)
                && rockBeside(blocks, foot, alongX, alongLow, sideLow + foot.width());
    }

    private static boolean rockBeside(LoadedBlocks blocks, PodFootprint foot, boolean alongX, int alongLow, int side) {
        for (int along = alongLow; along < alongLow + foot.width(); along++) {
            for (int y = foot.feetY(); y < foot.feetY() + foot.height(); y++) {
                BlockPos pos = alongX ? new BlockPos(along, y, side) : new BlockPos(side, y, along);
                if (!blocks.getBlockState(pos).getCollisionShape(blocks, pos).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
