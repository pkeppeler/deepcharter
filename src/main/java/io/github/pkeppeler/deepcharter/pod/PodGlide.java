package io.github.pkeppeler.deepcharter.pod;

import net.minecraft.core.PositionAndRotation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AbstractInterpolationHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a pod on the client (ADR 0082). Like vanilla's linear slide it glides the pod to each move the server sends, over
 * {@link PodGlideTuning#steps()} ticks, and adds three things for a server that runs late:
 *
 * <ul>
 *   <li>With no move to follow, the pod coasts on at the speed the server last reported, for {@link PodGlideTuning#coastTicks()} ticks (the last at
 *       {@link PodGlideTuning#coastTailShare()} of it), so it does not freeze. The coast stops short of any block, so a pod that hit a wall or the ground is never drawn inside it.
 *   <li>When the moves arrive in a burst, the pod catches up at {@link PodGlideTuning#catchUp()} times its speed (and at no less than a
 *       share of its gap), so it does not lunge.
 *   <li>A pod that coasted past where the server put it stays there for {@link PodGlideTuning#settleDelayTicks()} ticks, in case a late move
 *       says it went on, then gives the excess back at {@link PodGlideTuning#settleShare()} of the gap a tick, and ends exactly on the
 *       server's last position. A move that says it stopped there gets the same gentle return at once.
 * </ul>
 *
 * The server stays the authority: the target is only ever a position the server sent, and the coast is a drawn guess that ends.
 */
final class PodGlide extends AbstractInterpolationHandler {
    /** Blocks within which the pod counts as having reached its target. */
    private static final double ARRIVED = 1e-4;

    /** Halvings of the first coast sub-step tried before the coast gives up for the tick. */
    private static final int COAST_CLIP_TRIES = 3;

    /** The last position the server sent, and the rotation with it; only a move changes it. */
    private final PositionAndRotation.Mutable target = new PositionAndRotation.Mutable();
    /** Ticks of the glide to the latest move that are left. */
    private int remaining;
    /** Ticks the pod has coasted since the latest move. */
    private int coasted;
    /** Ticks since the latest move. */
    private int sinceMove;
    /** Whether the pod is coasting now, so that it is not pulled back to its target between coasted ticks. */
    private boolean coasting;
    /** The way the pod last coasted: a target behind it means the pod went too far. Null until it coasts. */
    private @Nullable Vec3 coastDirection;

    /** Whether the glide in progress began further from its target than {@link PodGlideTuning#snapGap()}: it is not eased. */
    private boolean snapping;

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
        // Sticky until arrival: the moves that follow a teleport of a driven pod find it nearer than snapGap, and must
        // not ease the rest.
        snapping = snapping || entity.position().distanceTo(path.endPosition()) > PodGlideTuning.DEFAULT.snapGap();
        if (snapping) {
            // A far move is a teleport, not an overshoot to settle back from: a stale coast must not ease it.
            coastDirection = null;
        }
        target.set(path.endPosition(), yRot, xRot);
        remaining = interpolationSteps;
        coasted = 0;
        sinceMove = 0;
        coasting = false;
        active = true;
    }

    @Override
    protected void doInterpolate() {
        PodGlideTuning tuning = PodGlideTuning.DEFAULT;
        sinceMove++;
        Vec3 reported = entity.getDeltaMovement();
        boolean moving = reported.lengthSqr() > ARRIVED * ARRIVED;
        if (coasting) {
            if (coastOn(tuning, reported, moving)) {
                return;
            }
            coasting = false;
        }
        Vec3 gap = target.position().subtract(entity.position());
        double distance = gap.length();
        if (remaining > 0 || distance > ARRIVED) {
            glide(tuning, gap, distance, reported.length(), moving);
        } else if (coastOn(tuning, reported, moving)) {
            coasting = true;
        } else {
            coastDirection = null;
            active = false;
        }
    }

    /** Moves the pod toward the target, by a share of the gap that never lunges, and turns it with it. */
    private void glide(PodGlideTuning tuning, Vec3 gap, double distance, double speed, boolean moving) {
        double share = 1.0 / Math.max(remaining, 1);
        double step;
        if (coastDirection != null && gap.dot(coastDirection) < 0) {
            // The pod coasted past where the server put it. While the server still reports it moving and no move has
            // come, a late server may yet say it went on: wait. Once it reports the pod still, or a move arrives,
            // give the excess back.
            if (moving && remaining == 0 && sinceMove < tuning.settleDelayTicks()) {
                active = true;
                return;
            }
            step = distance * tuning.settleShare();
        } else if (snapping) {
            step = distance * share;
        } else {
            double pace = Math.max(speed, PodTuning.DEFAULT.movement().horizontalSpeed()) * tuning.catchUp();
            // A coasted pod sits almost on its target, and a share of that gap would crawl: while the server reports
            // motion, a tick covers at least the reported speed. A pod that never coasted trails by design and keeps
            // the share.
            double flow = moving && coastDirection != null ? speed : 0;
            step = Math.min(Math.max(distance * share, flow), Math.max(pace, distance / tuning.catchUpTicks()));
        }
        boolean arrives = distance <= step + ARRIVED;
        entity.setPos(arrives ? target.position() : entity.position().add(gap.scale(step / distance)));
        entity.setYRot((float) Mth.rotLerp(share, entity.getYRot(), target.yRot()));
        entity.setXRot((float) Mth.lerp(share, entity.getXRot(), target.xRot()));
        remaining = Math.max(0, remaining - 1);
        if (arrives) {
            if (coastDirection != null) {
                // The floor ran a coasted pod onto its target early: finish the turn and coast on.
                entity.setYRot(target.yRot());
                entity.setXRot(target.xRot());
                remaining = 0;
            }
            coastDirection = null;
            snapping = false;
        }
        active = true;
    }

    /** Coasts one tick if the server reports the pod moving and the coast has ticks left; false when the pod does not coast. */
    private boolean coastOn(PodGlideTuning tuning, Vec3 reported, boolean moving) {
        if (!moving || coasted >= tuning.coastTicks()) {
            return false;
        }
        // The last coast tick eases off, so a late server slows the pod instead of stopping it dead.
        boolean tail = coasted == tuning.coastTicks() - 1;
        if (!coast(tail ? reported.scale(tuning.coastTailShare()) : reported)) {
            return false;
        }
        coasted++;
        return true;
    }

    /**
     * Draws the pod one more tick by {@code drawn}, short of any block; false when it cannot move at all. The tick is swept in
     * sub-steps no longer than the box's smallest side (at most {@link PodGlideTuning#coastSweepSteps()} of them, so a huge synced speed costs a bounded
     * number of checks), so a step bigger than the box cannot skip a thin block.
     */
    private boolean coast(Vec3 drawn) {
        AABB box = entity.getBoundingBox();
        double side = Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize()));
        int pieces = Math.clamp((long) Math.ceil(drawn.length() / side), 1, PodGlideTuning.DEFAULT.coastSweepSteps());
        Vec3 piece = drawn.scale(1.0 / pieces);
        Vec3 step = Vec3.ZERO;
        for (int i = 1; i <= pieces && entity.level().noCollision(entity, box.move(piece.scale(i))); i++) {
            step = piece.scale(i);
        }
        for (int tries = 0; step.equals(Vec3.ZERO) && tries < COAST_CLIP_TRIES; tries++) {
            piece = piece.scale(0.5);
            if (entity.level().noCollision(entity, box.move(piece))) {
                step = piece;
            }
        }
        if (step.equals(Vec3.ZERO)) {
            return false;
        }
        entity.setPos(entity.position().add(step));
        coastDirection = drawn;
        active = true;
        return true;
    }

    @Override
    public boolean hasActiveInterpolation() {
        return active;
    }

    @Override
    public void cancel() {
        remaining = 0;
        coasting = false;
        coastDirection = null;
        snapping = false;
        active = false;
    }
}
