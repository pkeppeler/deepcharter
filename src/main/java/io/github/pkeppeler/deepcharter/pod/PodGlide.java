package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.PositionAndRotation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AbstractInterpolationHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a pod on the client (ADR 0082). Like vanilla's linear slide it glides the pod to each move the server sends, over
 * {@link PodGlideTuning#steps()} ticks, and adds two things for a server that runs late:
 *
 * <ul>
 *   <li>With no move to follow, the pod coasts on at the speed the server last reported, for {@link PodGlideTuning#coastTicks()} ticks, so it
 *       does not freeze.
 *   <li>When the moves arrive in a burst, the pod catches up at {@link PodGlideTuning#catchUp()} times that speed, so it does not lunge.
 * </ul>
 *
 * The server stays the authority: the next move always pulls the pod to where the server says it is.
 */
final class PodGlide extends AbstractInterpolationHandler {
    /** Blocks within which the pod counts as having reached its target. */
    private static final double ARRIVED = 1e-4;

    private final PositionAndRotation.Mutable target = new PositionAndRotation.Mutable();
    /** Ticks of the glide to the latest move that are left. */
    private int remaining;
    /** Ticks the pod has coasted since the latest move. */
    private int coasted;

    private boolean active;

    private PodGlide(Entity pod) {
        super(pod, PodGlideTuning.DEFAULT.steps());
    }

    /** Only a client draws a pod from moves; the server's copy is the truth. Runs from the entity's constructor: it reads no field. */
    static InterpolationHandler create(Entity pod) {
        return pod.level().isClientSide() ? new PodGlide(pod) : InterpolationHandler.NO_OP;
    }

    @Override
    protected PositionAndRotation.Mutable interpolationData() {
        return target;
    }

    @Override
    protected void startInterpolating(PositionPath path, float yRot, float xRot) {
        target.set(path.endPosition(), yRot, xRot);
        remaining = interpolationSteps;
        coasted = 0;
        active = true;
    }

    @Override
    protected void doInterpolate() {
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        Vec3 pos = entity.position();
        Vec3 gap = target.position().subtract(pos);
        double distance = gap.length();
        Vec3 reported = entity.getDeltaMovement();
        double speed = reported.length();
        if (remaining > 0 || distance > ARRIVED) {
            double share = 1.0 / Math.max(remaining, 1);
            double step = distance * share;
            if (speed > 0 && distance <= tuning.maxCatchUpGap()) {
                step = Math.min(step, speed * tuning.catchUp());
            }
            entity.setPos(distance <= step + ARRIVED ? target.position() : pos.add(gap.scale(step / distance)));
            entity.setYRot((float) Mth.rotLerp(share, entity.getYRot(), target.yRot()));
            entity.setXRot((float) Mth.lerp(share, entity.getXRot(), target.xRot()));
            remaining = Math.max(0, remaining - 1);
            active = true;
        } else if (speed > ARRIVED && coasted < tuning.coastTicks()) {
            Vec3 next = pos.add(reported);
            entity.setPos(next);
            target.set(next, target.yRot(), target.xRot());
            coasted++;
        } else {
            active = false;
        }
    }

    @Override
    public boolean hasActiveInterpolation() {
        return active;
    }

    @Override
    public void cancel() {
        remaining = 0;
        active = false;
    }
}
